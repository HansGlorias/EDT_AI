package io.github.hansglorias.edtai.core.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Построчный diff в формате unified для выгрузки правок: короче полных текстов модуля и не
 * искажается их обрезкой. Без сторонних библиотек: общее начало и конец отбрасываются, середина —
 * по наибольшей общей подпоследовательности строк; слишком большая середина — одной заменой.
 */
public final class LineDiff {

    /** Предел ячеек таблицы LCS: середина больше — без поиска общих строк. */
    private static final long MAX_CELLS = 4_000_000L;
    private static final int CONTEXT = 2;

    private LineDiff() {
    }

    /** Diff {@code before} → {@code after}; одинаковые тексты — пустая строка. */
    public static String unified(String before, String after) {
        List<String> a = lines(before);
        List<String> b = lines(after);
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        // Операции по строкам: ' ' — общая, '-' — убрана, '+' — добавлена; с номерами строк в before/after.
        List<char[]> kinds = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            kinds.add(new char[] { ' ' });
            texts.add(a.get(i));
        }
        List<String> midA = a.subList(prefix, a.size() - suffix);
        List<String> midB = b.subList(prefix, b.size() - suffix);
        if ((long) midA.size() * midB.size() <= MAX_CELLS) {
            int[][] lcs = new int[midA.size() + 1][midB.size() + 1];
            for (int i = midA.size() - 1; i >= 0; i--) {
                for (int j = midB.size() - 1; j >= 0; j--) {
                    lcs[i][j] = midA.get(i).equals(midB.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
            int i = 0;
            int j = 0;
            while (i < midA.size() || j < midB.size()) {
                if (i < midA.size() && j < midB.size() && midA.get(i).equals(midB.get(j))) {
                    kinds.add(new char[] { ' ' });
                    texts.add(midA.get(i++));
                    j++;
                } else if (i < midA.size() && (j == midB.size() || lcs[i + 1][j] >= lcs[i][j + 1])) {
                    kinds.add(new char[] { '-' });
                    texts.add(midA.get(i++));
                } else {
                    kinds.add(new char[] { '+' });
                    texts.add(midB.get(j++));
                }
            }
        } else {
            midA.forEach(line -> { kinds.add(new char[] { '-' }); texts.add(line); });
            midB.forEach(line -> { kinds.add(new char[] { '+' }); texts.add(line); });
        }
        for (int i = a.size() - suffix; i < a.size(); i++) {
            kinds.add(new char[] { ' ' });
            texts.add(a.get(i));
        }
        return hunks(kinds, texts);
    }

    private static String hunks(List<char[]> kinds, List<String> texts) {
        StringBuilder out = new StringBuilder();
        int n = kinds.size();
        int k = 0;
        while (k < n) {
            if (kinds.get(k)[0] == ' ') {
                k++;
                continue;
            }
            int start = Math.max(0, k - CONTEXT);
            int end = k;
            int quiet = 0;
            // Расширяем ханк, пока между изменениями не больше 2 * CONTEXT общих строк.
            for (int m = k; m < n; m++) {
                if (kinds.get(m)[0] == ' ') {
                    if (++quiet > 2 * CONTEXT) {
                        break;
                    }
                } else {
                    quiet = 0;
                    end = m;
                }
            }
            end = Math.min(n - 1, end + CONTEXT);
            int oldLine = 1;
            int newLine = 1;
            for (int m = 0; m < start; m++) {
                if (kinds.get(m)[0] != '+') oldLine++;
                if (kinds.get(m)[0] != '-') newLine++;
            }
            int oldCount = 0;
            int newCount = 0;
            for (int m = start; m <= end; m++) {
                if (kinds.get(m)[0] != '+') oldCount++;
                if (kinds.get(m)[0] != '-') newCount++;
            }
            out.append("@@ -").append(oldLine).append(',').append(oldCount).append(" +").append(newLine).append(',')
                    .append(newCount).append(" @@\n");
            for (int m = start; m <= end; m++) {
                out.append(kinds.get(m)[0]).append(texts.get(m)).append('\n');
            }
            k = end + 1;
        }
        return out.toString();
    }

    private static List<String> lines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String body = text.startsWith("﻿") ? text.substring(1) : text;
        List<String> lines = new ArrayList<>(List.of(body.split("\r\n|\r|\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
