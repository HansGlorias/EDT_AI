package io.github.hansglorias.edtai.core.diagnostics;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Локальный opt-in JSONL. Один владелец пути, синхронная ограниченная запись;
 * IOException/SecurityException выключают экземпляр без повторных попыток.
 */
public final class DiagnosticLog implements AutoCloseable {

    public static final int MAX_FILE_BYTES = 1024 * 1024;
    /** Вместе с активным файлом. */
    public static final int MAX_FILES = 3;

    public enum State { DISABLED, AVAILABLE, UNAVAILABLE, CLOSED }

    @FunctionalInterface
    interface OutputOpener {
        OutputStream open(Path path) throws IOException;
    }

    private final Path file;
    private final int maxBytes;
    private final int maxFiles;
    private final OutputOpener opener;
    private State state;
    private OutputStream output;
    private long size;

    /** По умолчанию выключен: не обращается к файловой системе. */
    public DiagnosticLog() {
        file = null;
        maxBytes = MAX_FILE_BYTES;
        maxFiles = MAX_FILES;
        opener = null;
        state = State.DISABLED;
    }

    /** Явное включение. Путь и соседние .1/.2 принадлежат только этому журналу. */
    public static DiagnosticLog open(Path file) {
        return new DiagnosticLog(file, MAX_FILE_BYTES, MAX_FILES,
                path -> Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS));
    }

    // Меньшие границы и контролируемые ошибки I/O для тестов; публичные лимиты фиксированы.
    DiagnosticLog(Path file, int maxBytes, int maxFiles, OutputOpener opener) {
        this.file = Objects.requireNonNull(file).toAbsolutePath();
        this.opener = Objects.requireNonNull(opener);
        if (maxBytes < 2048 || maxBytes > MAX_FILE_BYTES || maxFiles < 1 || maxFiles > MAX_FILES) {
            throw new IllegalArgumentException("Некорректные границы журнала");
        }
        this.maxBytes = maxBytes;
        this.maxFiles = maxFiles;
        state = State.AVAILABLE;
        try {
            Files.createDirectories(this.file.getParent());
            // Чужой тип файла или нарушенный прежними запусками лимит — не удалять молча.
            for (int i = 0; i < maxFiles; i++) {
                Path path = slot(i);
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                        && (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maxBytes)) {
                    throw new IOException("Журнал недоступен");
                }
            }
            // Новый запуск начинает целую строку даже после аварийно оборванной записи.
            rotate();
        } catch (IOException | SecurityException e) {
            unavailable();
        }
    }

    public synchronized State state() {
        return state;
    }

    /** Возвращает состояние после записи; выключенный/закрытый журнал ничего не пишет. */
    public synchronized State write(DiagnosticEvent event) {
        if (state != State.AVAILABLE) {
            return state;
        }
        byte[] line = (Objects.requireNonNull(event).json() + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            if (line.length > maxBytes) {
                throw new IOException("Событие превышает лимит журнала");
            }
            if (size + line.length > maxBytes) {
                rotate();
            }
            output.write(line);
            output.flush();
            size += line.length;
        } catch (IOException | SecurityException e) {
            unavailable();
        }
        return state;
    }

    private Path slot(int index) {
        return index == 0 ? file : file.resolveSibling(file.getFileName() + "." + index);
    }

    private void rotate() throws IOException {
        closeOutput();
        Files.deleteIfExists(slot(maxFiles - 1));
        for (int i = maxFiles - 2; i >= 0; i--) {
            if (Files.exists(slot(i), LinkOption.NOFOLLOW_LINKS)) {
                Files.move(slot(i), slot(i + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        output = opener.open(file);
        size = 0;
    }

    private void closeOutput() throws IOException {
        OutputStream previous = output;
        output = null;
        if (previous != null) {
            previous.close();
        }
    }

    private void unavailable() {
        state = State.UNAVAILABLE;
        try {
            closeOutput();
        } catch (IOException | SecurityException e) {
            // Не логируем исключение: оно может содержать путь или другие чувствительные данные.
        }
    }

    /** Идемпотентно; ошибка закрытия тоже видна как UNAVAILABLE. */
    @Override
    public synchronized void close() {
        if (state == State.CLOSED || state == State.UNAVAILABLE) {
            return;
        }
        try {
            closeOutput();
            state = State.CLOSED;
        } catch (IOException | SecurityException e) {
            unavailable();
        }
    }
}
