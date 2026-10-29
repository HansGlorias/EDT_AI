package io.github.hansglorias.edtai.core.documents;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.hansglorias.edtai.core.markdown.Markdown;

/**
 * Книга Excel (.xlsx) из того, что пишет модель: таблицы Markdown (каждая —
 * свой лист, имя — из заголовка над ней) или CSV (разделитель «;», «,» или
 * табуляция). Числа и даты {@code ДД.ММ.ГГГГ} становятся числами и датами,
 * ячейки с «=» — формулами. Первая строка — шапка: жирная, закреплена, с
 * автофильтром; ширина колонок — по содержимому.
 */
public final class XlsxWriter {

    public static final String MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9]\\d{0,14})([.,]\\d{1,10})?");
    private static final Pattern GROUPED = Pattern.compile("-?\\d{1,3}([ \\u00A0\\u202F]\\d{3})+([.,]\\d{1,10})?");
    private static final Pattern DATE = Pattern.compile("(\\d{2})\\.(\\d{2})\\.(\\d{4})");
    /** Формула: функция Excel по-английски или ссылка на ячейку — иначе «=» в тексте осталось бы испорченной формулой. */
    private static final Pattern FORMULA = Pattern.compile("=\\s*(?:[A-Z][A-Z0-9.]*\\(|\\$?[A-Z]{1,3}\\$?\\d+).*", Pattern.DOTALL);
    private static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);
    private static final int MAX_SHEET_NAME = 31;

    /** Стили ячеек в {@code styles.xml}. */
    private static final int HEADER = 1;
    private static final int CELL = 2;
    private static final int DATE_CELL = 3;

    record Sheet(String name, List<List<String>> rows) {
    }

    private XlsxWriter() {
    }

    /** Книга из таблиц Markdown, а если их нет — из CSV. */
    public static byte[] fromText(String content) {
        List<Sheet> sheets = sheets(content == null ? "" : content);
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", contentTypes(sheets.size()));
        parts.put("_rels/.rels", Ooxml.rootRels(Ooxml.R + "/officeDocument", "xl/workbook.xml"));
        parts.put("xl/workbook.xml", workbook(sheets));
        parts.put("xl/_rels/workbook.xml.rels", workbookRels(sheets.size()));
        parts.put("xl/styles.xml", STYLES);
        for (int i = 0; i < sheets.size(); i++) {
            parts.put("xl/worksheets/sheet" + (i + 1) + ".xml", sheet(sheets.get(i).rows()));
        }
        return Ooxml.zip(parts);
    }

    /** Листы: таблицы Markdown с именами из заголовков, иначе один лист из CSV. */
    static List<Sheet> sheets(String content) {
        List<Sheet> sheets = new ArrayList<>();
        Set<String> names = new HashSet<>();
        String heading = null;
        for (MarkdownBlocks.Block block : MarkdownBlocks.parse(content)) {
            if (block.type() == MarkdownBlocks.Type.HEADING) {
                heading = Markdown.renderInline(block.text()).text();
            } else if (block.type() == MarkdownBlocks.Type.TABLE) {
                List<List<String>> rows = new ArrayList<>();
                for (List<String> row : block.rows()) {
                    rows.add(row.stream().map(cell -> Markdown.renderInline(cell).text()).toList());
                }
                sheets.add(new Sheet(sheetName(heading, sheets.size() + 1, names), rows));
                heading = null;
            }
        }
        if (sheets.isEmpty()) {
            sheets.add(new Sheet(sheetName(null, 1, names), csv(content)));
        }
        return sheets;
    }

    /** Имя листа: без {@code []:*?/\}, до 31 знака, не повторяется. */
    private static String sheetName(String heading, int number, Set<String> taken) {
        String name = heading == null ? "" : heading.replaceAll("[\\[\\]:*?/\\\\]", " ").replaceAll("\\s+", " ").strip();
        name = name.replaceAll("^'+|'+$", "");
        if (name.isEmpty()) {
            name = "Лист" + number;
        }
        name = name.length() > MAX_SHEET_NAME ? name.substring(0, MAX_SHEET_NAME).strip() : name;
        String unique = name;
        for (int suffix = 2; taken.contains(unique.toLowerCase(Locale.ROOT)); suffix++) {
            String tail = " (" + suffix + ")";
            unique = name.substring(0, Math.min(name.length(), MAX_SHEET_NAME - tail.length())) + tail;
        }
        taken.add(unique.toLowerCase(Locale.ROOT));
        return unique;
    }

    /** CSV с кавычками и переводами строк в них; разделитель — какой чаще в первой строке. */
    static List<List<String>> csv(String content) {
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        char separator = separator(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (c == separator) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        rows.removeIf(cells -> cells.stream().allMatch(String::isBlank));
        return rows;
    }

    private static char separator(String text) {
        int end = text.indexOf('\n');
        String first = end < 0 ? text : text.substring(0, end);
        char best = ';';
        long most = 0;
        for (char candidate : new char[] { ';', '\t', ',' }) {
            long count = first.chars().filter(c -> c == candidate).count();
            if (count > most) {
                most = count;
                best = candidate;
            }
        }
        return best;
    }

    // ---- части пакета ----

    private static String sheet(List<List<String>> rows) {
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        boolean header = rows.size() > 1;
        StringBuilder xml = new StringBuilder(Ooxml.XML).append("<worksheet xmlns=\"").append(MAIN).append("\" xmlns:r=\"")
                .append(Ooxml.R).append("\">");
        if (header) {
            xml.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\""
                    + " state=\"frozen\"/></sheetView></sheetViews>");
        }
        xml.append("<sheetFormatPr defaultRowHeight=\"15\"/>");
        if (columns > 0) {
            xml.append("<cols>");
            for (int column = 0; column < columns; column++) {
                int width = 8;
                for (List<String> row : rows) {
                    if (column < row.size()) {
                        width = Math.max(width, row.get(column).lines().mapToInt(String::length).max().orElse(0) + 2);
                    }
                }
                xml.append("<col min=\"").append(column + 1).append("\" max=\"").append(column + 1).append("\" width=\"")
                        .append(Math.min(60, width)).append("\" customWidth=\"1\"/>");
            }
            xml.append("</cols>");
        }
        xml.append("<sheetData>");
        for (int row = 0; row < rows.size(); row++) {
            xml.append("<row r=\"").append(row + 1).append("\">");
            List<String> cells = rows.get(row);
            for (int column = 0; column < cells.size(); column++) {
                cell(xml, column(column) + (row + 1), cells.get(column).strip(), header && row == 0);
            }
            xml.append("</row>");
        }
        xml.append("</sheetData>");
        if (header && columns > 0) {
            xml.append("<autoFilter ref=\"").append(range(rows.size(), columns)).append("\"/>");
        }
        return xml.append("</worksheet>").toString();
    }

    /** Ячейка: формула, число, дата или текст. Шапка — всегда текст. */
    private static void cell(StringBuilder xml, String reference, String value, boolean header) {
        if (value.isEmpty()) {
            return;
        }
        xml.append("<c r=\"").append(reference).append("\" s=\"");
        if (header) {
            xml.append(HEADER).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(Ooxml.escape(value))
                    .append("</t></is></c>");
            return;
        }
        if (FORMULA.matcher(value).matches()) {
            xml.append(CELL).append("\"><f>").append(Ooxml.escape(value.substring(1))).append("</f></c>");
            return;
        }
        String number = number(value);
        if (number != null) {
            xml.append(CELL).append("\"><v>").append(number).append("</v></c>");
            return;
        }
        Long date = date(value);
        if (date != null) {
            xml.append(DATE_CELL).append("\"><v>").append(date).append("</v></c>");
            return;
        }
        xml.append(CELL).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(Ooxml.escape(value))
                .append("</t></is></c>");
    }

    /**
     * Число в записи Excel: «1 234,5» → «1234.5». Коды с нулём впереди
     * («00123») и длинные номера — не числа: Excel их испортил бы.
     */
    static String number(String value) {
        String compact = GROUPED.matcher(value).matches() ? value.replaceAll("[ \\u00A0\\u202F]", "") : value;
        if (!NUMBER.matcher(compact).matches()) {
            return null;
        }
        return compact.replace(',', '.');
    }

    /** Дата «ДД.ММ.ГГГГ» — номер дня Excel. */
    static Long date(String value) {
        Matcher matcher = DATE.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.of(Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(1)));
            return ChronoUnit.DAYS.between(EPOCH, date);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** Буквы колонки: 0 → A, 25 → Z, 26 → AA. */
    static String column(int index) {
        StringBuilder name = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) {
            name.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return name.toString();
    }

    private static String range(int rows, int columns) {
        return "A1:" + column(columns - 1) + rows;
    }

    private static String workbook(List<Sheet> sheets) {
        StringBuilder xml = new StringBuilder(Ooxml.XML).append("<workbook xmlns=\"").append(MAIN).append("\" xmlns:r=\"")
                .append(Ooxml.R).append("\"><sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            xml.append("<sheet name=\"").append(Ooxml.escape(sheets.get(i).name())).append("\" sheetId=\"").append(i + 1)
                    .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        xml.append("</sheets>");
        StringBuilder filters = new StringBuilder();
        for (int i = 0; i < sheets.size(); i++) {
            List<List<String>> rows = sheets.get(i).rows();
            int columns = rows.stream().mapToInt(List::size).max().orElse(0);
            if (rows.size() > 1 && columns > 0) {
                String range = range(rows.size(), columns).replaceAll("([A-Z]+)(\\d+)", "\\$$1\\$$2");
                filters.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i).append("\" hidden=\"1\">")
                        .append(Ooxml.escape("'" + sheets.get(i).name().replace("'", "''") + "'!" + range)).append("</definedName>");
            }
        }
        if (!filters.isEmpty()) {
            xml.append("<definedNames>").append(filters).append("</definedNames>");
        }
        return xml.append("</workbook>").toString();
    }

    private static String workbookRels(int sheets) {
        StringBuilder xml = new StringBuilder(Ooxml.XML).append("<Relationships xmlns=\"").append(Ooxml.RELATIONSHIPS).append("\">");
        for (int i = 1; i <= sheets; i++) {
            xml.append("<Relationship Id=\"rId").append(i).append("\" Type=\"").append(Ooxml.R)
                    .append("/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        xml.append("<Relationship Id=\"rIdStyles\" Type=\"").append(Ooxml.R).append("/styles\" Target=\"styles.xml\"/>");
        return xml.append("</Relationships>").toString();
    }

    private static String contentTypes(int sheets) {
        StringBuilder xml = new StringBuilder(Ooxml.XML).append("<Types xmlns=\"").append(Ooxml.CONTENT_TYPES).append("\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Override PartName=\"/xl/workbook.xml\"")
                .append(" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                .append("<Override PartName=\"/xl/styles.xml\"")
                .append(" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= sheets; i++) {
            xml.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return xml.append("</Types>").toString();
    }

    /** Стили: 0 — обычная ячейка, 1 — шапка, 2 — ячейка с рамкой, 3 — дата с рамкой. */
    private static final String STYLES = Ooxml.XML + "<styleSheet xmlns=\"" + MAIN + "\">"
            + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
            + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font></fonts>"
            + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDEE6F2\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "</fills>"
            + "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>"
            + "<border><left style=\"thin\"><color rgb=\"FFBFBFBF\"/></left><right style=\"thin\"><color rgb=\"FFBFBFBF\"/></right>"
            + "<top style=\"thin\"><color rgb=\"FFBFBFBF\"/></top><bottom style=\"thin\"><color rgb=\"FFBFBFBF\"/></bottom>"
            + "<diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"4\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"/>"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\"/>"
            + "<xf numFmtId=\"14\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\"/>"
            + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
            + "</styleSheet>";
}
