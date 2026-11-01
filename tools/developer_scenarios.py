"""Подготовка и проверка фиксированных исходников; Python 3.10+, только стандартная библиотека."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil

FIXTURES = (Path(__file__).resolve().parents[1] / "tests" /
            "io.github.hansglorias.edtai.core.tests" / "fixtures" / "developer-scenarios")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def normalized(path):
    return path.read_text(encoding="utf-8-sig").replace("\r\n", "\n").replace("\r", "\n")


def hashes(root):
    result = {}
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if ".git" in relative.parts:
            continue
        if path.is_symlink():
            raise ValueError(f"Символическая ссылка в проверяемом дереве: {relative}")
        if path.is_file():
            result[relative.as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def source_hash(path):
    return hashlib.sha256(normalized(path).encode("utf-8")).hexdigest()


def source_format(path):
    raw = path.read_bytes()
    return {"bom": raw.startswith(b"\xef\xbb\xbf"),
            "delimiters": sorted(set(re.findall(r"\r\n|\r|\n", raw.decode("utf-8-sig"))))}


def snapshot_data(root):
    sources = [path for path in root.rglob("*.bsl") if ".git" not in path.relative_to(root).parts]
    return {"files": hashes(root),
            "sources": {path.relative_to(root).as_posix(): source_hash(path) for path in sources},
            "formats": {path.relative_to(root).as_posix(): source_format(path) for path in sources}}


def scenario(identifier):
    return next(item for item in read_json(FIXTURES / "scenarios.json")["scenarios"]
                if item["id"] == identifier)


def copy_state(identifier, root):
    # Не затирать текущую работу: состояние накладывается только на базовые модули.
    for source in (FIXTURES / "project").rglob("*.bsl"):
        target = root / source.relative_to(FIXTURES / "project")
        if normalized(source) != normalized(target):
            raise ValueError(f"Модуль уже изменён: {target}")
    state = {"S11": ("ПроверкаЗаказов", "S11-review.bsl"),
             "S14": ("БуферЗаказов", "S14-done.bsl")}.get(identifier)
    if state:
        target = root / "src" / "CommonModules" / state[0] / "Module.bsl"
        # Сохраняем кодировку и разделитель исходного файла.
        raw = target.read_bytes()
        text = normalized(FIXTURES / "states" / state[1])
        if b"\r\n" in raw:
            text = text.replace("\n", "\r\n")
        target.write_bytes((b"\xef\xbb\xbf" if raw.startswith(b"\xef\xbb\xbf") else b"") +
                           text.encode("utf-8"))
    print("Состояние на диске готово. Для S07 отдельно загрузить несохранённый буфер из карточки.")


def check(identifier, root, before, buffer, stopped):
    card = scenario(identifier)
    snapshot = read_json(before)
    expected_hashes = dict(snapshot["files"])
    actual_hashes = hashes(root)
    errors = []
    initial = {path.relative_to(FIXTURES / "project").as_posix(): source_hash(path)
               for path in (FIXTURES / "project").rglob("*.bsl")}
    overlay = {"S11": ("ПроверкаЗаказов", "S11-review.bsl"),
               "S14": ("БуферЗаказов", "S14-done.bsl")}.get(identifier)
    if overlay:
        initial[f"src/CommonModules/{overlay[0]}/Module.bsl"] = source_hash(FIXTURES / "states" / overlay[1])
    if initial != snapshot["sources"]:
        errors.append("Начальный снимок не соответствует фиксированным исходникам сценария")
    changes = [] if stopped else card["allowed_changes"]
    for change in changes:
        if change["medium"] != "disk":
            continue
        relative = change["path"]
        if relative not in expected_hashes:
            errors.append(f"Нет исходного файла в снимке: {relative}")
            continue
        current = root / relative
        if not current.is_file() or normalized(current) != normalized(FIXTURES / change["expected"]):
            errors.append(f"Текст отличается от эталона: {relative}")
        # Нормализация строк не должна скрывать изменение BOM и разделителей.
        if current.is_file():
            if source_format(current) != snapshot["formats"][relative]:
                errors.append(f"Изменены BOM или разделители строк: {relative}")
            expected_hashes[relative] = actual_hashes[relative]
    buffer_changes = [item for item in changes if item["medium"] == "buffer"]
    if buffer_changes:
        if buffer is None:
            errors.append("Нужен экспорт несохранённого буфера через --buffer вне каталога проекта")
        elif normalized(buffer) != normalized(FIXTURES / buffer_changes[0]["expected"]):
            errors.append("Несохранённый буфер отличается от эталона")
    for relative in sorted(set(expected_hashes) | set(actual_hashes)):
        if expected_hashes.get(relative) != actual_hashes.get(relative):
            errors.append(f"Файл создан, удалён или изменён вне разрешённой правки: {relative}")
    if errors:
        for error in errors:
            print(error)
        return 1
    print("Файлы совпадают с эталоном. Семантику, трассу и состояние редактора оценить по карточке.")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare")
    prepare.add_argument("destination", type=Path)
    show = commands.add_parser("show")
    show.add_argument("scenario", choices=[f"S{i:02}" for i in range(1, 16)])
    state = commands.add_parser("state")
    state.add_argument("scenario", choices=[f"S{i:02}" for i in range(1, 16)])
    state.add_argument("project", type=Path)
    snapshot = commands.add_parser("snapshot")
    snapshot.add_argument("project", type=Path)
    snapshot.add_argument("output", type=Path)
    verify = commands.add_parser("check")
    verify.add_argument("scenario", choices=[f"S{i:02}" for i in range(1, 16)])
    verify.add_argument("project", type=Path)
    verify.add_argument("before", type=Path)
    verify.add_argument("--buffer", type=Path)
    verify.add_argument("--stopped", action="store_true")
    args = parser.parse_args()
    if args.command == "prepare":
        shutil.copytree(FIXTURES / "project", args.destination)
        print("Созданы базовые исходники. Метаданные EDT подготовить по docs/DEVELOPER_SCENARIOS.md.")
    elif args.command == "show":
        print(json.dumps(scenario(args.scenario), ensure_ascii=False, indent=2))
    elif args.command == "state":
        copy_state(args.scenario, args.project)
    elif args.command == "snapshot":
        if args.output.resolve().is_relative_to(args.project.resolve()):
            raise ValueError("Снимок нужно сохранять вне проверяемого проекта")
        # Не перезаписывать свидетельство начала другого прогона.
        with args.output.open("x", encoding="utf-8") as stream:
            json.dump(snapshot_data(args.project), stream, ensure_ascii=False, indent=2)
    else:
        if args.stopped and args.scenario != "S15":
            raise ValueError("--stopped предназначен только для S15")
        return check(args.scenario, args.project, args.before, args.buffer, args.stopped)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, StopIteration) as error:
        raise SystemExit(f"Ошибка подготовки или проверки: {error}")
