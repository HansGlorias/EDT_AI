"""Сводка ручных прогонов, без запуска модели; Python 3.10+, stdlib."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
from statistics import median
import sys

ROOT = Path(__file__).resolve().parents[1]
STATUSES = ("pass", "partial", "fail", "timeout", "blocked_environment", "not_run")
COUNTERS = ("model_requests", "tool_calls", "tool_executions", "mcp_transport_attempts",
            "repeats", "duration_ms", "first_visible_ms")
TOKEN_FIELDS = tuple(f"{source}_{direction}_{suffix}"
                     for source in ("server", "estimated")
                     for direction in ("input", "output")
                     for suffix in ("tokens", "requests"))
RUN_FIELDS = {"task_id", "run_id", "configuration_id", "repetition", "status", "success",
              "edits_correct", "measurement_complete", "estimate_method", *COUNTERS, *TOKEN_FIELDS}
FILE_FIELDS = {"schema_version", "synthetic", "conditions_id", "configuration_id",
               "task_ids", "planned_repetitions", "runs"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def identifier(value):
    return isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", value)


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Повтор JSON-ключа")
        result[key] = value
    return result


def read_results(path):
    with Path(path).open(encoding="utf-8-sig") as stream:
        data = json.load(stream, object_pairs_hook=unique_object)
    return validate(data)


def validate(data):
    require(isinstance(data, dict) and set(data) == FILE_FIELDS, "Неверные поля файла")
    require(type(data["schema_version"]) is int and data["schema_version"] == 1,
            "Нужна schema_version=1")
    require(type(data["synthetic"]) is bool, "synthetic должен быть boolean")
    for key in ("conditions_id", "configuration_id"):
        require(identifier(data[key]), f"Неверный {key}")
    require(integer(data["planned_repetitions"], 1), "planned_repetitions должен быть >= 1")
    tasks = data["task_ids"]
    require(isinstance(tasks, list) and tasks and all(identifier(t) for t in tasks),
            "Нужен непустой task_ids")
    fixture = ROOT / "tests/io.github.hansglorias.edtai.core.tests/fixtures/developer-scenarios/scenarios.json"
    known = {s["id"] for s in json.loads(fixture.read_text(encoding="utf-8-sig"))["scenarios"]}
    require(len(set(tasks)) == len(tasks) and set(tasks) <= known, "Неизвестные/повторные task_ids")
    require(isinstance(data["runs"], list), "runs должен быть массивом")
    ids, slots = set(), set()
    for run in data["runs"]:
        require(isinstance(run, dict) and set(run) <= RUN_FIELDS, "Неверные поля прогона")
        for key in ("task_id", "run_id", "configuration_id"):
            require(identifier(run.get(key)), f"Неверный {key} прогона")
        require(run["configuration_id"] == data["configuration_id"], "Смешаны configuration_id")
        require(run["task_id"] in tasks, "task_id вне плана")
        rep = run.get("repetition")
        require(integer(rep, 1) and rep <= data["planned_repetitions"], "repetition вне плана")
        require(run["run_id"] not in ids, "Дубликат run_id")
        ids.add(run["run_id"])
        slot = (run["task_id"], rep)
        require(slot not in slots, "Повтор задачи/repetition")
        slots.add(slot)
        require(run.get("status") in STATUSES, "Неизвестный status")
        require(type(run.get("success")) is bool and run["success"] == (run["status"] == "pass"),
                "success должен соответствовать status=pass")
        require(run.get("edits_correct") is None or type(run["edits_correct"]) is bool,
                "edits_correct должен быть boolean/null")
        require(not run["success"] or run.get("edits_correct") is not False,
                "pass с неверной правкой")
        require(type(run.get("measurement_complete")) is bool, "Нужен measurement_complete")
        for key in (*COUNTERS, *TOKEN_FIELDS):
            value = run.get(key)
            require(value is None or integer(value), f"{key} должен быть целым >= 0 или null")
        requests = run.get("model_requests")
        for source in ("server", "estimated"):
            for direction in ("input", "output"):
                prefix = f"{source}_{direction}"
                tokens, covered = run.get(prefix + "_tokens"), run.get(prefix + "_requests")
                require((tokens is None) == (covered is None), f"{prefix}: нужны tokens и requests вместе")
                if covered is not None:
                    require(requests is not None and covered <= requests, f"{prefix}: покрытие больше запросов")
                    require(covered > 0 or (tokens == 0 and requests == 0), f"{prefix}: нет источника токенов")
        estimated = any(run.get(f"estimated_{d}_tokens") is not None for d in ("input", "output"))
        require(identifier(run.get("estimate_method")) if estimated else run.get("estimate_method") is None,
                "Для оценок нужен estimate_method (метод и версия)")
        if run.get("first_visible_ms") is not None:
            require(run.get("duration_ms") is not None and run["first_visible_ms"] <= run["duration_ms"],
                    "first_visible_ms больше duration_ms")
        if run["status"] in ("not_run", "blocked_environment"):
            require(not run["measurement_complete"] and run.get("edits_correct") is None
                    and all(run.get(k) is None for k in (*COUNTERS, *TOKEN_FIELDS)),
                    "Незапущенный прогон должен иметь null-метрики и measurement_complete=false")
    return data


def full_usage(run):
    return (run.get("measurement_complete") and run.get("model_requests") is not None
            and all(run.get(f"server_{d}_tokens") is not None
                    and run.get(f"server_{d}_requests") == run["model_requests"]
                    for d in ("input", "output")))


def sum_cell(runs, key):
    values = [r[key] for r in runs if r.get(key) is not None]
    total = str(sum(values)) if values else "unknown"
    return f"{total} ({len(values)}/{len(runs)} прогонов)"


def time_cell(runs, key):
    values = [r[key] for r in runs if r.get(key) is not None]
    value = f"{median(values):g} [{min(values)}, {max(values)}]" if values else "unknown"
    return f"{value} ({len(values)}/{len(runs)})"


def table(headers, rows):
    return ["| " + " | ".join(headers) + " |", "| " + " | ".join(["---"] * len(headers)) + " |",
            *("| " + " | ".join(map(str, row)) + " |" for row in rows), ""]


def report(baseline, candidate):
    for data in (baseline, candidate):
        validate(data)
    require(baseline["conditions_id"] == candidate["conditions_id"], "Не совпадают conditions_id")
    require(set(baseline["task_ids"]) == set(candidate["task_ids"]), "Не совпадают task_ids")
    require(baseline["planned_repetitions"] == candidate["planned_repetitions"], "Не совпадает план повторов")
    require(baseline["synthetic"] == candidate["synthetic"], "Нельзя смешивать реальные и синтетические данные")
    require(baseline["configuration_id"] != candidate["configuration_id"], "Нужны разные configuration_id")
    require(not ({r["run_id"] for r in baseline["runs"]} & {r["run_id"] for r in candidate["runs"]}),
            "Дубликат run_id между файлами")
    lines = ["# Сравнение ручных результатов", ""]
    if baseline["synthetic"]:
        lines += ["**СИНТЕТИЧЕСКИЙ ПРИМЕР — не результаты моделей.**", ""]
    lines += [f"Условия: `{baseline['conditions_id']}`. Сопоставляются одинаковые задачи и номера повторов.",
              "Равенство conditions_id — заявление оценщика; исходные условия сверяются вручную.",
              "Малая выборка не доказывает статистическую значимость. Пропущенные строки = not_run.", ""]
    groups = []
    for label, data in (("baseline", baseline), ("candidate", candidate)):
        for task in sorted(data["task_ids"]):
            rows = [r for r in data["runs"] if r["task_id"] == task]
            groups.append((f"{label}/{data['configuration_id']}/{task}", rows, data["planned_repetitions"]))
    quality, tokens, counts, times = [], [], [], []
    paired_full = {(r["task_id"], r["repetition"]) for r in baseline["runs"] if full_usage(r)} & {
        (r["task_id"], r["repetition"]) for r in candidate["runs"] if full_usage(r)}
    for label, rows, planned in groups:
        status = Counter(r["status"] for r in rows)
        status["not_run"] += planned - len(rows)
        attempted = [r for r in rows if r["status"] not in ("not_run", "blocked_environment")]
        edits = [r["edits_correct"] for r in rows if r.get("edits_correct") is not None]
        quality.append([label, f"{status['pass']}/{planned}", f"{len(attempted)}/{planned}",
                        f"{sum(edits)}/{len(edits)}; покрытие {len(edits)}/{planned}",
                        "; ".join(f"{s}={status[s]}" for s in STATUSES if s != "pass")])
        complete = [r for r in attempted if (r["task_id"], r["repetition"]) in paired_full]
        full = str(sum(r["server_input_tokens"] + r["server_output_tokens"] for r in complete)) if complete else "unknown"
        repetitions = ",".join(str(r["repetition"]) for r in sorted(complete, key=lambda r: r["repetition"])) or "—"
        tokens.append([label, f"{full} ({len(complete)}/{len(attempted)} полных прогонов); повторы {repetitions}"])
        for source in ("server", "estimated"):
            for direction in ("input", "output"):
                key = f"{source}_{direction}"
                covered = [r for r in attempted if r.get(key + "_tokens") is not None]
                known_requests = [r["model_requests"] for r in attempted if r.get("model_requests") is not None]
                known_all = len(known_requests) == len(attempted) and all(r["measurement_complete"] for r in attempted)
                denominator = str(sum(known_requests)) if known_all else f">={sum(known_requests)}"
                value = str(sum(r[key + "_tokens"] for r in covered)) if covered else "unknown"
                tokens[-1].append(f"{value}; {sum(r[key + '_requests'] for r in covered)}/{denominator} запросов; {len(covered)}/{len(attempted)} прогонов")
        methods = sorted({r["estimate_method"] for r in attempted if r.get("estimate_method")})
        tokens[-1].append(", ".join(methods) or "—")
        counts.append([label, *(sum_cell(attempted, k) for k in COUNTERS[:5]),
                       f"{sum(r['measurement_complete'] for r in attempted)}/{len(attempted)}"])
        successes = [r for r in attempted if r["success"]]
        failures = [r for r in attempted if not r["success"]]
        times.append([label, time_cell(successes, "duration_ms"), time_cell(failures, "duration_ms"),
                      sum_cell(failures, "duration_ms"), time_cell(attempted, "first_visible_ms")])
    lines += table(["Конфигурация/задача", "Успех/план", "Начаты/план", "Корректные правки/оценённые", "Остальные статусы"], quality)
    lines += ["Токены: полная сумма включает только полные прогоны с серверным input И output на всех запросах.",
              "Для полной суммы взяты только одинаковые номера повторов с полным usage в обеих конфигурациях.",
              "Остальные суммы — известные подытоги; оценки не прибавляются к server. >= означает неизвестный полный знаменатель.", ""]
    lines += table(["Конфигурация/задача", "Полная сумма server input+output", "Server input", "Server output", "Оценка input", "Оценка output", "Метод оценки"], tokens)
    lines += ["Счётчики — известные подытоги с покрытием; при неполном измерении скрытые попытки неизвестны.", ""]
    lines += table(["Конфигурация/задача", "Запросы модели", "Решения tool", "Исполнения tool", "MCP transport", "Повторы решений tool", "Полное измерение/начаты"], counts)
    lines += ["Время в мс: медиана [min, max] и покрытие. duration включает ожидание человека и проверку oracle.", ""]
    lines += table(["Конфигурация/задача", "Успехи duration", "Неудачи duration", "Неудачи сумма duration", "Первый видимый ответ"], times)
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--output", type=Path, help="Новый Markdown-файл; иначе stdout")
    args = parser.parse_args()
    try:
        result = report(read_results(args.baseline), read_results(args.candidate))
        if args.output:
            with args.output.open("x", encoding="utf-8", newline="\n") as stream:
                stream.write(result)
        else:
            sys.stdout.reconfigure(encoding="utf-8")
            print(result)
    except (ValueError, OSError) as error:
        # Не печатать содержимое ошибочного JSON или произвольные значения полей.
        message = "Ошибка чтения/записи файла" if isinstance(error, (OSError, json.JSONDecodeError)) else str(error)
        parser.exit(2, message + "\n")


if __name__ == "__main__":
    main()
