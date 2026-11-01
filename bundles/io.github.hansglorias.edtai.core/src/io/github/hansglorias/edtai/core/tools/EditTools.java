package io.github.hansglorias.edtai.core.tools;

import static io.github.hansglorias.edtai.core.tools.ProjectTools.PROJECT;
import static io.github.hansglorias.edtai.core.tools.ProjectTools.schema;
import static io.github.hansglorias.edtai.core.tools.ProjectTools.string;
import static io.github.hansglorias.edtai.core.tools.ProjectTools.text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import io.github.hansglorias.edtai.core.context.BslModulePath;

/**
 * Правка модулей моделью — без подтверждения каждой правки: что изменено,
 * видно в диффе (git) и в редакторе, отменяется Ctrl+Z. Только модули
 * {@code .bsl}: метаданные мимо EDT не трогаем.
 */
public final class EditTools {

    /**
     * Правка файла моделью: файл до и после — для диффа и отката.
     *
     * @param path        путь относительно проекта
     * @param before      текст до правки; {@code null} — файла не было
     * @param after       текст после; {@code null} — файл удалён
     * @param sinceCommit правка сделана мимо плагина (инструментом MCP): «до» — последний коммит, а не состояние
     *                    перед ответом
     */
    public record Change(String project, String path, String before, String after, boolean sinceCommit) {

        public Change(String project, String path, String before, String after) {
            this(project, path, before, after, false);
        }
    }

    private final WorkspaceFiles files;
    private final Consumer<Change> changes;

    private EditTools(WorkspaceFiles files, Consumer<Change> changes) {
        this.files = files;
        this.changes = changes;
    }

    public static List<Tool> create(WorkspaceFiles files) {
        return create(files, change -> { });
    }

    /** @param changes получает каждую применённую правку, из потока ответа */
    public static List<Tool> create(WorkspaceFiles files, Consumer<Change> changes) {
        return List.of(new EditTools(files, changes).new EditModule());
    }

    private final class EditModule implements Tool {

        @Override
        public boolean modifies() {
            return true;
        }
        @Override
        public String name() {
            return "edit_module";
        }

        @Override
        public String description() {
            return "Меняет модуль BSL: заменяет фрагмент old_text на new_text. old_text — точный текст из модуля (с"
                    + " отступами, без номеров строк из read_module), встречается в модуле ровно один раз; добавь"
                    + " соседние строки, если фрагмент неуникален. Сначала прочитай модуль. Для нескольких мест —"
                    + " несколько вызовов. Метаданные (формы, реквизиты) так не меняются — только код модулей.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema(Map.of("module", string("Модуль: имя в нотации 1С или путь файла в проекте"),
                    "old_text", string("Точный заменяемый фрагмент модуля"),
                    "new_text", string("Новый текст фрагмента; пусто — удалить фрагмент"), "project", PROJECT),
                    List.of("module", "old_text", "new_text"));
        }

        @Override
        public String summary(Map<String, Object> input) {
            return "Правит модуль " + text(input, "module");
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            ToolArguments args = new ToolArguments(input);
            String module = args.nonBlankString("module");
            // Текст — как прислала модель, без strip: отступы и переводы строк важны.
            String oldText = args.string("old_text");
            if (oldText.isEmpty()) {
                throw args.error("old_text", "не должно быть пустым: укажи точный фрагмент модуля");
            }
            String newText = args.string("new_text");
            args.optionalString("project");
            String project = ProjectTools.project(files, input);
            Path root = files.root(project).orElseThrow(() -> new ToolException("Каталог проекта «" + project + "» не найден"));
            String path = ProjectTools.modulePath(project, module);
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root) || !path.toLowerCase(Locale.ROOT).endsWith(".bsl")) {
                throw new ToolException("Менять можно только модули .bsl проекта: " + path);
            }
            if (!Files.isRegularFile(file)) {
                throw new ToolException("Модуля " + path + " нет. Новые модули создаются в EDT вместе с объектом метаданных.");
            }
            WorkspaceFiles.Edit edit = files.edit(project, path, TextChange.replace(oldText, newText));
            changes.accept(new Change(project, path, edit.before(), edit.text()));
            int first = line(edit.text(), edit.replacement().offset());
            int last = line(edit.text(), edit.replacement().offset() + Math.max(0, edit.replacement().text().length() - 1));
            String name = BslModulePath.metadataName(path).orElse(path);
            String lines = edit.replacement().text().isEmpty() ? "удалено у строки " + first
                    : first == last ? "строка " + first : "строки " + first + "–" + last;
            String state = edit.saved() ? "сохранён" : "изменён в редакторе, не сохранён: там были несохранённые правки пользователя";
            return new Result("Изменено: " + name + ", " + lines + "; файл " + state + ".", lines + (edit.saved() ? "" : ", не сохранён"));
        }
    }

    /** Номер строки (с 1) символа {@code offset}. */
    private static int line(String text, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
