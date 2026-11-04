package io.github.hansglorias.edtai.core.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Поиск фрагмента для правки, когда точного совпадения нет: модель пишет отступы пробелами вместо
 * табов модуля, теряет пробелы в концах строк или копирует номера строк из вывода read_module.
 * Сравниваются целые строки без учёта пробельных различий; совпасть должно ровно одно место. Отступы
 * нового текста приводятся к стилю модуля. Не нашлось — ошибка с первой несовпавшей строкой.
 */
final class LooseMatch {

    /** Номер строки из вывода read_module: «  42  текст». */
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+  ");
    private static final int TAB_WIDTH = 4;

    private LooseMatch() {
    }

    /**
     * @param current   текст модуля
     * @param oldText   фрагмент модели, переводы строк — {@code \n}
     * @param newText   замена модели, переводы строк — {@code \n}
     * @param delimiter перевод строки модуля
     * @throws ToolException не нашлось или нашлось несколько мест — с подсказкой
     */
    static TextChange.Replacement replace(String current, String oldText, String newText, String delimiter) throws ToolException {
        List<String> old = new ArrayList<>(List.of(oldText.split("\n", -1)));
        List<String> fresh = new ArrayList<>(List.of(newText.split("\n", -1)));
        if (!old.isEmpty() && old.stream().filter(line -> !line.isBlank()).allMatch(line -> NUMBERED.matcher(line).find())) {
            old.replaceAll(LooseMatch::unnumber);
            if (fresh.stream().filter(line -> !line.isBlank()).allMatch(line -> NUMBERED.matcher(line).find())) {
                fresh.replaceAll(LooseMatch::unnumber);
            }
        }
        int leading = 0;
        while (!old.isEmpty() && old.get(0).isBlank()) {
            old.remove(0);
            leading++;
        }
        while (!old.isEmpty() && old.get(old.size() - 1).isBlank()) {
            old.remove(old.size() - 1);
        }
        if (old.isEmpty()) {
            throw notFound("");
        }
        for (int i = 0; i < leading && fresh.size() > 1 && fresh.get(0).isBlank(); i++) {
            fresh.remove(0);
        }
        while (fresh.size() > 1 && fresh.get(fresh.size() - 1).isBlank()) {
            fresh.remove(fresh.size() - 1);
        }

        List<int[]> lines = lines(current);
        List<String> canon = lines.stream().map(range -> canon(current.substring(range[0], range[1]))).toList();
        List<String> wanted = old.stream().map(LooseMatch::canon).toList();
        List<Integer> found = new ArrayList<>();
        int best = -1;
        int bestLength = 0;
        for (int i = 0; i + wanted.size() <= canon.size(); i++) {
            int k = 0;
            while (k < wanted.size() && canon.get(i + k).equals(wanted.get(k))) {
                k++;
            }
            if (k == wanted.size()) {
                found.add(i);
            } else if (k > bestLength) {
                best = i;
                bestLength = k;
            }
        }
        if (found.size() > 1) {
            throw new ToolException("old_text встречается в модуле " + found.size() + " раз (без учёта пробелов): добавь соседние"
                    + " строки, чтобы фрагмент был единственным");
        }
        if (found.isEmpty()) {
            if (best < 0) {
                throw notFound("Первой строки фрагмента «" + shorten(old.get(0).strip()) + "» в модуле нет даже без учёта"
                        + " пробелов. ");
            }
            int line = best + bestLength;
            String actual = line < lines.size() ? current.substring(lines.get(line)[0], lines.get(line)[1]).strip() : "(конец модуля)";
            throw notFound("Начало фрагмента совпало со строкой " + (best + 1) + ", но строка " + (line + 1) + " отличается: в модуле «"
                    + shorten(actual) + "», в old_text «" + shorten(old.get(bestLength).strip()) + "». ");
        }
        int first = found.get(0);
        int start = lines.get(first)[0];
        int end = lines.get(first + old.size() - 1)[1];
        List<String> matched = lines.subList(first, first + old.size()).stream()
                .map(range -> current.substring(range[0], range[1])).toList();
        return new TextChange.Replacement(start, end - start, String.join(delimiter, reindent(fresh, old, matched)));
    }

    /**
     * Отступы замены — в стиле модуля: относительный отступ каждой строки модели сохраняется, а
     * записывается так же, как у первой совпавшей строки модуля (табами или пробелами).
     */
    private static List<String> reindent(List<String> fresh, List<String> old, List<String> matched) {
        boolean differs = false;
        for (int k = 0; k < old.size() && !differs; k++) {
            differs = !indent(old.get(k)).equals(indent(matched.get(k)));
        }
        if (!differs) {
            return fresh;
        }
        String anchorOld = indent(old.get(0));
        String anchorFile = indent(matched.get(0));
        boolean tabs = matched.stream().anyMatch(line -> indent(line).contains("\t"));
        int base = columns(anchorOld);
        int fileBase = columns(anchorFile);
        List<String> result = new ArrayList<>();
        for (String line : fresh) {
            if (line.isBlank()) {
                result.add(line.strip());
                continue;
            }
            int width = Math.max(0, fileBase + columns(indent(line)) - base);
            String prefix = tabs ? "\t".repeat((width + TAB_WIDTH / 2) / TAB_WIDTH) : " ".repeat(width);
            result.add(prefix + line.stripLeading());
        }
        return result;
    }

    private static String indent(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t' || line.charAt(i) == ' ')) {
            i++;
        }
        return line.substring(0, i);
    }

    private static int columns(String indent) {
        int width = 0;
        for (char c : indent.toCharArray()) {
            width += c == '\t' ? TAB_WIDTH - width % TAB_WIDTH : 1;
        }
        return width;
    }

    /** Начало и конец (без перевода строки) каждой строки текста. */
    private static List<int[]> lines(String text) {
        List<int[]> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(new int[] { start, i });
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(new int[] { start, text.length() });
        }
        return lines;
    }

    /** Строка без различий в пробелах: неразрывный пробел — обычный, невидимые символы убраны. */
    static String canon(String line) {
        return line.replace(' ', ' ').replace("​", "").replace("﻿", "").strip().replaceAll("\\s+", " ");
    }

    private static String unnumber(String line) {
        return NUMBERED.matcher(line).replaceFirst("");
    }

    private static String shorten(String text) {
        return text.length() <= 120 ? text : text.substring(0, 119) + "…";
    }

    private static ToolException notFound(String detail) {
        return new ToolException("old_text не найден в модуле. " + detail + "Нужен точный текст модуля (с отступами, без номеров строк"
                + " из read_module); прочитай нужные строки заново и скопируй их.");
    }
}
