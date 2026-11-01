package io.github.hansglorias.edtai.core.tools;

import static io.github.hansglorias.edtai.core.tools.ProjectTools.PROJECT;
import static io.github.hansglorias.edtai.core.tools.ProjectTools.schema;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.context.BslModulePath;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.review.Finding;

/**
 * Итог ревью: модель передаёт находки списком ({@code report_review}), а не
 * только текстом — плагин показывает их карточкой в ленте и маркерами в
 * редакторе. Проект не меняет: доступен во всех режимах.
 */
public final class ReviewTools {

    private ReviewTools() {
    }

    /** @param markers что делать с принятыми находками: поставить маркеры */
    public static List<Tool> create(WorkspaceFiles files, Consumer<List<Finding>> markers) {
        return List.of(new Report(files, markers));
    }

    private record Report(WorkspaceFiles files, Consumer<List<Finding>> markers) implements Tool {

        @Override
        public String name() {
            return "report_review";
        }

        @Override
        public String description() {
            return "Передаёт находки ревью кода: плагин покажет их пользователю списком со ссылками на строки и отметит в"
                    + " редакторе. Вызывай один раз в конце ревью со всеми находками (и когда находок нет — с пустым списком),"
                    + " потом коротко подведи итог текстом, не повторяя находки.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> finding = Json.object("type", "object", "properties", Json.object(
                    "module", Json.object("type", "string", "description", "Модуль в нотации 1С (ОбщийМодуль.Цены,"
                            + " Справочник.Номенклатура.МодульОбъекта) или путь в проекте"),
                    "line", Json.object("type", "integer", "minimum", 1, "maximum", Integer.MAX_VALUE,
                            "description", "Строка в модуле, с 1"),
                    "severity", Json.object("type", "string", "enum", List.of("error", "warning", "info"), "description",
                            "error — ошибка (неверный результат, падение, потеря данных), warning — нарушение стандарта или"
                                    + " риск, info — совет по стилю"),
                    "title", Json.object("type", "string", "description", "Что не так — одной фразой"),
                    "fix", Json.object("type", "string", "description", "Как исправить — коротко")),
                    "required", List.of("module", "line", "severity", "title"));
            return schema(Json.object("findings", Json.object("type", "array", "items", finding), "project", PROJECT),
                    List.of("findings"));
        }

        @Override
        public String summary(Map<String, Object> input) {
            return "Итог ревью";
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            // Сначала проверяем форму всего списка: ошибка даже в последнем
            // элементе не должна публиковать неполное ревью.
            List<Draft> drafts = parse(input);
            String project = ProjectTools.project(files, input);
            Path root = files.root(project).orElseThrow(() -> new ToolException("Каталог проекта «" + project + "» не найден"))
                    .toAbsolutePath().normalize();
            List<Finding> findings = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (Draft draft : drafts) {
                try {
                    String path = ProjectTools.modulePath(project, draft.module());
                    Path file = root.resolve(path).normalize();
                    if (!file.startsWith(root) || !path.toLowerCase(Locale.ROOT).endsWith(".bsl")) {
                        throw new ToolException("нужен модуль .bsl внутри проекта");
                    }
                    if (!Files.isRegularFile(file)) {
                        throw new ToolException("модуля нет в проекте");
                    }
                    path = root.relativize(file).toString().replace('\\', '/');
                    int total = lineCount(currentText(files, project, path, file));
                    if (draft.line() > total) {
                        throw new ToolException("строки " + draft.line() + " нет, в модуле строк: " + total);
                    }
                    findings.add(new Finding(project, path, BslModulePath.metadataName(path).orElse(path), draft.line(),
                            draft.severity(), draft.title(), draft.fix()));
                } catch (ToolException e) {
                    skipped.add(draft.module() + ": " + e.getMessage());
                } catch (InvalidPathException e) {
                    skipped.add(draft.module() + ": некорректный путь модуля");
                } catch (IOException e) {
                    skipped.add(draft.module() + ": не удалось прочитать модуль");
                }
            }
            if (!drafts.isEmpty() && findings.isEmpty()) {
                throw new ToolException("Не принято ни одной находки ревью: " + String.join("; ", skipped));
            }
            List<Finding> shown = Finding.sorted(findings);
            markers.accept(shown);
            String summary = Finding.summary(shown);
            String text = "Принято находок: " + shown.size() + " (" + summary + "). Пользователь видит их списком со ссылками и"
                    + " в редакторе — не перечисляй их снова, подведи итог в 1–3 предложениях."
                    + (skipped.isEmpty() ? "" : " Не приняты (исправь и передай заново, если важно): " + String.join("; ", skipped));
            return new Result(text, summary, Attachment.review(Finding.toJson(shown)));
        }

        private static List<Draft> parse(Map<String, Object> input) throws ToolException {
            ToolArguments args = new ToolArguments(input);
            if (!input.containsKey("findings")) {
                throw args.error("findings", "обязательно");
            }
            if (!(input.get("findings") instanceof List<?> items)) {
                throw args.error("findings", "должно быть массивом объектов");
            }
            args.optionalString("project");
            List<Draft> drafts = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                String field = "findings[" + i + "]";
                if (!(items.get(i) instanceof Map<?, ?> item)) {
                    throw args.error(field, "должно быть объектом");
                }
                ToolArguments finding = new ToolArguments(item, field + ".");
                String module = finding.nonBlankString("module");
                int line = finding.positiveInteger("line");
                String severity = finding.string("severity");
                if (!List.of("error", "warning", "info").contains(severity)) {
                    throw finding.error("severity", "должно быть одним из: error, warning, info");
                }
                drafts.add(new Draft(module, line, Finding.Severity.of(severity), finding.nonBlankString("title"),
                        finding.optionalString("fix")));
            }
            return drafts;
        }
    }

    private record Draft(String module, int line, Finding.Severity severity, String title, String fix) {
    }

    private static String currentText(WorkspaceFiles files, String project, String path, Path file) throws IOException {
        var unsaved = files.unsavedText(project, path);
        return unsaved.isPresent() ? unsaved.get() : Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Как в read_module: завершающий перенос не добавляет пустую строку. */
    private static int lineCount(String text) {
        String[] lines = text.split("\r\n|\r|\n", -1);
        return lines.length > 1 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
    }
}
