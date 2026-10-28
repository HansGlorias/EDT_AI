package io.github.hansglorias.edtai.core.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.github.hansglorias.edtai.core.context.BslMethods;
import io.github.hansglorias.edtai.core.context.BslModulePath;

/**
 * Сведения о среде для системного промпта: проект и конфигурация, открытый
 * модуль и метод под курсором. Собираются перед каждым ответом, поэтому
 * разобранный {@code Configuration.mdo} запоминается до его изменения.
 */
public final class ProjectBrief {

    /** Правила для модели, если ей даны инструменты. */
    public static final String TOOL_RULES = """
            Правила работы с инструментами:
            - Прежде чем отвечать о коде или метаданных этого проекта, прочитай их инструментами (read_module, search_code,\
             list_objects). Не выдумывай имена объектов, модулей и методов.
            - Ссылайся на код так: Модуль:строка.
            - Не вызывай инструмент повторно с теми же аргументами: результат у тебя уже есть.
            - Если инструменты не дали нужного — так и скажи.
            - Меняй код (edit_module) только когда пользователь просит изменить, исправить или написать код в модуле;             на вопросы отвечай без правок. После правок кратко перечисли, что и где изменил.""";

    private static final Map<Path, Cached> CONFIGURATIONS = new ConcurrentHashMap<>();

    private record Cached(long modified, ConfigurationInfo info) {
    }

    private ProjectBrief() {
    }

    /** Пустая строка, если проектов нет. */
    public static String describe(WorkspaceFiles files) {
        Optional<String> project = ProjectTools.defaultProject(files);
        if (project.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("Среда: 1C:EDT. Проект в IDE: " + project.get() + ".");
        Optional<Path> root = files.root(project.get());
        root.flatMap(ProjectBrief::configuration).ifPresent(info -> lines.add(String.join("; ", info.describe()) + "."));
        files.activeFile().ifPresent(path -> lines.add(activeModule(files, project.get(), root, path)));
        return String.join("\n", lines);
    }

    private static String activeModule(WorkspaceFiles files, String project, Optional<Path> root, String path) {
        String text = "Открыт в редакторе: " + BslModulePath.metadataName(path).map(name -> name + " (" + path + ")").orElse(path);
        int line = files.activeLine();
        if (line > 0 && path.endsWith(".bsl")) {
            Optional<String> content = files.unsavedText(project, path).or(() -> root.flatMap(dir -> read(dir.resolve(path))));
            Optional<BslMethods.Method> method = content.flatMap(code -> BslMethods.enclosing(code, line));
            text += method.map(found -> ", курсор в методе " + found.name() + ", строка " + line).orElse(", курсор на строке " + line);
        }
        return text + ".";
    }

    private static Optional<ConfigurationInfo> configuration(Path root) {
        Path mdo = root.resolve("src/Configuration/Configuration.mdo");
        try {
            long modified = Files.getLastModifiedTime(mdo).toMillis();
            Cached cached = CONFIGURATIONS.get(mdo);
            if (cached == null || cached.modified() != modified) {
                cached = new Cached(modified, ConfigurationInfo.parse(read(mdo).orElse("")));
                CONFIGURATIONS.put(mdo, cached);
            }
            return Optional.of(cached.info());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> read(Path file) {
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return Optional.of(text.startsWith("﻿") ? text.substring(1) : text);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
