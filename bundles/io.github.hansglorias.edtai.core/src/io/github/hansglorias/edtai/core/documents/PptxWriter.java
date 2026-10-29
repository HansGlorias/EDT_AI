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
 * Презентация PowerPoint (.pptx) из Markdown: заголовок {@code #} или
 * {@code ##} (или разделитель {@code ---}) начинает слайд, под ним —
 * пункты, абзацы, код, таблица. Первый слайд только с заголовком и
 * короткой строкой — титульный. Слайды 16:9, текст в надписях без
 * заполнителей макета; размер шрифта — по объёму текста на слайде.
 */
public final class PptxWriter {

    public static final String MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String NAMESPACES = " xmlns:a=\"" + A + "\" xmlns:r=\"" + Ooxml.R + "\" xmlns:p=\"" + P + "\"";
    private static final String TYPES = "application/vnd.openxmlformats-officedocument.presentationml.";

    /** Размеры в EMU: 914400 на дюйм. */
    private static final long WIDTH = 12_192_000;
    private static final long HEIGHT = 6_858_000;
    private static final long MARGIN = 640_080;
    private static final long BODY_TOP = 1_463_040;
    private static final long PT = 12_700;
    private static final String TITLE_COLOR = "1F3864";
    private static final String ACCENT = "2E75B6";

    record Slide(String title, List<MarkdownBlocks.Block> body) {
    }

    private PptxWriter() {
    }

    public static byte[] fromMarkdown(String markdown) {
        List<Slide> slides = slides(markdown);
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", contentTypes(slides.size()));
        parts.put("_rels/.rels", Ooxml.rootRels(Ooxml.R + "/officeDocument", "ppt/presentation.xml"));
        parts.put("ppt/presentation.xml", presentation(slides.size()));
        parts.put("ppt/_rels/presentation.xml.rels", presentationRels(slides.size()));
        parts.put("ppt/presProps.xml", Ooxml.XML + "<p:presentationPr" + NAMESPACES + "/>");
        parts.put("ppt/viewProps.xml", Ooxml.XML + "<p:viewPr" + NAMESPACES + "><p:normalViewPr><p:restoredLeft sz=\"15620\"/>"
                + "<p:restoredTop sz=\"94660\"/></p:normalViewPr><p:gridSpacing cx=\"76200\" cy=\"76200\"/></p:viewPr>");
        parts.put("ppt/tableStyles.xml", Ooxml.XML + "<a:tblStyleLst xmlns:a=\"" + A
                + "\" def=\"{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}\"/>");
        parts.put("ppt/slideMasters/slideMaster1.xml", MASTER);
        parts.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", rels("slideLayout", "../slideLayouts/slideLayout1.xml", "theme",
                "../theme/theme1.xml"));
        parts.put("ppt/slideLayouts/slideLayout1.xml", LAYOUT);
        parts.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", rels("slideMaster", "../slideMasters/slideMaster1.xml"));
        parts.put("ppt/theme/theme1.xml", THEME);
        for (int i = 0; i < slides.size(); i++) {
            parts.put("ppt/slides/slide" + (i + 1) + ".xml", slide(slides.get(i), i == 0));
            parts.put("ppt/slides/_rels/slide" + (i + 1) + ".xml.rels", rels("slideLayout", "../slideLayouts/slideLayout1.xml"));
        }
        return Ooxml.zip(parts);
    }

    /** Слайды: заголовки 1–2 уровня и {@code ---} разделяют, текст до первого заголовка — слайд без заголовка. */
    static List<Slide> slides(String markdown) {
        List<Slide> slides = new ArrayList<>();
        String title = null;
        List<MarkdownBlocks.Block> body = new ArrayList<>();
        boolean started = false;
        for (MarkdownBlocks.Block block : MarkdownBlocks.parse(markdown)) {
            boolean heading = block.type() == MarkdownBlocks.Type.HEADING && block.level() <= 2;
            if (heading || block.type() == MarkdownBlocks.Type.RULE) {
                if (started && (title != null || !body.isEmpty())) {
                    slides.add(new Slide(title, body));
                }
                title = heading ? block.text() : null;
                body = new ArrayList<>();
                started = true;
            } else {
                body.add(block);
                started = true;
            }
        }
        if (title != null || !body.isEmpty() || slides.isEmpty()) {
            slides.add(new Slide(title, body));
        }
        return slides;
    }

    // ---- слайд ----

    private static String slide(Slide slide, boolean first) {
        Shapes shapes = new Shapes();
        boolean titleSlide = first && slide.title() != null && slide.body().size() <= 1
                && slide.body().stream().allMatch(block -> block.type() == MarkdownBlocks.Type.PARAGRAPH && block.text().length() < 160);
        if (titleSlide) {
            shapes.text(MARGIN, 2_286_000, WIDTH - 2 * MARGIN, 1_371_600, "b",
                    paragraph(slide.title(), 4400, true, TITLE_COLOR, "ctr", "<a:buNone/>", 0, false));
            shapes.bar((WIDTH - 1_828_800) / 2, 3_749_040, 1_828_800);
            if (!slide.body().isEmpty()) {
                shapes.text(MARGIN, 3_931_920, WIDTH - 2 * MARGIN, 1_097_280, "t",
                        paragraph(slide.body().get(0).text(), 2400, false, "595959", "ctr", "<a:buNone/>", 0, false));
            }
            return slideXml(shapes);
        }
        long top = MARGIN;
        if (slide.title() != null) {
            shapes.text(MARGIN, 365_760, WIDTH - 2 * MARGIN, 868_680, "b",
                    paragraph(slide.title(), 3200, true, TITLE_COLOR, "l", "<a:buNone/>", 0, false));
            shapes.bar(MARGIN, 1_252_728, 1_371_600);
            top = BODY_TOP;
        }
        int size = fontSize(slide.body());
        StringBuilder text = new StringBuilder();
        for (MarkdownBlocks.Block block : slide.body()) {
            if (block.type() == MarkdownBlocks.Type.TABLE) {
                top = flush(shapes, text, top, size);
                top = shapes.table(block, top, Math.min(size, 1600));
                continue;
            }
            switch (block.type()) {
                case ITEM -> text.append(paragraph(block.text(), size, false, null, "l",
                        block.numbered() ? "<a:buFont typeface=\"+mj-lt\"/><a:buAutoNum type=\"arabicPeriod\"/>"
                                : "<a:buFont typeface=\"Arial\"/><a:buChar char=\"" + (block.level() == 0 ? "•" : "–") + "\"/>",
                        block.level(), false));
                case HEADING -> text.append(paragraph(block.text(), size, true, ACCENT, "l", "<a:buNone/>", 0, false));
                case QUOTE -> text.append(paragraph(block.text(), size, false, "595959", "l", "<a:buNone/>", 0, false)
                        .replace("<a:rPr ", "<a:rPr i=\"1\" "));
                case CODE -> {
                    for (String line : block.lines()) {
                        text.append(paragraph(line.isEmpty() ? " " : line, size - 400, false, "404040", "l", "<a:buNone/>", 0,
                                true));
                    }
                }
                default -> text.append(paragraph(block.text(), size, false, null, "l", "<a:buNone/>", 0, false));
            }
        }
        flush(shapes, text, top, size);
        return slideXml(shapes);
    }

    /** Накопленные абзацы — надписью от {@code top}; возвращает, где она кончается. */
    private static long flush(Shapes shapes, StringBuilder text, long top, int size) {
        if (text.isEmpty()) {
            return top;
        }
        int paragraphs = text.toString().split("<a:p>", -1).length - 1;
        long height = Math.min(HEIGHT - MARGIN - top, Math.max(457_200, paragraphs * size / 100L * PT * 17 / 10));
        shapes.text(MARGIN, top, WIDTH - 2 * MARGIN, height, "t", text.toString());
        text.setLength(0);
        return top + height + 91_440;
    }

    /** Шрифт по объёму: строк с учётом переноса, сотые доли пункта. */
    private static int fontSize(List<MarkdownBlocks.Block> body) {
        int lines = 0;
        for (MarkdownBlocks.Block block : body) {
            lines += switch (block.type()) {
                case CODE -> block.lines().size();
                case TABLE -> block.rows().size() + 1;
                default -> 1 + block.text().length() / 90;
            };
        }
        return lines <= 6 ? 2400 : lines <= 9 ? 2000 : lines <= 13 ? 1800 : lines <= 18 ? 1600 : 1400;
    }

    /** Абзац с разметкой внутри строки: жирный, курсив, код, ссылки (текстом). */
    private static String paragraph(String markdown, int size, boolean bold, String color, String align, String bullet, int level,
            boolean code) {
        StringBuilder xml = new StringBuilder("<a:p><a:pPr");
        if (bullet.contains("buChar") || bullet.contains("buAutoNum")) {
            long indent = 342_900;
            xml.append(" marL=\"").append(indent * (level + 1)).append("\" lvl=\"").append(level).append("\" indent=\"-")
                    .append(indent).append('"');
        } else {
            xml.append(" marL=\"0\" indent=\"0\"");
        }
        xml.append(" algn=\"").append(align).append("\"><a:spcBef><a:spcPts val=\"").append(code ? 0 : size / 4)
                .append("\"/></a:spcBef>").append(bullet).append("</a:pPr>");
        RichText rich = code ? null : Markdown.renderInline(markdown);
        String plain = code ? markdown : rich.text();
        List<Segment> segments = code ? List.of() : rich.segments();
        int at = 0;
        for (Segment segment : segments) {
            if (segment.start() > at) {
                run(xml, plain.substring(at, segment.start()), size, bold, false, color, code);
            }
            run(xml, plain.substring(segment.start(), segment.start() + segment.length()), size,
                    bold || segment.has(Kind.BOLD), segment.has(Kind.ITALIC), segment.link() != null ? "0563C1" : color,
                    code || segment.has(Kind.CODE));
            at = segment.start() + segment.length();
        }
        if (at < plain.length() || plain.isEmpty()) {
            run(xml, plain.substring(at), size, bold, false, color, code);
        }
        return xml.append("</a:p>").toString();
    }

    private static void run(StringBuilder xml, String text, int size, boolean bold, boolean italic, String color, boolean code) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                xml.append("<a:br><a:rPr lang=\"ru-RU\" sz=\"").append(size).append("\"/></a:br>");
            }
            if (lines[i].isEmpty() && lines.length > 1) {
                continue;
            }
            // Порядок в a:rPr задан схемой: заливка, затем шрифт.
            xml.append("<a:r><a:rPr lang=\"ru-RU\" sz=\"").append(size).append('"').append(bold ? " b=\"1\"" : "")
                    .append(italic ? " i=\"1\"" : "").append(" dirty=\"0\">");
            if (color != null) {
                xml.append("<a:solidFill><a:srgbClr val=\"").append(color).append("\"/></a:solidFill>");
            }
            // Шрифт явно: иначе LibreOffice берёт его у предыдущего абзаца, например у кода.
            String font = code ? "Consolas" : "+mn-lt";
            xml.append("<a:latin typeface=\"").append(font).append("\"/><a:cs typeface=\"").append(font).append("\"/>");
            xml.append("</a:rPr><a:t>").append(Ooxml.escape(lines[i])).append("</a:t></a:r>");
        }
    }

    /** Фигуры слайда с номерами по порядку. */
    private static final class Shapes {
        private final StringBuilder xml = new StringBuilder();
        private int id = 1;

        void text(long x, long y, long cx, long cy, String anchor, String paragraphs) {
            int shape = ++id;
            xml.append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(shape).append("\" name=\"Текст ").append(shape)
                    .append("\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr>").append(xfrm(x, y, cx, cy))
                    .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap=\"square\""
                            + " lIns=\"0\" rIns=\"0\" rtlCol=\"0\" anchor=\"")
                    .append(anchor).append("\"><a:normAutofit/></a:bodyPr><a:lstStyle/>").append(paragraphs)
                    .append("</p:txBody></p:sp>");
        }

        /** Полоса цвета акцента под заголовком. */
        void bar(long x, long y, long cx) {
            int shape = ++id;
            xml.append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(shape).append("\" name=\"Полоса ").append(shape)
                    .append("\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>").append(xfrm(x, y, cx, 45_720))
                    .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val=\"").append(ACCENT)
                    .append("\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr></p:sp>");
        }

        /** Таблица с шапкой; возвращает, где она кончается. */
        long table(MarkdownBlocks.Block table, long top, int size) {
            int columns = table.columns();
            long width = WIDTH - 2 * MARGIN;
            long column = width / columns;
            long rowHeight = size / 100L * PT * 2;
            long height = rowHeight * table.rows().size();
            int shape = ++id;
            xml.append("<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"").append(shape).append("\" name=\"Таблица ").append(shape)
                    .append("\"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/>")
                    .append("</p:nvGraphicFramePr><p:xfrm><a:off x=\"").append(MARGIN).append("\" y=\"").append(top)
                    .append("\"/><a:ext cx=\"").append(column * columns).append("\" cy=\"").append(height).append("\"/></p:xfrm>")
                    .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\">")
                    .append("<a:tbl><a:tblPr firstRow=\"1\" bandRow=\"1\"/><a:tblGrid>");
            for (int i = 0; i < columns; i++) {
                xml.append("<a:gridCol w=\"").append(column).append("\"/>");
            }
            xml.append("</a:tblGrid>");
            for (int row = 0; row < table.rows().size(); row++) {
                boolean header = row == 0;
                xml.append("<a:tr h=\"").append(rowHeight).append("\">");
                for (int i = 0; i < columns; i++) {
                    String align = i < table.align().size() ? table.align().get(i) : "left";
                    String algn = align.equals("center") ? "ctr" : align.equals("right") ? "r" : "l";
                    String cell = paragraph(table.cell(row, i), size, header, null, algn, "<a:buNone/>", 0, false)
                            .replace("<a:spcBef><a:spcPts val=\"" + size / 4 + "\"/></a:spcBef>", "");
                    xml.append("<a:tc><a:txBody><a:bodyPr/><a:lstStyle/>").append(cell).append("</a:txBody><a:tcPr>");
                    for (String side : List.of("lnL", "lnR", "lnT", "lnB")) {
                        xml.append("<a:").append(side).append(" w=\"9525\"><a:solidFill><a:srgbClr val=\"BFBFBF\"/></a:solidFill></a:")
                                .append(side).append('>');
                    }
                    xml.append(header ? "<a:solidFill><a:srgbClr val=\"DEE6F2\"/></a:solidFill>" : "<a:noFill/>")
                            .append("</a:tcPr></a:tc>");
                }
                xml.append("</a:tr>");
            }
            xml.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>");
            return top + height + 182_880;
        }

        private static String xfrm(long x, long y, long cx, long cy) {
            return "<a:xfrm><a:off x=\"" + x + "\" y=\"" + y + "\"/><a:ext cx=\"" + cx + "\" cy=\"" + Math.max(1, cy) + "\"/></a:xfrm>";
        }
    }

    private static String slideXml(Shapes shapes) {
        return Ooxml.XML + "<p:sld" + NAMESPACES + "><p:cSld><p:spTree>" + GROUP + shapes.xml
                + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>";
    }

    // ---- части пакета ----

    private static final String GROUP = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
            + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/>"
            + "<a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>";

    private static String presentation(int slides) {
        StringBuilder xml = new StringBuilder(Ooxml.XML).append("<p:presentation").append(NAMESPACES)
                .append(" saveSubsetFonts=\"1\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rIdMaster\"/>")
                .append("</p:sldMasterIdLst><p:sldIdLst>");
        for (int i = 1; i <= slides; i++) {
            xml.append("<p:sldId id=\"").append(255 + i).append("\" r:id=\"rIdSlide").append(i).append("\"/>");
        }
        return xml.append("</p:sldIdLst><p:sldSz cx=\"").append(WIDTH).append("\" cy=\"").append(HEIGHT)
                .append("\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/><p:defaultTextStyle><a:defPPr><a:defRPr lang=\"ru-RU\"/>")
                .append("</a:defPPr></p:defaultTextStyle></p:presentation>").toString();
    }

    private static String presentationRels(int slides) {
        StringBuilder xml = new StringBuilder(Ooxml.XML).append("<Relationships xmlns=\"").append(Ooxml.RELATIONSHIPS).append("\">")
                .append(relationship("rIdMaster", "slideMaster", "slideMasters/slideMaster1.xml"))
                .append(relationship("rIdTheme", "theme", "theme/theme1.xml"))
                .append(relationship("rIdPresProps", "presProps", "presProps.xml"))
                .append(relationship("rIdViewProps", "viewProps", "viewProps.xml"))
                .append(relationship("rIdTableStyles", "tableStyles", "tableStyles.xml"));
        for (int i = 1; i <= slides; i++) {
            xml.append(relationship("rIdSlide" + i, "slide", "slides/slide" + i + ".xml"));
        }
        return xml.append("</Relationships>").toString();
    }

    /** Связи части: пары «тип, цель». */
    private static String rels(String... typesAndTargets) {
        StringBuilder xml = new StringBuilder(Ooxml.XML).append("<Relationships xmlns=\"").append(Ooxml.RELATIONSHIPS).append("\">");
        for (int i = 0; i < typesAndTargets.length; i += 2) {
            xml.append(relationship("rId" + (i / 2 + 1), typesAndTargets[i], typesAndTargets[i + 1]));
        }
        return xml.append("</Relationships>").toString();
    }

    private static String relationship(String id, String type, String target) {
        return "<Relationship Id=\"" + id + "\" Type=\"" + Ooxml.R + "/" + type + "\" Target=\"" + target + "\"/>";
    }

    private static String contentTypes(int slides) {
        StringBuilder xml = new StringBuilder(Ooxml.XML).append("<Types xmlns=\"").append(Ooxml.CONTENT_TYPES).append("\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append(override("/ppt/presentation.xml", TYPES + "presentation.main+xml"))
                .append(override("/ppt/presProps.xml", TYPES + "presProps+xml"))
                .append(override("/ppt/viewProps.xml", TYPES + "viewProps+xml"))
                .append(override("/ppt/tableStyles.xml", TYPES + "tableStyles+xml"))
                .append(override("/ppt/slideMasters/slideMaster1.xml", TYPES + "slideMaster+xml"))
                .append(override("/ppt/slideLayouts/slideLayout1.xml", TYPES + "slideLayout+xml"))
                .append(override("/ppt/theme/theme1.xml", "application/vnd.openxmlformats-officedocument.theme+xml"));
        for (int i = 1; i <= slides; i++) {
            xml.append(override("/ppt/slides/slide" + i + ".xml", TYPES + "slide+xml"));
        }
        return xml.append("</Types>").toString();
    }

    private static String override(String part, String type) {
        return "<Override PartName=\"" + part + "\" ContentType=\"" + type + "\"/>";
    }

    private static final String MASTER = Ooxml.XML + "<p:sldMaster" + NAMESPACES + "><p:cSld><p:bg><p:bgRef idx=\"1001\">"
            + "<a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>" + GROUP + "</p:spTree></p:cSld>"
            + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\""
            + " accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"
            + "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>"
            + "<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz=\"3200\"/></a:lvl1pPr></p:titleStyle>"
            + "<p:bodyStyle><a:lvl1pPr><a:defRPr sz=\"2000\"/></a:lvl1pPr></p:bodyStyle>"
            + "<p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>";

    private static final String LAYOUT = Ooxml.XML + "<p:sldLayout" + NAMESPACES + " type=\"blank\" preserve=\"1\">"
            + "<p:cSld name=\"Пустой\"><p:spTree>" + GROUP + "</p:spTree></p:cSld>"
            + "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>";

    private static String solid(String color) {
        return "<a:solidFill><a:schemeClr val=\"" + color + "\"/></a:solidFill>";
    }

    private static String line(int width) {
        return "<a:ln w=\"" + width + "\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\">" + solid("phClr")
                + "<a:prstDash val=\"solid\"/><a:miter lim=\"800000\"/></a:ln>";
    }

    /** Тема: цвета и шрифты Office; без неё PowerPoint файл не откроет. */
    private static final String THEME = Ooxml.XML + "<a:theme xmlns:a=\"" + A + "\" name=\"EDT AI\"><a:themeElements>"
            + "<a:clrScheme name=\"EDT AI\"><a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>"
            + "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1><a:dk2><a:srgbClr val=\"1F3864\"/></a:dk2>"
            + "<a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2><a:accent1><a:srgbClr val=\"2E75B6\"/></a:accent1>"
            + "<a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2><a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3>"
            + "<a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4><a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5>"
            + "<a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6><a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink>"
            + "<a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>"
            + "<a:fontScheme name=\"EDT AI\"><a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/>"
            + "<a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/>"
            + "<a:cs typeface=\"\"/></a:minorFont></a:fontScheme>"
            + "<a:fmtScheme name=\"EDT AI\"><a:fillStyleLst>" + solid("phClr") + solid("phClr") + solid("phClr") + "</a:fillStyleLst>"
            + "<a:lnStyleLst>" + line(6350) + line(12700) + line(19050) + "</a:lnStyleLst>"
            + "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle>"
            + "<a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>"
            + "<a:bgFillStyleLst>" + solid("phClr") + solid("phClr") + solid("phClr") + "</a:bgFillStyleLst>"
            + "</a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>";
}
