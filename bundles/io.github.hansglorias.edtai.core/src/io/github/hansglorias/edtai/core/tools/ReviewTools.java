package io.github.hansglorias.edtai.core.tools;

import static io.github.hansglorias.edtai.core.tools.ProjectTools.PROJECT;
import static io.github.hansglorias.edtai.core.tools.ProjectTools.schema;
import static io.github.hansglorias.edtai.core.tools.ProjectTools.text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
                    "line", Json.object("type", "integer", "description", "Строка в модуле, с 1"),
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
            String project = ProjectTools.project(files, input);
            Path root = files.root(project).orElseThrow(() -> new ToolException("Каталог проекта «" + project + "» не найден"));
            List<Finding> findings = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (Object item : Json.asList(input.get("findings"))) {
                String module = Json.stringAt(item, "module");
                String title = Json.stringAt(item, "title");
                if (module == null || title == null || title.isBlank()) {
                    skipped.add(String.valueOf(module) + ": нет модуля или сути");
                    continue;
                }
                String path;
                try {
                    path = ProjectTools.modulePath(project, module);
                } catch (ToolException e) {
                    skipped.add(module + ": " + e.getMessage());
                    continue;
                }
                if (!Files.isRegularFile(root.resolve(path).normalize())) {
                    skipped.add(module + ": модуля нет в проекте");
                    continue;
                }
                int line = Json.at(item, "line") instanceof Number number ? number.intValue() : 1;
                findings.add(new Finding(project, path, BslModulePath.metadataName(path).orElse(path), line,
                        Finding.Severity.of(Json.stringAt(item, "severity")), title, text(asMap(item), "fix")));
            }
            List<Finding> shown = Finding.sorted(findings);
            markers.accept(shown);
            String summary = Finding.summary(shown);
            String text = "Принято находок: " + shown.size() + " (" + summary + "). Пользователь видит их списком со ссылками и"
                    + " в редакторе — не перечисляй их снова, подведи итог в 1–3 предложениях."
                    + (skipped.isEmpty() ? "" : " Не приняты (исправь и передай заново, если важно): " + String.join("; ", skipped));
            return new Result(text, summary, Attachment.review(Finding.toJson(shown)));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> asMap(Object item) {
            return item instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }
    }
}
