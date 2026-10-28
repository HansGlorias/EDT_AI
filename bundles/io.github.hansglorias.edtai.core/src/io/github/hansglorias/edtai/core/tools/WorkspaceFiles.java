package io.github.hansglorias.edtai.core.tools;

import java.nio.file.Path;
import java.util.List;
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
}
