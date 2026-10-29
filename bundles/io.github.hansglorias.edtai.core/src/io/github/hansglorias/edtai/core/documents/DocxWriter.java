package io.github.hansglorias.edtai.core.documents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        writer.blocks(MarkdownBlocks.parse(markdown));
        return writer.pack();
    }

    // ---- блоки ----

    private void blocks(List<MarkdownBlocks.Block> blocks) {
        for (MarkdownBlocks.Block block : blocks) {
            if (block.type() != MarkdownBlocks.Type.ITEM) {
                currentNumbered = 0;
            }
            switch (block.type()) {
                case CODE -> code(block.lines());
                case TABLE -> table(block);
                case HEADING -> paragraph("<w:pStyle w:val=\"Heading" + Math.min(4, block.level()) + "\"/>", block.text(), false);
                case RULE -> body.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\""
                        + " w:color=\"A6A6A6\"/></w:pBdr></w:pPr></w:p>");
                case QUOTE -> paragraph("<w:pStyle w:val=\"Quote\"/>", block.text(), false);
                case ITEM -> listItem(block);
                case PARAGRAPH -> paragraph("", block.text(), false);
            }
        }
    }

    private void code(List<String> code) {
        for (int line = 0; line < code.size(); line++) {
            String spacing = line == 0 ? "<w:spacing w:before=\"60\"/>" : line == code.size() - 1 ? "<w:spacing w:after=\"160\"/>" : "";
            body.append("<w:p><w:pPr><w:pStyle w:val=\"Code\"/>").append(spacing).append("</w:pPr>");
            if (!code.get(line).isEmpty()) {
                body.append("<w:r>").append(text(code.get(line))).append("</w:r>");
            }
            body.append("</w:p>");
        }
    }

    /** Пункт списка: у каждого нумерованного списка своя нумерация. */
    private void listItem(MarkdownBlocks.Block item) {
        int numbering = BULLETS;
        if (item.numbered()) {
            if (currentNumbered == 0) {
                currentNumbered = BULLETS + ++numberedLists;
            }
            numbering = currentNumbered;
        }
        paragraph("<w:pStyle w:val=\"ListParagraph\"/><w:numPr><w:ilvl w:val=\"" + item.level() + "\"/><w:numId w:val=\""
                + numbering + "\"/></w:numPr>", item.text(), false);
    }

    private void table(MarkdownBlocks.Block table) {
        int columns = table.columns();
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
        for (int row = 0; row < table.rows().size(); row++) {
            boolean header = row == 0;
            body.append("<w:tr>").append(header ? "<w:trPr><w:tblHeader/></w:trPr>" : "");
            for (int column = 0; column < columns; column++) {
                body.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(width).append("\" w:type=\"dxa\"/>")
                        .append(header ? "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"DEE6F2\"/>" : "").append("</w:tcPr>");
                String align = column < table.align().size() ? table.align().get(column) : "left";
                String jc = align.equals("left") ? "" : "<w:jc w:val=\"" + align + "\"/>";
                paragraph("<w:spacing w:after=\"0\"/>" + jc, table.cell(row, column), header);
                body.append("</w:tc>");
            }
            body.append("</w:tr>");
        }
        body.append("</w:tbl><w:p/>");
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

    static String escape(String text) {
        return Ooxml.escape(text);
    }

    // ---- пакет ----

    private byte[] pack() {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", CONTENT_TYPES);
        parts.put("_rels/.rels", Ooxml.rootRels(R + "/officeDocument", "word/document.xml"));
        parts.put("word/document.xml", document());
        parts.put("word/styles.xml", STYLES);
        parts.put("word/numbering.xml", numbering());
        parts.put("word/_rels/document.xml.rels", documentRels());
        return Ooxml.zip(parts);
    }

    private static final String XML = Ooxml.XML;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = Ooxml.R;

    private static final String CONTENT_TYPES = XML
            + "<Types xmlns=\"" + Ooxml.CONTENT_TYPES + "\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/word/document.xml\""
            + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
            + "<Override PartName=\"/word/styles.xml\""
            + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
            + "<Override PartName=\"/word/numbering.xml\""
            + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>"
            + "</Types>";

    private String document() {
        return XML + "<w:document xmlns:w=\"" + W + "\" xmlns:r=\"" + R + "\"><w:body>" + body
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"567\" w:footer=\"567\""
                + " w:gutter=\"0\"/></w:sectPr></w:body></w:document>";
    }

    private String documentRels() {
        StringBuilder rels = new StringBuilder(XML)
                .append("<Relationships xmlns=\"" + Ooxml.RELATIONSHIPS + "\">")
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
