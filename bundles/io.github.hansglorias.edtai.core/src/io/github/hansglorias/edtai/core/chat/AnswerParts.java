package io.github.hansglorias.edtai.core.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ответ для ленты по порядку: текст, вызовы инструментов, снова текст —
 * как это происходило. Вызов встаёт туда, сколько текста было написано до
 * него ({@link ToolUse#at()}), но не посреди строки: в начало следующей.
 */
public final class AnswerParts {

    /** Кусок ответа: либо текст, либо подряд идущие вызовы. */
    public record Part(String text, List<ToolUse> tools) {

        public Part {
            tools = List.copyOf(tools);
        }

        public boolean isText() {
            return tools.isEmpty();
        }
    }

    private AnswerParts() {
    }

    public static List<Part> split(String text, List<ToolUse> tools) {
        record Placed(int at, int order, ToolUse use) {
        }
        List<Placed> placed = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            placed.add(new Placed(lineStart(text, tools.get(i).at()), i, tools.get(i)));
        }
        placed.sort(Comparator.comparingInt(Placed::at).thenComparingInt(Placed::order));

        List<Part> parts = new ArrayList<>();
        int cursor = 0;
        List<ToolUse> group = new ArrayList<>();
        for (Placed item : placed) {
            if (item.at() > cursor) {
                flush(parts, group);
                addText(parts, text.substring(cursor, item.at()));
                cursor = item.at();
            }
            group.add(item.use());
        }
        flush(parts, group);
        addText(parts, text.substring(cursor));
        return parts;
    }

    /** Место вызова: не раньше начала и не позже конца текста, в начале строки. */
    private static int lineStart(String text, int at) {
        int position = Math.max(0, Math.min(text.length(), at));
        if (position == 0 || position == text.length() || text.charAt(position - 1) == '\n') {
            return position;
        }
        int newline = text.indexOf('\n', position);
        return newline < 0 ? text.length() : newline + 1;
    }

    private static void flush(List<Part> parts, List<ToolUse> group) {
        if (!group.isEmpty()) {
            parts.add(new Part("", group));
            group.clear();
        }
    }

    private static void addText(List<Part> parts, String text) {
        String stripped = text.strip();
        if (!stripped.isEmpty()) {
            parts.add(new Part(stripped, List.of()));
        }
    }
}
