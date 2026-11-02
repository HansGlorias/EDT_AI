"""Контракт ручной сводки; python -m unittest discover -s tests/tools -v."""
from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/scenario_results.py"
spec = importlib.util.spec_from_file_location("scenario_results", SCRIPT)
results = importlib.util.module_from_spec(spec)
spec.loader.exec_module(results)


def dataset(configuration="base"):
    return {"schema_version": 1, "synthetic": True, "conditions_id": "synthetic-8k-v1",
            "configuration_id": configuration, "task_ids": ["S05"], "planned_repetitions": 5,
            "runs": [{"run_id": configuration + "-S05-1", "configuration_id": configuration,
                      "task_id": "S05", "repetition": 1, "status": "pass", "success": True,
                      "edits_correct": True, "measurement_complete": True,
                      "model_requests": 2, "server_input_tokens": 100, "server_input_requests": 2,
                      "server_output_tokens": 20, "server_output_requests": 2,
                      "duration_ms": 1000, "first_visible_ms": 100}]}


class ScenarioResultsTest(unittest.TestCase):
    def compare(self, base=None, candidate=None):
        return results.report(base or dataset(), candidate or dataset("new"))

    def test_missing_metrics_are_unknown_and_missing_runs_remain_planned(self):
        base = dataset()
        base["runs"] = [{k: v for k, v in base["runs"][0].items()
                         if k in {"run_id", "configuration_id", "task_id", "repetition", "status",
                                  "success", "measurement_complete"}}]
        base["runs"][0]["measurement_complete"] = False
        text = self.compare(base)
        self.assertIn("1/5", text)
        self.assertIn("not_run=4", text)
        self.assertIn("unknown (0/1 полных прогонов)", text)
        self.assertIn("unknown; 0/>=0 запросов", text)
        self.assertIn("unknown (0/1 прогонов)", text)
        self.assertIn("0/0; покрытие 0/5", text)

    def test_server_and_estimates_never_mix(self):
        base = dataset()
        run = base["runs"][0]
        run.update(server_input_requests=1, server_output_tokens=None, server_output_requests=None,
                   estimated_input_tokens=700, estimated_input_requests=1,
                   estimated_output_tokens=90, estimated_output_requests=2,
                   estimate_method="chars-div3-v1")
        run["measurement_complete"] = False
        text = self.compare(base)
        self.assertIn("unknown (0/1 полных прогонов)", text)
        self.assertIn("100; 1/>=2 запросов", text)
        self.assertIn("700; 1/>=2 запросов", text)
        self.assertIn("90; 2/>=2 запросов", text)
        self.assertNotIn("890", text)
        self.assertIn("chars-div3-v1", text)

    def test_incomplete_excluded_from_full_cost_even_with_known_usage(self):
        base = dataset()
        extra = deepcopy(base["runs"][0])
        extra.update(run_id="base-S05-2", repetition=2, measurement_complete=False,
                     server_input_tokens=900, status="fail", success=False,
                     edits_correct=False, duration_ms=6000)
        base["runs"].append(extra)
        text = self.compare(base)
        self.assertIn("120 (1/2 полных прогонов)", text)
        self.assertIn("1000; 4/>=4 запросов", text)
        self.assertIn("6000 [6000, 6000] (1/1)", text)
        self.assertIn("1/2; покрытие 2/5", text)

    def test_known_zero_and_empty_sample(self):
        base = dataset()
        base["runs"][0].update(model_requests=0, server_input_tokens=0, server_input_requests=0,
                                 server_output_tokens=0, server_output_requests=0, tool_calls=0)
        candidate = dataset("new")
        text = self.compare(base, candidate)
        self.assertIn("0 (1/1 полных прогонов)", text)
        self.assertIn("0 (1/1 прогонов)", text)
        candidate["runs"] = []
        text = self.compare(base, candidate)
        self.assertIn("unknown (0/0 полных прогонов)", text)
        self.assertIn("not_run=5", text)

    def test_full_cost_uses_same_repetitions_in_both_configurations(self):
        candidate = dataset("new")
        candidate["runs"][0]["repetition"] = 2
        text = self.compare(candidate=candidate)
        self.assertEqual(text.count("unknown (0/1 полных прогонов)"), 2)
        self.assertIn("100; 2/2 запросов", text)

    def test_duplicate_ids_and_slots_rejected(self):
        for cross_file in (False, True):
            base, candidate = dataset(), dataset("new")
            if cross_file:
                candidate["runs"][0]["run_id"] = base["runs"][0]["run_id"]
            else:
                base["runs"].append(deepcopy(base["runs"][0]))
            with self.subTest(cross_file=cross_file), self.assertRaisesRegex(ValueError, "run_id"):
                self.compare(base, candidate)
        base = dataset()
        extra = deepcopy(base["runs"][0])
        extra["run_id"] = "another"
        base["runs"].append(extra)
        with self.assertRaisesRegex(ValueError, "repetition"):
            self.compare(base)

    def test_negative_boolean_and_fractional_metrics_rejected(self):
        for field in (*results.COUNTERS, *results.TOKEN_FIELDS, "repetition"):
            for value in (-1, True, 1.5):
                base = dataset()
                base["runs"][0][field] = value
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    self.compare(base)

    def test_mismatched_conditions_tasks_repetitions_and_sources_rejected(self):
        for key, value in (("task_ids", ["S06"]), ("conditions_id", "other"),
                           ("planned_repetitions", 4), ("synthetic", False)):
            candidate = dataset("new")
            candidate[key] = value
            if key == "task_ids":
                candidate["runs"][0]["task_id"] = "S06"
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.compare(candidate=candidate)
        for tasks in (["S99"], ["S05", "S05"], []):
            base = dataset()
            base["task_ids"] = tasks
            with self.assertRaises(ValueError):
                self.compare(base)

    def test_invalid_or_contradictory_runs_rejected(self):
        for changes in ({"server_input_requests": 3}, {"server_input_tokens": None},
                        {"server_output_requests": 0}, {"first_visible_ms": 1001},
                        {"configuration_id": "other"}, {"success": False},
                        {"edits_correct": False}, {"measurement_complete": None},
                        {"estimated_input_tokens": 5, "estimated_input_requests": 1},
                        {"status": "not_run", "success": False}, {"prompt": "forbidden"}):
            base = dataset()
            base["runs"][0].update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.compare(base)

    def test_blocked_and_not_run_are_reported_without_attempts(self):
        base = dataset()
        for index, status in enumerate(("blocked_environment", "not_run"), 2):
            base["runs"].append({"task_id": "S05", "run_id": f"base-{index}",
                                 "configuration_id": "base", "repetition": index,
                                 "status": status, "success": False, "measurement_complete": False})
        text = self.compare(base)
        self.assertIn("blocked_environment=1; not_run=3", text)
        self.assertIn("| 1/5 | 1/5 |", text)

    def test_cli_examples_reproducible_and_output_not_overwritten(self):
        examples = ROOT / "docs/scenarios"
        args = [sys.executable, str(SCRIPT), str(examples / "results-synthetic-baseline.json"),
                str(examples / "results-synthetic-candidate.json")]
        expected = results.report(results.read_results(args[-2]), results.read_results(args[-1]))
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "сводка.md"
            invocation = args + ["--output", str(output)]
            process = subprocess.run(invocation, capture_output=True)
            self.assertEqual(process.returncode, 0, process.stderr)
            self.assertEqual(output.read_text(encoding="utf-8"), expected)
            self.assertIn("СИНТЕТИЧЕСКИЙ", expected)
            self.assertEqual(subprocess.run(invocation, capture_output=True).returncode, 2)
            self.assertEqual(output.read_text(encoding="utf-8"), expected)

    def test_json_duplicate_keys_and_invalid_input(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.json"
            path.write_text('{"schema_version": 1, "schema_version": 1}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "JSON-ключа"):
                results.read_results(path)
            for data in ([], {}, {**dataset(), "notes": "forbidden"}):
                with self.assertRaises(ValueError):
                    results.validate(data)


if __name__ == "__main__":
    unittest.main()
