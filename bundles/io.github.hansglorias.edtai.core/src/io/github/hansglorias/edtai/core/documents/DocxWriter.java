package io.github.hansglorias.edtai.core.documents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.hansglorias.edtai.core.markdown.Kind;
import io.github.hansglorias.edtai.core.markdown.Markdown;
import io.github.hansglorias.edtai.core.markdown.RichText;
import io.github.hansglorias.edtai.core.markdown.Segment;

/**
 * Документ Word (.docx) из Markdown, которым пишет модель: заголовки,
 * абзацы, списки, таблицы, цитаты, блоки кода, жирный, курсив, ссылки.
 * Пишется сам: .docx — zip с несколькими XML, библиотека не нужна.
 * Страница A4, поля 2 см, шрифт Calibri 11.
 */
public final class DocxWriter {

    public static final String MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$");
    private static final Pattern RULE = Pattern.compile("^ {0,3}([-*_])(?:\\s*\\1){2,}\\s*$");
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}>\\s?(.*)$");
    private static final Pattern ITEM = Pattern.compile("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(?:\\|\\s*:?-+:?\\s*)*\\|?\\s*$");
    /** Ширина текста на A4 с полями 2 см, в двадцатых долях пункта. */
    private static final int TEXT_WIDTH = 9638;
    private static final int BULLETS = 1;

    private final StringBuilder body = new StringBuilder();
    private final List<String> links = new ArrayList<>();
    /** Нумерованные списки: у каждого своя нумерация с единицы. */
    private int numberedLists;
    /** Номер нумерации текущего нумерованного списка; 0 — списка нет. */
    private int currentNumbered;

    private DocxWriter() {
    }

    /** Документ из Markdown. */
    public static byte[] fromMarkdown(String markdown) {
        DocxWriter writer = new DocxWriter();
        writer.blocks((markdown == null ? "" : markdown).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        return writer.pack();
    }

    // ---- блоки ----

    private void blocks(String[] lines) {
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank()) {
                i++;
                continue;
            }
            Matcher item = ITEM.matcher(line);
            if (!item.matches()) {
                currentNumbered = 0;
            }
            Matcher matcher = FENCE.matcher(line);
            if (matcher.matches()) {
                i = code(lines, i, matcher.group(1));
            } else if (i + 1 < lines.length && line.contains("|") && TABLE_SEPARATOR.matcher(lines[i + 1]).matches()) {
                i = table(lines, i);
            } else if ((matcher = HEADING.matcher(line)).matches()) {
                int level = Math.min(4, matcher.group(1).length());
                paragraph("<w:pStyle w:val=\"Heading" + level + "\"/>", matcher.group(2), false);
                i++;
            } else if (RULE.matcher(line).matches()) {
                body.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"A6A6A6\"/>"
                        + "</w:pBdr></w:pPr></w:p>");
                i++;
            } else if (QUOTE.matcher(line).matches()) {
                StringBuilder text = new StringBuilder();
                while (i < lines.length && (matcher = QUOTE.matcher(lines[i])).matches()) {
                    text.append(text.isEmpty() ? "" : "\n").append(matcher.group(1));
                    i++;
                }
                paragraph("<w:pStyle w:val=\"Quote\"/>", text.toString(), false);
            } else if (item.matches()) {
                i = listItem(lines, i, item);
            } else {
                StringBuilder text = new StringBuilder(line.strip());
                i++;
                while (i < lines.length && !lines[i].isBlank() && !startsBlock(lines, i)) {
                    text.append('\n').append(lines[i].strip());
                    i++;
                }
                paragraph("", text.toString(), false);
            }
        }
    }

    private static boolean startsBlock(String[] lines, int i) {
        String line = lines[i];
        return FENCE.matcher(line).matches() || HEADING.matcher(line).matches() || RULE.matcher(line).matches()
                || QUOTE.matcher(line).matches() || ITEM.matcher(line).matches()
                || i + 1 < lines.length && line.contains("|") && TABLE_SEPARATOR.matcher(lines[i + 1]).matches();
    }

    private int code(String[] lines, int start, String fence) {
        int i = start + 1;
        List<String> code = new ArrayList<>();
        while (i < lines.length && !(lines[i].strip().startsWith(fence) && lines[i].strip().replace(fence.substring(0, 1), "").isEmpty())) {
            code.add(lines[i]);
            i++;
        }
        for (int line = 0; line < code.size(); line++) {
            String spacing = line == 0 ? "<w:spacing w:before=\"60\"/>" : line == code.size() - 1 ? "<w:spacing w:after=\"160\"/>" : "";
            body.append("<w:p><w:pPr><w:pStyle w:val=\"Code\"/>").append(spacing).append("</w:pPr>");
            if (!code.get(line).isEmpty()) {
                body.append("<w:r>").append(text(code.get(line))).append("</w:r>");
            }
            body.append("</w:p>");
        }
        return Math.min(lines.length, i + 1);
    }

    /** Пункт списка с продолжением на следующих строках с отступом. */
    private int listItem(String[] lines, int start, Matcher item) {
        int indent = item.group(1).replace("\t", "    ").length();
        int level = Math.min(2, indent / 2);
        boolean numbered = Character.isDigit(item.group(2).charAt(0));
        StringBuilder text = new StringBuilder(item.group(3).strip());
        int i = start + 1;
        while (i < lines.length && !lines[i].isBlank() && !startsBlock(lines, i)) {
            text.append('\n').append(lines[i].strip());
            i++;
        }
        int numbering = BULLETS;
        if (numbered) {
            if (currentNumbered == 0) {
                currentNumbered = BULLETS + ++numberedLists;
            }
            numbering = currentNumbered;
        }
        paragraph("<w:pStyle w:val=\"ListParagraph\"/><w:numPr><w:ilvl w:val=\"" + level + "\"/><w:numId w:val=\"" + numbering
                + "\"/></w:numPr>", text.toString(), false);
        return i;
    }

    private int table(String[] lines, int start) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(cells(lines[start]));
        List<String> align = new ArrayList<>();
        for (String cell : cells(lines[start + 1])) {
            String stripped = cell.strip();
            align.add(stripped.startsWith(":") && stripped.endsWith(":") ? "center" : stripped.endsWith(":") ? "right" : "");
        }
        int i = start + 2;
        while (i < lines.length && lines[i].contains("|") && !lines[i].isBlank()) {
            rows.add(cells(lines[i]));
            i++;
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        int width = TEXT_WIDTH / columns;
        body.append("<w:tbl><w:tblPr><w:tblW w:w=\"").append(width * columns).append("\" w:type=\"dxa\"/><w:tblBorders>");
        for (String side : List.of("top", "left", "bottom", "right", "insideH", "insideV")) {
            body.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"BFBFBF\"/>");
        }
        body.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/><w:tblCellMar><w:top w:w=\"40\" w:type=\"dxa\"/>"
                + "<w:left w:w=\"100\" w:type=\"dxa\"/><w:bottom w:w=\"40\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/>"
                + "</w:tblCellMar></w:tblPr><w:tblGrid>");
        for (int column = 0; column < columns; column++) {
            body.append("<w:gridCol w:w=\"").append(width).append("\"/>");
        }
        body.append("</w:tblGrid>");
        for (int row = 0; row < rows.size(); row++) {
            boolean header = row == 0;
            body.append("<w:tr>").append(header ? "<w:trPr><w:tblHeader/></w:trPr>" : "");
            for (int column = 0; column < columns; column++) {
                String cell = column < rows.get(row).size() ? rows.get(row).get(column) : "";
                body.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(width).append("\" w:type=\"dxa\"/>")
                        .append(header ? "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"DEE6F2\"/>" : "").append("</w:tcPr>");
                String jc = column < align.size() && !align.get(column).isEmpty() ? "<w:jc w:val=\"" + align.get(column) + "\"/>" : "";
                paragraph("<w:spacing w:after=\"0\"/>" + jc, cell, header);
                body.append("</w:tc>");
            }
            body.append("</w:tr>");
        }
        body.append("</w:tbl><w:p/>");
        return i;
    }

    private static List<String> cells(String line) {
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

    // ---- внутри абзаца ----

    /**
     * Абзац: свойства абзаца, текст с разметкой Markdown; {@code bold} —
     * весь жирный (шапка таблицы).
     */
    private void paragraph(String properties, String markdown, boolean bold) {
        body.append("<w:p>");
        if (!properties.isEmpty()) {
            body.append("<w:pPr>").append(properties).append("</w:pPr>");
        }
        RichText rich = Markdown.renderInline(markdown);
        String plain = rich.text();
        int at = 0;
        for (Segment segment : rich.segments()) {
            if (segment.start() > at) {
                run(plain.substring(at, segment.start()), bold ? "<w:b/>" : "");
            }
            String part = plain.substring(segment.start(), segment.start() + segment.length());
            boolean link = segment.link() != null && isUrl(segment.link());
            // Порядок элементов в w:rPr задан схемой: Word не открывает файл с другим порядком.
            String props = (link ? "<w:rStyle w:val=\"Hyperlink\"/>" : "")
                    + (segment.has(Kind.CODE) ? "<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>" : "")
                    + (bold || segment.has(Kind.BOLD) ? "<w:b/>" : "") + (segment.has(Kind.ITALIC) ? "<w:i/>" : "")
                    + (segment.has(Kind.STRIKE) ? "<w:strike/>" : "")
                    + (segment.has(Kind.CODE) ? "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"EFEFEF\"/>" : "");
            if (link) {
                links.add(segment.link());
                body.append("<w:hyperlink r:id=\"rIdLink").append(links.size()).append("\">");
                run(part, props);
                body.append("</w:hyperlink>");
            } else {
                run(part, props);
            }
            at = segment.start() + segment.length();
        }
        if (at < plain.length()) {
            run(plain.substring(at), bold ? "<w:b/>" : "");
        }
        body.append("</w:p>");
    }

    private static boolean isUrl(String link) {
        return link.matches("(?i)(https?|mailto|ftp):.*");
    }

    private void run(String text, String properties) {
        if (text.isEmpty()) {
            return;
        }
        body.append("<w:r>");
        if (!properties.isEmpty()) {
            body.append("<w:rPr>").append(properties).append("</w:rPr>");
        }
        body.append(text(text)).append("</w:r>");
    }

    /** Текст части абзаца: переводы строк — {@code <w:br/>}, табуляции — {@code <w:tab/>}. */
    private static String text(String text) {
        StringBuilder out = new StringBuilder();
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t') {
                flush(out, chunk);
                out.append(c == '\n' ? "<w:br/>" : "<w:tab/>");
            } else {
                chunk.append(c);
            }
        }
        flush(out, chunk);
        return out.toString();
    }

    private static void flush(StringBuilder out, StringBuilder chunk) {
        if (!chunk.isEmpty()) {
            out.append("<w:t xml:space=\"preserve\">").append(escape(chunk.toString())).append("</w:t>");
            chunk.setLength(0);
        }
    }

    /** Экранирование для XML; символы, недопустимые в XML 1.0, выбрасываются. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    boolean surrogatePair = Character.isHighSurrogate(c) && i + 1 < text.length()
                            && Character.isLowSurrogate(text.charAt(i + 1));
                    if (surrogatePair) {
                        out.append(c).append(text.charAt(++i));
                    } else if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20 && c < 0xD800 || c >= 0xE000 && c <= 0xFFFD) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    // ---- пакет ----

    private byte[] pack() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", CONTENT_TYPES);
            entry(zip, "_rels/.rels", ROOT_RELS);
            entry(zip, "word/document.xml", document());
            entry(zip, "word/styles.xml", STYLES);
            entry(zip, "word/numbering.xml", numbering());
            entry(zip, "word/_rels/document.xml.rels", documentRels());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static final String CONTENT_TYPES = XML
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/word/document.xml\""
            + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
            + "<Override PartName=\"/word/styles.xml\""
            + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
            + "<Override PartName=\"/word/numbering.xml\""
            + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>"
            + "</Types>";

    private static final String ROOT_RELS = XML
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"" + R + "/officeDocument\" Target=\"word/document.xml\"/>"
            + "</Relationships>";

    private String document() {
        return XML + "<w:document xmlns:w=\"" + W + "\" xmlns:r=\"" + R + "\"><w:body>" + body
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"567\" w:footer=\"567\""
                + " w:gutter=\"0\"/></w:sectPr></w:body></w:document>";
    }

    private String documentRels() {
        StringBuilder rels = new StringBuilder(XML)
                .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                .append("<Relationship Id=\"rIdStyles\" Type=\"").append(R).append("/styles\" Target=\"styles.xml\"/>")
                .append("<Relationship Id=\"rIdNumbering\" Type=\"").append(R).append("/numbering\" Target=\"numbering.xml\"/>");
        for (int i = 0; i < links.size(); i++) {
            rels.append("<Relationship Id=\"rIdLink").append(i + 1).append("\" Type=\"").append(R)
                    .append("/hyperlink\" Target=\"").append(escape(links.get(i))).append("\" TargetMode=\"External\"/>");
        }
        return rels.append("</Relationships>").toString();
    }

    /** Маркированный список — нумерация 1; у каждого нумерованного — своя, с единицы. */
    private String numbering() {
        StringBuilder xml = new StringBuilder(XML).append("<w:numbering xmlns:w=\"").append(W).append("\">");
        abstractNumbering(xml, 0, "bullet", new String[] { "•", "◦", "▪" });
        abstractNumbering(xml, 1, "decimal", new String[] { "%1.", "%2.", "%3." });
        xml.append("<w:num w:numId=\"").append(BULLETS).append("\"><w:abstractNumId w:val=\"0\"/></w:num>");
        for (int list = 1; list <= numberedLists; list++) {
            xml.append("<w:num w:numId=\"").append(BULLETS + list).append("\"><w:abstractNumId w:val=\"1\"/>");
            for (int level = 0; level < 3; level++) {
                xml.append("<w:lvlOverride w:ilvl=\"").append(level).append("\"><w:startOverride w:val=\"1\"/></w:lvlOverride>");
            }
            xml.append("</w:num>");
        }
        return xml.append("</w:numbering>").toString();
    }

    private static void abstractNumbering(StringBuilder xml, int id, String format, String[] texts) {
        xml.append("<w:abstractNum w:abstractNumId=\"").append(id).append("\"><w:multiLevelType w:val=\"hybridMultilevel\"/>");
        for (int level = 0; level < texts.length; level++) {
            xml.append("<w:lvl w:ilvl=\"").append(level).append("\"><w:start w:val=\"1\"/><w:numFmt w:val=\"").append(format)
                    .append("\"/><w:lvlText w:val=\"").append(texts[level]).append("\"/><w:lvlJc w:val=\"left\"/>")
                    .append("<w:pPr><w:ind w:left=\"").append(720 * (level + 1)).append("\" w:hanging=\"360\"/></w:pPr></w:lvl>");
        }
        xml.append("</w:abstractNum>");
    }

    private static final String STYLES = XML + "<w:styles xmlns:w=\"" + W + "\">"
            + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\""
            + " w:cs=\"Calibri\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"ru-RU\" w:eastAsia=\"en-US\""
            + " w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\""
            + " w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
            + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
            + heading(1, 32, "1F3864", 360) + heading(2, 26, "2F5496", 240) + heading(3, 24, "2F5496", 200)
            + heading(4, 22, "2F5496", 160)
            + "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/>"
            + "<w:basedOn w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"60\"/><w:contextualSpacing/></w:pPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/><w:basedOn w:val=\"Normal\"/>"
            + "<w:pPr><w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"8\" w:color=\"BFBFBF\"/></w:pBdr>"
            + "<w:ind w:left=\"340\"/></w:pPr><w:rPr><w:i/><w:color w:val=\"595959\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Code\"><w:name w:val=\"Code\"/><w:basedOn w:val=\"Normal\"/>"
            + "<w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F3F3F3\"/>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/><w:ind w:left=\"113\" w:right=\"113\"/></w:pPr>"
            + "<w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/><w:sz w:val=\"19\"/>"
            + "<w:szCs w:val=\"19\"/></w:rPr></w:style>"
            + "<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/>"
            + "<w:rPr><w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/></w:rPr></w:style>"
            + "</w:styles>";

    private static String heading(int level, int size, String color, int before) {
        return "<w:style w:type=\"paragraph\" w:styleId=\"Heading" + level + "\"><w:name w:val=\"heading " + level + "\"/>"
                + "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:keepLines/>"
                + "<w:spacing w:before=\"" + before + "\" w:after=\"120\"/><w:outlineLvl w:val=\"" + (level - 1) + "\"/></w:pPr>"
                + "<w:rPr><w:b/><w:color w:val=\"" + color + "\"/><w:sz w:val=\"" + size + "\"/><w:szCs w:val=\"" + size
                + "\"/></w:rPr></w:style>";
    }
}
