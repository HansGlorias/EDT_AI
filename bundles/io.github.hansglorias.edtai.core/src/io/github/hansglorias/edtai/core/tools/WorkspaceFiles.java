package io.github.hansglorias.edtai.core.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Проекты рабочей области для инструментов: без API Eclipse, чтобы ядро
 * проверялось на обычных каталогах. В EDT реализуется через {@code IWorkspace}.
 */
public interface WorkspaceFiles {

    /** Имена открытых проектов. */
    List<String> projects();

    /** Каталог проекта на диске. */
    Optional<Path> root(String project);

    /** Проект, о котором вероятнее спрашивают: например, проект открытого редактора. */
    default Optional<String> preferredProject() {
        return Optional.empty();
    }

    /** Файл активного редактора в {@link #preferredProject()}: путь относительно проекта, разделитель {@code /}. */
    default Optional<String> activeFile() {
        return Optional.empty();
    }

    /** Строка курсора в активном редакторе, с 1; 0 — неизвестно. */
    default int activeLine() {
        return 0;
    }

    /**
     * Текст файла с несохранёнными правками, если он открыт в редакторе.
     *
     * @param path путь относительно проекта, разделитель {@code /}
     */
    default Optional<String> unsavedText(String project, String path) {
        return Optional.empty();
    }

    /**
     * Итог правки файла.
     *
     * @param text        текст файла после правки
     * @param replacement что заменено
     * @param saved       файл сохранён; {@code false} — правка в редакторе, где были несохранённые правки пользователя
     */
    record Edit(String text, TextChange.Replacement replacement, boolean saved) {
    }

    /**
     * Правит файл. По умолчанию — прямо на диске; в EDT — через документ
     * редактора, чтобы правку было видно и можно было отменить.
     *
     * @param path путь относительно проекта, разделитель {@code /}
     */
    default Edit edit(String project, String path, TextChange change) throws ToolException {
        Path file = root(project).orElseThrow(() -> new ToolException("Каталог проекта «" + project + "» не найден")).resolve(path);
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String bom = content.startsWith("\uFEFF") ? "\uFEFF" : "";
            content = content.substring(bom.length());
            TextChange.Replacement replacement = change.compute(content);
            String result = content.substring(0, replacement.offset()) + replacement.text()
                    + content.substring(replacement.offset() + replacement.length());
            Files.writeString(file, bom + result, StandardCharsets.UTF_8);
            return new Edit(result, replacement, true);
        } catch (IOException e) {
            throw new ToolException("Не удалось изменить " + path + ": " + e.getMessage());
        }
    }

    /** Все файлы проекта с несохранёнными правками: путь относительно проекта → текст. */
    default Map<String, String> unsavedTexts(String project) {
        return Map.of();
    }
}
