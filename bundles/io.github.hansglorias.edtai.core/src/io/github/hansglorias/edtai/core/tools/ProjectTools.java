package io.github.hansglorias.edtai.core.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

import io.github.hansglorias.edtai.core.context.BslModulePath;
import io.github.hansglorias.edtai.core.json.Json;

/**
 * Встроенные инструменты только на чтение: сведения о проекте, список
 * объектов метаданных, чтение модуля, поиск по коду. Работают со структурой
 * каталогов проекта EDT ({@code src/<Тип>/<Имя>/…}), без API EDT.
 */
public final class ProjectTools {

    static final int DEFAULT_SEARCH_RESULTS = 50;
    static final int MAX_SEARCH_RESULTS = 200;
    static final int MAX_LISTED_OBJECTS = 500;
    private static final int MAX_SEARCH_LINE = 200;

    private final WorkspaceFiles files;
    /** Сколько символов отдавать модели за один вызов: лимит контекста из настроек. */
    private final IntSupplier limit;

    private ProjectTools(WorkspaceFiles files, IntSupplier limit) {
        this.files = files;
        this.limit = limit;
    }

    public static List<Tool> create(WorkspaceFiles files, IntSupplier limit) {
        ProjectTools tools = new ProjectTools(files, limit);
        return List.of(tools.new ProjectInfo(), tools.new ListObjects(), tools.new ReadModule(), tools.new SearchCode());
    }

    // ---- инструменты ----

    private final class ProjectInfo implements Tool {
        @Override
        public String name() {
            return "project_info";
        }

        @Override
        public String description() {
            return "Сведения о проекте 1C:EDT, открытом в IDE: имя, синоним и версия конфигурации, режим совместимости,"
                    + " расширение ли это, сколько объектов метаданных каждого типа, другие проекты рабочей области."
                    + " Вызывай первым, если вопрос о проекте или конфигурации в целом.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema(Json.object("project", PROJECT), List.of());
        }

        @Override
        public String summary(Map<String, Object> input) {
            return "Смотрит сведения о проекте";
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            String project = project(input);
            Path root = root(project);
            List<String> lines = new ArrayList<>();
            lines.add("Проект: " + project);
            List<String> all = files.projects();
            if (all.size() > 1) {
                lines.add("Проекты рабочей области: " + String.join(", ", all));
            }
            Path mdo = root.resolve("src/Configuration/Configuration.mdo");
            String outcome = project;
            if (Files.isRegularFile(mdo)) {
                try {
                    ConfigurationInfo info = ConfigurationInfo.parse(read(mdo));
                    lines.addAll(info.describe());
                    if (!info.name().isEmpty()) {
                        outcome = info.name() + (info.version().isEmpty() ? "" : " " + info.version());
                    }
                } catch (IOException e) {
                    lines.add("Configuration.mdo не прочитан: " + e.getMessage());
                }
            } else {
                lines.add("Файла src/Configuration/Configuration.mdo нет: возможно, это не проект конфигурации EDT.");
            }
            lines.add("");
            lines.addAll(counts(root));
            return new Result(String.join("\n", lines), outcome);
        }
    }

    private final class ListObjects implements Tool {
        @Override
        public String name() {
            return "list_objects";
        }

