package io.github.hansglorias.edtai.core.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown модели по блокам — для документов: заголовки, абзацы, пункты
 * списков, цитаты, код, таблицы, разделители. Разметку внутри строки
 * блоки не трогают: её разбирает {@code Markdown.renderInline}.
 */
final class MarkdownBlocks {

    enum Type {
        HEADING, PARAGRAPH, ITEM, QUOTE, CODE, TABLE, RULE
    }

    /**
     * @param level    у заголовка — 1…6, у пункта списка — вложенность 0…2
     * @param numbered пункт нумерованного списка
     * @param text     текст заголовка, абзаца, пункта, цитаты (строки через {@code \n})
     * @param lines    строки кода
     * @param rows     строки таблицы, первая — шапка
     * @param align    выравнивание колонок таблицы: {@code left}, {@code center}, {@code right}
     */
    record Block(Type type, int level, boolean numbered, String text, List<String> lines, List<List<String>> rows,
            List<String> align) {

        static Block of(Type type, int level, String text) {
            return new Block(type, level, false, text, List.of(), List.of(), List.of());
        }

        /** Число колонок таблицы. */
        int columns() {
            return rows.stream().mapToInt(List::size).max().orElse(1);
        }

        /** Ячейка таблицы; нет такой — пустая. */
        String cell(int row, int column) {
            List<String> cells = rows.get(row);
            return column < cells.size() ? cells.get(column) : "";
        }
    }

    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$");
    private static final Pattern RULE = Pattern.compile("^ {0,3}([-*_])(?:\\s*\\1){2,}\\s*$");
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}>\\s?(.*)$");
    private static final Pattern ITEM = Pattern.compile("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(?:\\|\\s*:?-+:?\\s*)*\\|?\\s*$");

    private MarkdownBlocks() {
    }

    static List<Block> parse(String markdown) {
        String[] lines = (markdown == null ? "" : markdown).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank()) {
                i++;
                continue;
            }
            Matcher matcher = FENCE.matcher(line);
            if (matcher.matches()) {
                String fence = matcher.group(1);
                List<String> code = new ArrayList<>();
                i++;
                while (i < lines.length && !isClosing(lines[i], fence)) {
                    code.add(lines[i]);
                    i++;
                }
                blocks.add(new Block(Type.CODE, 0, false, "", code, List.of(), List.of()));
                i = Math.min(lines.length, i + 1);
            } else if (isTableStart(lines, i)) {
                List<List<String>> rows = new ArrayList<>();
                rows.add(cells(line));
                List<String> align = new ArrayList<>();
                for (String cell : cells(lines[i + 1])) {
                    String stripped = cell.strip();
                    align.add(stripped.startsWith(":") && stripped.endsWith(":") ? "center" : stripped.endsWith(":") ? "right" : "left");
                }
                i += 2;
                while (i < lines.length && lines[i].contains("|") && !lines[i].isBlank()) {
                    rows.add(cells(lines[i]));
                    i++;
                }
                blocks.add(new Block(Type.TABLE, 0, false, "", List.of(), rows, align));
            } else if ((matcher = HEADING.matcher(line)).matches()) {
                blocks.add(Block.of(Type.HEADING, matcher.group(1).length(), matcher.group(2)));
                i++;
            } else if (RULE.matcher(line).matches()) {
                blocks.add(Block.of(Type.RULE, 0, ""));
                i++;
            } else if (QUOTE.matcher(line).matches()) {
                StringBuilder text = new StringBuilder();
                while (i < lines.length && (matcher = QUOTE.matcher(lines[i])).matches()) {
                    text.append(text.isEmpty() ? "" : "\n").append(matcher.group(1));
                    i++;
                }
                blocks.add(Block.of(Type.QUOTE, 0, text.toString()));
            } else if ((matcher = ITEM.matcher(line)).matches()) {
                int indent = matcher.group(1).replace("\t", "    ").length();
                boolean numbered = Character.isDigit(matcher.group(2).charAt(0));
                StringBuilder text = new StringBuilder(matcher.group(3).strip());
                i = continuation(lines, i + 1, text);
                blocks.add(new Block(Type.ITEM, Math.min(2, indent / 2), numbered, text.toString(), List.of(), List.of(), List.of()));
            } else {
                StringBuilder text = new StringBuilder(line.strip());
                i = continuation(lines, i + 1, text);
                blocks.add(Block.of(Type.PARAGRAPH, 0, text.toString()));
            }
        }
        return blocks;
    }

    /** Строки, продолжающие абзац или пункт, — через {@code \n}; возвращает первую не вошедшую. */
    private static int continuation(String[] lines, int from, StringBuilder text) {
        int i = from;
        while (i < lines.length && !lines[i].isBlank() && !startsBlock(lines, i)) {
            text.append('\n').append(lines[i].strip());
            i++;
        }
        return i;
    }

    private static boolean isClosing(String line, String fence) {
        String stripped = line.strip();
        return stripped.startsWith(fence) && stripped.replace(fence.substring(0, 1), "").isEmpty();
    }

    private static boolean isTableStart(String[] lines, int i) {
        return i + 1 < lines.length && lines[i].contains("|") && TABLE_SEPARATOR.matcher(lines[i + 1]).matches();
    }

    private static boolean startsBlock(String[] lines, int i) {
        String line = lines[i];
        return FENCE.matcher(line).matches() || HEADING.matcher(line).matches() || RULE.matcher(line).matches()
                || QUOTE.matcher(line).matches() || ITEM.matcher(line).matches() || isTableStart(lines, i);
    }

    /** Ячейки строки таблицы: {@code |} внутри кода и {@code \|} — не разделители. */
    static List<String> cells(String line) {
        String row = line.strip();
        if (row.startsWith("|")) {
            row = row.substring(1);
        }
        if (row.endsWith("|") && !row.endsWith("\\|")) {
            row = row.substring(0, row.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        int start = 0;
        boolean code = false;
        for (int i = 0; i <= row.length(); i++) {
            if (i == row.length() || row.charAt(i) == '|' && !code && (i == 0 || row.charAt(i - 1) != '\\')) {
                cells.add(row.substring(start, i).strip().replace("\\|", "|"));
                start = i + 1;
            } else if (row.charAt(i) == '`') {
                code = !code;
            }
        }
        return cells;
    }
}
