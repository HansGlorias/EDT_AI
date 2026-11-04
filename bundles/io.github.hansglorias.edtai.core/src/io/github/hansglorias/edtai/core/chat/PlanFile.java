package io.github.hansglorias.edtai.core.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * План режима «План» отдельным файлом Markdown: его открывают в редакторе EDT, правят, сохраняют —
 * и утверждают на выполнение уже исправленный текст. Первая строка — подсказка-комментарий, в план
 * она не входит.
 */
public final class PlanFile {

    /** Начало подсказки в первой строке файла; при чтении строка убирается. */
    static final String HEADER = "<!-- План EDT AI";
    private static final String HINT = ". Исправьте текст, сохраните (Ctrl+S) и нажмите"
            + " «Утвердить план и выполнить» в чате: модель выполнит эту редакцию. -->";
    private static final String SOURCE = " · источник ";

    private final Path file;

    public PlanFile(Path file) {
        this.file = file;
    }

    /** Файл плана чата в папке планов: {@code <папка>/<id чата>.md}. */
    public static PlanFile of(Path directory, String chatId) {
        String name = chatId.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return new PlanFile(directory.resolve((name.isEmpty() ? "plan" : name) + ".md"));
    }

    public Path path() {
        return file;
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    /** Отпечаток предложенного плана: по нему видно, для какого плана записан файл. */
    static String source(String plan) {
        return String.format("%08x", normalize(plan).hashCode());
    }

    /** Файл записан для этого плана модели (возможно, уже исправлен пользователем). */
    public boolean matches(String plan) throws IOException {
        if (!exists()) {
            return false;
        }
        String first = Files.readString(file, StandardCharsets.UTF_8).replace("\uFEFF", "").lines().findFirst().orElse("");
        return first.startsWith(HEADER + SOURCE + source(plan) + ".");
    }

    /** Записывает план заново, с подсказкой и отпечатком плана в первой строке. */
    public void write(String plan) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, HEADER + SOURCE + source(plan) + HINT + "\n\n" + normalize(plan) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** План из файла без подсказки; файла нет — пусто. */
    public String read() throws IOException {
        if (!exists()) {
            return "";
        }
        String text = normalize(Files.readString(file, StandardCharsets.UTF_8));
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (text.startsWith(HEADER)) {
            int end = text.indexOf('\n');
            text = end < 0 ? "" : text.substring(end + 1);
        }
        return text.strip();
    }

    /** Отличается ли план в файле от предложенного моделью: без учёта переводов строк и концевых пробелов. */
    public static boolean edited(String proposed, String current) {
        return !current.isBlank() && !normalize(proposed).equals(normalize(current));
    }

    /** {@code \r\n} → {@code \n}, без пробелов в концах строк и пустых строк по краям. */
    static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[ \\t]+\n", "\n").strip();
    }
}