        @Override
        public String description() {
            return "Объекты метаданных проекта. Без type — сколько объектов каждого типа. С type (например,"
                    + " «Справочник», «ОбщийМодуль», «Документ», можно по-английски: Catalog) — имена объектов этого типа,"
                    + " filter — часть имени.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema(Json.object("type", string("Тип объектов: Справочник, Документ, ОбщийМодуль, РегистрСведений…"),
                    "filter", string("Часть имени объекта, без учёта регистра"), "project", PROJECT), List.of());
        }

        @Override
        public String summary(Map<String, Object> input) {
            String type = text(input, "type");
            String filter = text(input, "filter");
            return type.isEmpty() ? "Смотрит состав метаданных"
                    : "Смотрит список «" + type + "»" + (filter.isEmpty() ? "" : " по «" + filter + "»");
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            String project = project(input);
            Path root = root(project);
            String type = text(input, "type");
            if (type.isEmpty()) {
                List<String> counts = counts(root);
                return new Result(String.join("\n", counts), counts.size() - 1 + " типов");
            }
            String directory = BslModulePath.typeDirectory(type).orElseThrow(() -> new ToolException(
                    "Неизвестный тип «" + type + "». Типы: " + String.join(", ", typeNames())));
            String filter = normalize(text(input, "filter"));
            List<String> names = objects(root, directory).stream().filter(name -> normalize(name).contains(filter)).toList();
            String typeName = BslModulePath.typeName(directory).orElse(directory);
            StringBuilder text = new StringBuilder(typeName + ": " + names.size()
                    + (filter.isEmpty() ? "" : " с «" + text(input, "filter") + "» в имени"));
            names.stream().limit(MAX_LISTED_OBJECTS).forEach(name -> text.append('\n').append(name));
            if (names.size() > MAX_LISTED_OBJECTS) {
                text.append("\n(показаны первые ").append(MAX_LISTED_OBJECTS).append(": уточните filter)");
            }
            return new Result(text.toString(), String.valueOf(names.size()));
        }
    }

    private final class ReadModule implements Tool {
        @Override
        public String name() {
            return "read_module";
        }

        @Override
        public String description() {
            return "Текст модуля BSL с номерами строк. module — имя в нотации 1С («ОбщийМодуль.ОбщегоНазначения»,"
                    + " «Справочник.Номенклатура.МодульОбъекта», «Справочник.Номенклатура.МодульМенеджера»,"
                    + " «Документ.Заказ.Форма.ФормаДокумента») или путь в проекте (src/CommonModules/Х/Module.bsl)."
                    + " Большой модуль отдаётся частями: first_line и last_line — какие строки прочитать.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema(Json.object("module", string("Имя модуля в нотации 1С или путь файла в проекте"),
                    "first_line", integer("Первая строка, с 1"), "last_line", integer("Последняя строка включительно"),
                    "project", PROJECT), List.of("module"));
        }

        @Override
        public String summary(Map<String, Object> input) {
            return "Читает модуль " + text(input, "module");
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            String project = project(input);
            Path root = root(project);
            String path = modulePath(project, text(input, "module"));
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root)) {
                throw new ToolException("Путь вне проекта: " + path);
            }
            if (!Files.isRegularFile(file)) {
                throw new ToolException(missingModule(root, path));
            }
            String content = files.unsavedText(project, path).orElseGet(() -> readUnchecked(file));
            List<String> lines = List.of(content.split("\r\n|\r|\n", -1));
            int total = lines.size() > 1 && lines.get(lines.size() - 1).isEmpty() ? lines.size() - 1 : lines.size();
            int first = Math.max(1, number(input, "first_line", 1));
            int last = Math.min(total, number(input, "last_line", total));
            if (first > total) {
                throw new ToolException("В модуле строк: " + total + ", строки " + first + " нет");
            }
            last = Math.max(first, last);
            String name = BslModulePath.metadataName(path).orElse(path);
            int width = String.valueOf(last).length();
            StringBuilder text = new StringBuilder();
            int shown = first - 1;
            int budget = limit.getAsInt();
            for (int line = first; line <= last; line++) {
                String numbered = String.format("%" + width + "d  %s", line, lines.get(line - 1));
                if (text.length() + numbered.length() + 1 > budget && line > first) {
                    break;
                }
                text.append(numbered).append('\n');
                shown = line;
            }
            String range = first == 1 && shown == total ? "весь модуль, строки 1–" + total : "строки " + first + "–" + shown + " из " + total;
            String header = name + " (проект " + project + ", " + path + "), " + range + ":\n";
            if (shown < last) {
                text.append("(дальше не поместилось в лимит: продолжение — first_line=").append(shown + 1).append(")\n");
            }
            return new Result(header + text, "строки " + first + "–" + shown + " из " + total);
        }
    }

    private final class SearchCode implements Tool {
        @Override
        public String name() {
            return "search_code";
        }

        @Override
        public String description() {
            return "Поиск строки в модулях BSL проекта без учёта регистра (не регулярное выражение). Возвращает модуль,"
                    + " номер строки и саму строку. type ограничивает поиск типом объектов (Справочник, ОбщийМодуль…).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema(Json.object("query", string("Что искать: имя процедуры, переменной, часть текста"),
                    "type", string("Искать только в объектах этого типа"),
                    "max_results", integer("Сколько совпадений вернуть, по умолчанию " + DEFAULT_SEARCH_RESULTS),
                    "project", PROJECT), List.of("query"));
        }

        @Override
        public String summary(Map<String, Object> input) {
            String type = text(input, "type");
            return "Ищет «" + text(input, "query") + "»" + (type.isEmpty() ? "" : " в «" + type + "»");
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            String query = text(input, "query");
            if (query.isBlank()) {
                throw new ToolException("Не задано, что искать (query)");
            }
            String project = project(input);
            Path root = root(project);
            Path where = root.resolve("src");
            String type = text(input, "type");
            if (!type.isEmpty()) {
                where = where.resolve(BslModulePath.typeDirectory(type).orElseThrow(() -> new ToolException(
                        "Неизвестный тип «" + type + "». Типы: " + String.join(", ", typeNames()))));
            }
            int max = Math.max(1, Math.min(MAX_SEARCH_RESULTS, number(input, "max_results", DEFAULT_SEARCH_RESULTS)));
            String needle = normalize(query);
            List<String> found = new ArrayList<>();
            int total = 0;
            int modules = 0;
            for (Path file : bslFiles(where)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new ToolException("Поиск прерван");
                }
                String path = root.relativize(file).toString().replace('\\', '/');
                List<String> lines = List.of(readUnchecked(file).split("\r\n|\r|\n", -1));
                boolean matched = false;
                for (int i = 0; i < lines.size(); i++) {
                    if (normalize(lines.get(i)).contains(needle)) {
                        total++;
                        matched = true;
                        if (found.size() < max) {
                            String line = lines.get(i).strip();
                            if (line.length() > MAX_SEARCH_LINE) {
                                line = line.substring(0, MAX_SEARCH_LINE) + "…";
                            }
                            found.add(BslModulePath.metadataName(path).orElse(path) + ":" + (i + 1) + ": " + line);
                        }
                    }
                }
                if (matched) {
                    modules++;
                }
            }
            StringBuilder text = new StringBuilder("«" + query + "»: совпадений — " + total + ", модулей — " + modules);
            if (total > found.size()) {
                text.append(", показаны первые ").append(found.size());
            }
            int budget = limit.getAsInt();
            for (String line : found) {
                if (text.length() + line.length() + 1 > budget) {
                    text.append("\n(дальше не поместилось в лимит: уточните запрос)");
                    break;
                }
                text.append('\n').append(line);
            }
            return new Result(text.toString(), "найдено " + total);
        }
    }

    // ---- общее ----

    private static final Map<String, Object> PROJECT = string("Имя проекта, если в рабочей области их несколько");

    private String project(Map<String, Object> input) throws ToolException {
        List<String> projects = files.projects();
        String asked = text(input, "project");
        if (!asked.isEmpty()) {
            if (!projects.contains(asked)) {
                throw new ToolException("Проекта «" + asked + "» нет. Проекты: " + String.join(", ", projects));
            }
            return asked;
        }
        Optional<String> preferred = files.preferredProject().filter(projects::contains);
        if (preferred.isPresent()) {
            return preferred.get();
        }
        for (String project : projects) {
            Optional<Path> root = files.root(project);
            if (root.isPresent() && Files.isRegularFile(root.get().resolve("src/Configuration/Configuration.mdo"))) {
                return project;
            }
        }
        if (projects.isEmpty()) {
            throw new ToolException("В рабочей области нет открытых проектов");
        }
        return projects.get(0);
    }

    private Path root(String project) throws ToolException {
        return files.root(project).orElseThrow(() -> new ToolException("Каталог проекта «" + project + "» не найден"));
    }

    /** Путь модуля в проекте по имени 1С или пути. */
    private static String modulePath(String project, String module) throws ToolException {
        String value = module.strip().replace('\\', '/');
        if (value.isEmpty()) {
            throw new ToolException("Не задан модуль (module)");
        }
        if (value.contains("/") || value.toLowerCase(Locale.ROOT).endsWith(".bsl")) {
            value = value.replaceAll("^/+", "");
            return value.startsWith(project + "/") ? value.substring(project.length() + 1) : value;
        }
        return BslModulePath.path(value).orElseThrow(() -> new ToolException("Не понял имя модуля «" + module
                + "». Примеры: ОбщийМодуль.ОбщегоНазначения, Справочник.Номенклатура.МодульОбъекта,"
                + " Документ.Заказ.Форма.ФормаДокумента"));
    }

    /** Модуля нет: подсказать, какие модули есть у объекта, или что объекта нет. */
    private static String missingModule(Path root, String path) {
        String[] parts = path.split("/");
        if (parts.length >= 3 && parts[0].equals("src")) {
            Path object = root.resolve(parts[0]).resolve(parts[1]).resolve(parts[2]);
            if (Files.isDirectory(object)) {
                List<String> modules = new ArrayList<>();
                for (Path file : bslFiles(object)) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    modules.add(BslModulePath.metadataName(relative).orElse(relative));
                }
                return "Модуля " + path + " нет." + (modules.isEmpty() ? " У объекта нет модулей."
                        : " У объекта есть: " + String.join(", ", modules));
            }
            String type = BslModulePath.typeName(parts[1]).orElse(parts[1]);
            return "Объекта " + type + "." + parts[2] + " в проекте нет. Список объектов — list_objects.";
        }
        return "Файла " + path + " в проекте нет";
    }

    /** «Справочник: 12» по каждому типу, где есть объекты. */
    private static List<String> counts(Path root) {
        List<String> lines = new ArrayList<>();
        lines.add("Объекты метаданных:");
        for (String directory : BslModulePath.typeDirectories()) {
            int count = objects(root, directory).size();
            if (count > 0) {
                lines.add("  " + BslModulePath.typeName(directory).orElse(directory) + ": " + count);
            }
        }
        if (lines.size() == 1) {
            lines.add("  нет (каталог src пуст или это не проект EDT)");
        }
        return lines;
    }

    private static List<String> objects(Path root, String directory) {
        Path dir = root.resolve("src").resolve(directory);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isDirectory).map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<Path> bslFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".bsl") && Files.isRegularFile(path)).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<String> typeNames() {
        return BslModulePath.typeDirectories().stream().map(directory -> BslModulePath.typeName(directory).orElse(directory)).toList();
    }

    private static String read(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    private static String readUnchecked(Path file) {
        try {
            return read(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static int number(Map<String, Object> input, String key, int fallback) {
        Object value = input.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        return required.isEmpty() ? Json.object("type", "object", "properties", properties)
                : Json.object("type", "object", "properties", properties, "required", required);
    }

    private static Map<String, Object> string(String description) {
        return Json.object("type", "string", "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return Json.object("type", "integer", "description", description);
    }
}
