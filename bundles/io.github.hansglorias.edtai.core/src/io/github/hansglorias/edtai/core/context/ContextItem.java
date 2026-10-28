package io.github.hansglorias.edtai.core.context;

import java.util.Locale;
import java.util.Objects;

/**
 * Фрагмент кода из проекта, прикреплённый к вопросу: строки модуля или
 * модуль целиком. Хранится в сообщении вместе с текстом, поэтому повтор
 * запроса и перезапуск EDT отправляют модели тот же код.
 *
 * @param project      имя проекта
 * @param path         путь файла относительно проекта, разделитель {@code /}
 * @param objectName   имя модуля в нотации 1С («ОбщийМодуль.Х.Модуль»), пустая строка — не распознано
 * @param firstLine    первая строка фрагмента, с 1
 * @param lastLine     последняя строка фрагмента (включительно); меньше {@code firstLine}, если не поместилась ни одна
 * @param totalLines   строк в файле
 * @param text         текст строк {@code firstLine..lastLine}, переводы строк — {@code \n}
 * @param omittedLines сколько строк после {@code lastLine} отброшено по лимиту контекста
 */
public record ContextItem(String project, String path, String objectName, int firstLine, int lastLine, int totalLines,
        String text, int omittedLines) {

    public ContextItem {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(path, "path");
        objectName = Objects.requireNonNullElse(objectName, "");
        Objects.requireNonNull(text, "text");
        totalLines = Math.max(totalLines, Math.max(lastLine, 1));
        omittedLines = Math.max(0, omittedLines);
    }

    /**
     * Фрагмент файла: имя модуля выводится из пути.
     *
     * @param text строки {@code firstLine..lastLine}, любые переводы строк
     */
    public static ContextItem of(String project, String path, int firstLine, int lastLine, int totalLines, String text) {
        String normalized = path.replace('\\', '/');
        return new ContextItem(project, normalized, BslModulePath.metadataName(normalized).orElse(""), firstLine, lastLine,
                totalLines, text.replace("\r\n", "\n").replace('\r', '\n'), 0);
    }

    /** Модуль целиком. */
    public boolean whole() {
        return firstLine == 1 && lastLine + omittedLines >= totalLines;
    }

    public boolean truncated() {
        return omittedLines > 0;
    }

    /** Последняя строка до обрезки по лимиту. */
    public int requestedLastLine() {
        return lastLine + omittedLines;
    }

    /** Имя модуля, а если не распознали — путь файла. */
    public String name() {
        return objectName.isEmpty() ? path : objectName;
    }

    /** Подпись на панели и в ленте: «ОбщийМодуль.Х.Модуль: 10–25». */
    public String label() {
        String label = name() + (whole() ? "" : ": " + lines(firstLine, requestedLastLine()));
        return truncated() ? label + " (обрезано)" : label;
    }

    /** Подсказка: проект, путь, строки, размер. */
    public String description() {
        String lines = whole() ? "весь модуль, " + totalLines + " " + plural(totalLines, "строка", "строки", "строк")
                : "строки " + lines(firstLine, requestedLastLine()) + " из " + totalLines;
        String description = project + "/" + path + "\n" + lines + ", " + text.length() + " "
                + plural(text.length(), "символ", "символа", "символов");
        return truncated() ? description + "\nНе поместилось в лимит контекста: " + omittedLines + " "
                + plural(omittedLines, "строка", "строки", "строк") : description;
    }

    /** Язык блока кода для модели: {@code bsl} у модулей 1С, иначе расширение файла. */
    public String language() {
        int dot = path.lastIndexOf('.');
        return dot < 0 || dot < path.lastIndexOf('/') ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Тот же участок того же файла: такой фрагмент второй раз не прикрепляем. */
    public boolean sameRange(ContextItem other) {
        return project.equals(other.project) && path.equals(other.path) && firstLine == other.firstLine
                && requestedLastLine() == other.requestedLastLine();
    }

    /**
     * Оставляет столько целых строк с начала, сколько помещается в
     * {@code maxChars} символов; остальные считаются отброшенными.
     */
    public ContextItem truncate(int maxChars) {
        if (text.length() <= maxChars) {
            return this;
        }
        int end = 0;
        int kept = 0;
        while (true) {
            int newline = text.indexOf('\n', end);
            int lineEnd = newline < 0 ? text.length() : newline;
            if (lineEnd > maxChars) {
                break;
            }
            kept++;
            end = lineEnd + 1;
            if (newline < 0) {
                break;
            }
        }
        String keptText = kept == 0 ? "" : text.substring(0, Math.min(end, text.length())).replaceAll("\n$", "");
        int newLast = firstLine + kept - 1;
        return new ContextItem(project, path, objectName, firstLine, newLast, totalLines, keptText,
                omittedLines + lastLine - newLast);
    }

    static String lines(int first, int last) {
        return first == last ? String.valueOf(first) : first + "–" + last;
    }

    static String plural(int count, String one, String few, String many) {
        int mod100 = count % 100;
        int mod10 = count % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return many;
        }
        return mod10 == 1 ? one : mod10 >= 2 && mod10 <= 4 ? few : many;
    }
}
