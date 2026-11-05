package io.github.hansglorias.edtai.core.llm;

import java.util.Arrays;

/**
 * Длинный непрерывный повтор вне примеров Markdown. Состояние не зависит от
 * разбиения SSE: для каждого периода считаем совпадения с предыдущей копией.
 * Память детектора ограничена максимальным периодом, полного текста здесь нет.
 */
final class RepetitionGuard {

    static final int MAX_PERIOD = 256;
    static final int MIN_COPIES = 16;
    static final int MIN_CHARS = 512;

    private final char[] window = new char[MAX_PERIOD + 1];
    private final int[] matches = new int[MAX_PERIOD + 1];
    private final Examples examples = new Examples();
    private int offset;
    private int length;

    /** @return начало повторяющегося хвоста в полном тексте, либо -1 */
    int append(String part) {
        for (int i = 0; i < part.length(); i++, offset++) {
            char value = part.charAt(i);
            if (examples.accept(value)) {
                if (length > 0) Arrays.fill(matches, 0);
                length = 0;
                continue;
            }
            for (int period = 1; period <= Math.min(length, MAX_PERIOD); period++) {
                matches[period] = value == window[(offset - period) % window.length] ? matches[period] + 1 : 0;
                int repeated = matches[period] + period;
                if (repeated >= MIN_CHARS && matches[period] >= (MIN_COPIES - 1) * period
                        && meaningful(period, value)) {
                    return offset + 1 - repeated;
                }
            }
            window[offset % window.length] = value;
            length++;
        }
        return -1;
    }

    private boolean meaningful(int period, char value) {
        if (!Character.isWhitespace(value)) return true;
        for (int back = 1; back < period; back++) {
            if (!Character.isWhitespace(window[(offset - back) % window.length])) return true;
        }
        return false;
    }

    /** Потоковое распознавание fenced/inline code, строк цитаты и кода с отступом. */
    private static final class Examples {
        private char fence;
        private int fenceLength;
        private boolean prefix = true;
        private int spaces;
        private boolean quoted;
        private boolean indented;
        private char marker;
        private int markers;
        private boolean markerEnded;
        private boolean whitespaceTrailer = true;
        private int inlineLength;
        private int inlineRun;

        boolean accept(char value) {
            if (value == '\n' || value == '\r') {
                finishInlineRun();
                boolean excluded = fence != 0 || marker != 0 || quoted || indented || inlineLength > 0;
                if (markers >= 3) {
                    if (fence == 0) {
                        fence = marker;
                        fenceLength = markers;
                    } else if (marker == fence && markers >= fenceLength && whitespaceTrailer) {
                        fence = 0;
                    }
                }
                prefix = true;
                spaces = 0;
                quoted = indented = markerEnded = false;
                whitespaceTrailer = true;
                marker = 0;
                markers = 0;
                return excluded;
            }
            if (quoted || indented) return true;
            if (prefix) {
                if (value == ' ') {
                    if (++spaces >= 4) indented = true;
                    return indented || fence != 0 || inlineLength > 0;
                }
                if (value == '\t') {
                    indented = true;
                    return true;
                }
                prefix = false;
                if (value == '>') {
                    quoted = true;
                    return true;
                }
                if (value == '`' || value == '~') {
                    marker = value;
                    markers = 1;
                    return true;
                }
            }
            if (marker != 0) {
                if (!markerEnded && value == marker) {
                    markers++;
                    return true;
                }
                if (!markerEnded && markers < 3 && fence == 0) {
                    if (marker == '`') inlineLength = markers;
                    marker = 0;
                    markers = 0;
                } else {
                    markerEnded = true;
                    if (!Character.isWhitespace(value)) whitespaceTrailer = false;
                    return true;
                }
            }
            if (fence != 0) return true;
            if (value == '`') {
                inlineRun++;
                return true;
            }
            finishInlineRun();
            return inlineLength > 0;
        }

        private void finishInlineRun() {
            if (inlineRun > 0) {
                if (inlineLength == 0) inlineLength = inlineRun;
                else if (inlineRun == inlineLength) inlineLength = 0;
                inlineRun = 0;
            }
        }
    }
}
