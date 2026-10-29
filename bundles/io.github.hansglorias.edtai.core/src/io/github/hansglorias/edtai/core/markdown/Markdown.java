package io.github.hansglorias.edtai.core.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Отрисовка подмножества Markdown, которым пишут модели: в текст для показа
 * и оформление его отрезков. Разметка из текста убирается, маркеры списков
 * и таблицы перерисовываются символами.
 *
 * <p>Это не полный CommonMark: правила выбраны так, чтобы ответы моделей
 * читались, а текст про код 1С не портился (подчёркивание внутри слова —
 * не курсив, незакрытая разметка остаётся как есть).
 */
public final class Markdown {

    /** Строка вместо разделителя {@code ---}. */
    public static final String RULE_TEXT = "──────────";
    /** Заголовок блока кода без указанного языка. */
    public static final String CODE_LABEL = "код";

    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})\\s*([^\\s`]*).*$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$");
    private static final Pattern RULE = Pattern.compile("^ {0,3}([-*_])(?:\\s*\\1){2,}\\s*$");
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}>\\s?(.*)$");
    private static final Pattern ITEM = Pattern.compile("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(?:\\|\\s*:?-+:?\\s*)*\\|?\\s*$");
    private static final String PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    private final StringBuilder out = new StringBuilder();
    private final List<Span> spans = new ArrayList<>();
    private final List<CodeBlock> blocks = new ArrayList<>();
    private final List<String> codeActions;

    private Markdown(List<String> codeActions) {
        this.codeActions = codeActions;
    }

    public static RichText render(String markdown) {
        return render(markdown, List.of());
    }

    /**
     * @param codeActions подписи действий, которые выводятся в строке над
     *                    каждым блоком кода, например «Копировать»
     */
    public static RichText render(String markdown, List<String> codeActions) {
        Markdown renderer = new Markdown(codeActions);
        renderer.blocks(markdown == null ? "" : markdown);
        return new RichText(renderer.out.toString(), renderer.spans, renderer.blocks);
    }

    /** Только разметка внутри строки (жирный, курсив, код, ссылки): для абзацев и ячеек документа. */
    public static RichText renderInline(String text) {
        Markdown renderer = new Markdown(List.of());
        renderer.inline(text == null ? "" : text);
        return new RichText(renderer.out.toString(), renderer.spans, List.of());
    }

    // ---- блоки ----

    private void blocks(String markdown) {
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean blank = false;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank()) {
                blank = true;
                i++;
                continue;
            }
            newLine(blank);
            blank = false;
            Matcher matcher = FENCE.matcher(line);
            if (matcher.matches()) {
                i = codeBlock(lines, i, matcher.group(1), matcher.group(2));
                continue;
            }
            if (isTableStart(lines, i)) {
                i = table(lines, i);
                continue;
            }
            matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                int start = out.length();
                inline(matcher.group(2));
                add(start, Kind.HEADING, String.valueOf(matcher.group(1).length()));
            } else if (RULE.matcher(line).matches()) {
                int start = out.length();
                out.append(RULE_TEXT);
                add(start, Kind.MARKER, "");
            } else if ((matcher = QUOTE.matcher(line)).matches()) {
                quote(matcher.group(1));
            } else if ((matcher = ITEM.matcher(line)).matches()) {
                item(matcher.group(1), matcher.group(2), matcher.group(3));
            } else {
                inline(line);
            }
            i++;
        }
    }

    /** Перевод строки перед следующим блоком; после пустой строки — ещё один. */
    private void newLine(boolean blank) {
        if (out.length() > 0) {
            out.append('\n');
            if (blank) {
                out.append('\n');
            }
        }
    }

    private int codeBlock(String[] lines, int open, String fence, String language) {
        char fenceChar = fence.charAt(0);
        int end = open + 1;
        List<String> code = new ArrayList<>();
        while (end < lines.length && !isClosingFence(lines[end], fenceChar, fence.length())) {
            code.add(lines[end]);
            end++;
        }
        int start = out.length();
        out.append(language.isEmpty() ? CODE_LABEL : language);
        for (String action : codeActions) {
            out.append("   ");
            int actionStart = out.length();
            out.append(action);
            add(actionStart, Kind.ACTION, action + ":" + blocks.size());
        }
        add(start, Kind.CODE_HEADER, language);
        out.append('\n');
        int codeStart = out.length();
        String text = String.join("\n", code);
        out.append(text);
        add(codeStart, Kind.CODE_BLOCK, language);
        blocks.add(new CodeBlock(language, text, start, out.length()));
        // Незакрытый блок (ответ ещё пишется) идёт до конца текста.
        return end < lines.length ? end + 1 : end;
    }

    private static boolean isClosingFence(String line, char fenceChar, int length) {
        String stripped = line.strip();
        if (stripped.length() < length) {
            return false;
        }
        for (int i = 0; i < stripped.length(); i++) {
            if (stripped.charAt(i) != fenceChar) {
                return false;
            }
        }
        return true;
    }

    private void quote(String text) {
        String rest = text;
        Matcher nested;
        while ((nested = QUOTE.matcher(rest)).matches()) {
            rest = nested.group(1);
        }
        int start = out.length();
        out.append("│ ");
        add(start, Kind.MARKER, "");
        inline(rest);
        add(start, Kind.QUOTE, "");
    }

    private void item(String indent, String marker, String text) {
        int width = indent.replace("\t", "    ").length();
        int depth = Math.min(width / 2, 6);
        out.append("  ".repeat(depth));
        int start = out.length();
        boolean bullet = !Character.isDigit(marker.charAt(0));
        out.append(bullet ? depth % 2 == 0 ? "•" : "◦" : marker);
        String rest = text;
        if (rest.startsWith("[ ] ")) {
            out.append(" ☐");
            rest = rest.substring(4);
        } else if (rest.startsWith("[x] ") || rest.startsWith("[X] ")) {
            out.append(" ☑");
            rest = rest.substring(4);
        }
        add(start, Kind.MARKER, "");
        out.append(' ');
        inline(rest);
    }

    // ---- таблицы ----

    private static boolean isTableStart(String[] lines, int i) {
        if (i + 1 >= lines.length || !lines[i].contains("|")) {
            return false;
        }
        String separator = lines[i + 1];
        return separator.contains("-") && TABLE_SEPARATOR.matcher(separator).matches()
                && (separator.contains("|") || lines[i].strip().startsWith("|"));
    }

    private int table(String[] lines, int first) {
        List<List<RichText>> rows = new ArrayList<>();
        rows.add(cells(lines[first]));
        int end = first + 2;
        while (end < lines.length && !lines[end].isBlank() && lines[end].contains("|")) {
            rows.add(cells(lines[end]));
            end++;
        }
        int columns = rows.get(0).size();
        int[] widths = new int[columns];
        for (List<RichText> row : rows) {
            for (int c = 0; c < columns && c < row.size(); c++) {
                widths[c] = Math.max(widths[c], row.get(c).text().length());
            }
        }
        int tableStart = out.length();
        for (int r = 0; r < rows.size(); r++) {
            if (r > 0) {
                out.append('\n');
            }
            int rowStart = out.length();
            List<RichText> row = rows.get(r);
            for (int c = 0; c < columns; c++) {
                if (c > 0) {
                    out.append(" │ ");
                }
                RichText cell = c < row.size() ? row.get(c) : new RichText("", List.of(), List.of());
                int cellStart = out.length();
                out.append(cell.text());
                for (Span span : cell.spans()) {
                    spans.add(new Span(cellStart + span.start(), span.length(), span.kind(), span.data()));
                }
                if (c < columns - 1) {
                    out.append(" ".repeat(widths[c] - cell.text().length()));
                }
            }
            if (r == 0) {
                add(rowStart, Kind.BOLD, "");
                out.append('\n');
                for (int c = 0; c < columns; c++) {
                    out.append(c > 0 ? "─┼─" : "").append("─".repeat(Math.max(1, widths[c])));
                }
            }
        }
        add(tableStart, Kind.TABLE, "");
        return end;
    }

    /** Ячейки строки таблицы, каждая отрисована отдельно. */
    private static List<RichText> cells(String line) {
        String row = line.strip();
        if (row.startsWith("|")) {
            row = row.substring(1);
        }
        if (row.endsWith("|") && !row.endsWith("\\|")) {
            row = row.substring(0, row.length() - 1);
        }
        List<RichText> cells = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i <= row.length()) {
            if (i == row.length() || row.charAt(i) == '|' && (i == 0 || row.charAt(i - 1) != '\\')) {
                Markdown cell = new Markdown(List.of());
                cell.inline(row.substring(start, i).strip().replace("\\|", "|"));
                cells.add(new RichText(cell.out.toString(), cell.spans, List.of()));
                start = i + 1;
                i++;
            } else if (row.charAt(i) == '`') {
                i = skipCode(row, i, row.length());
            } else {
                i++;
            }
        }
        return cells;
    }

    // ---- внутри строки ----

    private void inline(String text) {
        inline(text, 0, text.length());
    }

    private void inline(String s, int from, int to) {
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            int next = -1;
            if (c == '\\' && i + 1 < to && PUNCTUATION.indexOf(s.charAt(i + 1)) >= 0) {
                out.append(s.charAt(i + 1));
                next = i + 2;
            } else if (c == '`') {
                next = code(s, i, to);
            } else if (c == '*' || c == '_' || c == '~') {
                next = emphasis(s, i, from, to);
            } else if (c == '[') {
                next = link(s, i, to);
            } else if (c == '<') {
                next = autolink(s, i, to);
            } else if ((c == 'h' || c == 'H') && (i == from || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
                next = bareUrl(s, i, to);
            }
            if (next < 0) {
                out.append(c);
                i++;
            } else {
                i = next;
            }
        }
    }

    private int code(String s, int i, int to) {
        int n = run(s, i, to, '`');
        int close = findBackticks(s, i + n, to, n);
        if (close < 0) {
            out.append(s, i, i + n);
            return i + n;
        }
        String code = s.substring(i + n, close);
        if (code.length() >= 2 && code.startsWith(" ") && code.endsWith(" ") && !code.isBlank()) {
            code = code.substring(1, code.length() - 1);
        }
        int start = out.length();
        out.append(code);
        add(start, Kind.CODE, "");
        return close + n;
    }

    /** {@code *курсив*}, {@code **жирный**}, {@code ***оба***}, то же с {@code _}, {@code ~~зачёркнутый~~}. */
    private int emphasis(String s, int i, int from, int to) {
        char ch = s.charAt(i);
        int n = run(s, i, to, ch);
        boolean valid = n <= 3 && (ch != '~' || n == 2)
                && i + n < to && !Character.isWhitespace(s.charAt(i + n))
                && (ch != '_' || i == from || !Character.isLetterOrDigit(s.charAt(i - 1)));
        int close = valid ? findCloser(s, i + n, to, ch, n) : -1;
        if (close < 0) {
            out.append(s, i, i + n);
            return i + n;
        }
        int start = out.length();
        inline(s, i + n, close);
        if (ch == '~') {
            add(start, Kind.STRIKE, "");
        } else {
            if (n >= 2) {
                add(start, Kind.BOLD, "");
            }
            if (n != 2) {
                add(start, Kind.ITALIC, "");
            }
        }
        return close + n;
    }

    private static int findCloser(String s, int from, int to, char ch, int n) {
        int j = from;
        while (j < to) {
            char c = s.charAt(j);
            if (c == '`') {
                j = skipCode(s, j, to);
            } else if (c == '\\') {
                j += 2;
            } else if (c == ch) {
                int length = run(s, j, to, ch);
                boolean closes = length == n && j > from && !Character.isWhitespace(s.charAt(j - 1))
                        && (ch != '_' || j + n >= to || !Character.isLetterOrDigit(s.charAt(j + n)));
                if (closes) {
                    return j;
                }
                j += length;
            } else {
                j++;
            }
        }
        return -1;
    }

    /** {@code [текст](адрес)}; без подписи показывается адрес. */
    private int link(String s, int i, int to) {
        int depth = 0;
        int close = -1;
        for (int j = i; j < to && close < 0; j++) {
            char c = s.charAt(j);
            if (c == '`') {
                j = skipCode(s, j, to) - 1;
            } else if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                close = j;
            }
        }
        if (close < 0 || close + 1 >= to || s.charAt(close + 1) != '(') {
            return -1;
        }
        int parens = 0;
        int end = -1;
        for (int j = close + 1; j < to && end < 0; j++) {
            char c = s.charAt(j);
            if (c == '(') {
                parens++;
            } else if (c == ')' && --parens == 0) {
                end = j;
            }
        }
        if (end < 0) {
            return -1;
        }
        String target = s.substring(close + 2, end).strip();
        int space = target.indexOf(' ');
        String url = space < 0 ? target : target.substring(0, space);
        if (url.startsWith("<") && url.endsWith(">")) {
            url = url.substring(1, url.length() - 1);
        }
        if (url.isEmpty()) {
            return -1;
        }
        int start = out.length();
        if (close == i + 1) {
            out.append(url);
        } else {
            inline(s, i + 1, close);
        }
        add(start, Kind.LINK, url);
        return end + 1;
    }

    /** {@code <https://…>}. */
    private int autolink(String s, int i, int to) {
        int close = s.indexOf('>', i);
        if (close < 0 || close >= to) {
            return -1;
        }
        String url = s.substring(i + 1, close);
        if (!url.matches("(?i)(https?://|mailto:)\\S+")) {
            return -1;
        }
        int start = out.length();
        out.append(url);
        add(start, Kind.LINK, url);
        return close + 1;
    }

    /** Адрес {@code http(s)://…} прямо в тексте. */
    private int bareUrl(String s, int i, int to) {
        if (!s.regionMatches(true, i, "http://", 0, 7) && !s.regionMatches(true, i, "https://", 0, 8)) {
            return -1;
        }
        int end = i;
        while (end < to && !Character.isWhitespace(s.charAt(end)) && "<>\"`".indexOf(s.charAt(end)) < 0) {
            end++;
        }
        // Знаки препинания в конце — часть предложения, а не адреса; скобка — только если она лишняя.
        while (end > i) {
            char last = s.charAt(end - 1);
            String url = s.substring(i, end);
            boolean extraParen = last == ')' && count(url, '(') < count(url, ')');
            if (".,;:!?'*_".indexOf(last) >= 0 || extraParen) {
                end--;
            } else {
                break;
            }
        }
        String url = s.substring(i, end);
        if (url.length() <= "https://".length() || !url.contains(".") && !url.contains("localhost")) {
            return -1;
        }
        int start = out.length();
        out.append(url);
        add(start, Kind.LINK, url);
        return end;
    }

    // ---- служебное ----

    private void add(int start, Kind kind, String data) {
        if (out.length() > start || kind == Kind.CODE_BLOCK) {
            spans.add(new Span(start, out.length() - start, kind, data));
        }
    }

    private static int run(String s, int i, int to, char ch) {
        int j = i;
        while (j < to && s.charAt(j) == ch) {
            j++;
        }
        return j - i;
    }

    /** Начало следующей серии ровно из {@code n} обратных кавычек или -1. */
    private static int findBackticks(String s, int from, int to, int n) {
        int j = from;
        while (j < to) {
            if (s.charAt(j) == '`') {
                int length = run(s, j, to, '`');
                if (length == n) {
                    return j;
                }
                j += length;
            } else {
                j++;
            }
        }
        return -1;
    }

    /** Позиция после кода в обратных кавычках, начинающегося в {@code i}. */
    private static int skipCode(String s, int i, int to) {
        int n = run(s, i, to, '`');
        int close = findBackticks(s, i + n, to, n);
        return close < 0 ? i + n : close + n;
    }

    private static int count(String s, char ch) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ch) {
                count++;
            }
        }
        return count;
    }
}
