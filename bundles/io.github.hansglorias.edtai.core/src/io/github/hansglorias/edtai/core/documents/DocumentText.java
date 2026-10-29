package io.github.hansglorias.edtai.core.documents;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Текст документа, приложенного к вопросу: модель читает текст, а не
 * двоичный файл. Word (.docx), Excel (.xlsx), PowerPoint (.pptx),
 * LibreOffice (.odt, .ods, .odp) и PDF — без сторонних библиотек: пакеты
 * Office — zip из XML, PDF разбирает {@link PdfText}. Заголовки и таблицы
 * документа — в Markdown, листы таблиц — строками через табуляцию.
 */
public final class DocumentText {

    /** Больше текста модели не отдаём: дальше обрезаем с пометкой. */
    public static final int MAX_CHARS = 200_000;

    /** Распакованный пакет больше — не документ, а что-то не то (или zip-бомба). */
    private static final long MAX_UNPACKED = 100_000_000;
    private static final int MAX_EMPTY_REPEAT = 1_000;

    private static final Set<String> OLD_OFFICE = Set.of("doc", "xls", "ppt");
    private static final Pattern CELL = Pattern.compile("([A-Z]+)(\\d+)");
    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)(?:heading|заголовок)\\s*(\\d)");

    private DocumentText() {
    }

    /** Документ по расширению: текст из него берёт {@link #extract} (у старых .doc, .xls, .ppt — подсказка пересохранить). */
    public static boolean isDocument(String name) {
        return switch (extension(name)) {
            case "docx", "docm", "dotx", "xlsx", "xlsm", "xltx", "pptx", "pptm", "odt", "ods", "odp", "pdf", "doc", "xls", "ppt" -> true;
            default -> false;
        };
    }

    /**
     * Текст документа; больше {@link #MAX_CHARS} — обрезан с пометкой в конце.
     *
     * @throws IllegalArgumentException с текстом для пользователя: формат не тот, файл повреждён, текста нет
     */
    public static String extract(String name, byte[] bytes) {
        String extension = extension(name);
        if (OLD_OFFICE.contains(extension)) {
            throw new IllegalArgumentException("«" + name + "» — старый формат Office: сохраните как ."
                    + (extension.equals("xls") ? "xlsx" : extension + "x") + " и приложите снова");
        }
        if (!isDocument(name)) {
            throw new IllegalArgumentException("«" + name + "» — не документ");
        }
        String text;
        try {
            text = switch (extension) {
                case "docx", "docm", "dotx" -> docx(unzip(bytes));
                case "xlsx", "xlsm", "xltx" -> xlsx(unzip(bytes));
                case "pptx", "pptm" -> pptx(unzip(bytes));
                case "odt", "ods", "odp" -> odf(unzip(bytes));
                default -> PdfText.extract(bytes);
            };
        } catch (IOException | SAXException | RuntimeException e) {
            // IOException — с понятной причиной («зашифрован паролем»); остальное — файл не того формата.
            String reason = e instanceof IOException && e.getMessage() != null ? e.getMessage()
                    : "файл повреждён или это не " + extension.toUpperCase(Locale.ROOT);
            throw new IllegalArgumentException("«" + name + "» не прочитать: " + reason, e);
        }
        text = text.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("В «" + name + "» нет текста" + (extension.equals("pdf")
                    ? " (скан?): приложите страницы картинками" : ""));
        }
        return truncate(text);
    }

    static String truncate(String text) {
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        int cut = text.lastIndexOf('\n', MAX_CHARS);
        cut = cut < MAX_CHARS / 2 ? MAX_CHARS : cut;
        return text.substring(0, cut) + "\n\n[… дальше не поместилось: показаны первые " + cut / 1000 + " тыс. символов из "
                + text.length() / 1000 + " тыс.]";
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ---- пакет ----

    static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().endsWith(".xml") && !entry.getName().endsWith(".rels")) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_UNPACKED) {
                        throw new IOException("распакованный файл больше " + MAX_UNPACKED / 1_000_000 + " МБ");
                    }
                    out.write(buffer, 0, read);
                }
                parts.put(entry.getName().startsWith("/") ? entry.getName().substring(1) : entry.getName(), out.toByteArray());
            }
        }
        if (parts.isEmpty()) {
            throw new IOException("не zip-пакет");
        }
        return parts;
    }

    private static Element xml(Map<String, byte[]> parts, String name) throws IOException, SAXException {
        byte[] part = parts.get(name);
        if (part == null) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            try (InputStream in = new ByteArrayInputStream(part)) {
                return factory.newDocumentBuilder().parse(in).getDocumentElement();
            }
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Связи части: Id → путь в пакете. */
    private static Map<String, String> relationships(Map<String, byte[]> parts, String part) throws IOException, SAXException {
        int slash = part.lastIndexOf('/');
        String folder = slash < 0 ? "" : part.substring(0, slash + 1);
        Element rels = xml(parts, folder + "_rels/" + part.substring(slash + 1) + ".rels");
        Map<String, String> targets = new HashMap<>();
        if (rels != null) {
            for (Element rel : children(rels, "Relationship")) {
                String target = rel.getAttribute("Target");
                targets.put(rel.getAttribute("Id"), target.startsWith("/") ? target.substring(1) : normalize(folder + target));
            }
        }
        return targets;
    }

    private static String normalize(String path) {
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                if (!segments.isEmpty()) {
                    segments.remove(segments.size() - 1);
                }
            } else if (!segment.isEmpty() && !segment.equals(".")) {
                segments.add(segment);
            }
        }
        return String.join("/", segments);
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent == null ? null : parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    private static Element child(Element parent, String localName) {
        List<Element> found = children(parent, localName);
        return found.isEmpty() ? null : found.get(0);
    }

    private static List<Element> descendants(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        if (parent != null) {
            org.w3c.dom.NodeList nodes = parent.getElementsByTagNameNS("*", localName);
            for (int i = 0; i < nodes.getLength(); i++) {
                result.add((Element) nodes.item(i));
            }
        }
        return result;
    }

    private static String attribute(Element element, String localName) {
        if (element == null) {
            return "";
        }
        org.w3c.dom.NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String name = attribute.getLocalName() != null ? attribute.getLocalName() : attribute.getNodeName();
            if (localName.equals(name)) {
                return attribute.getNodeValue();
            }
        }
        return "";
    }

    // ---- Word ----

    private static String docx(Map<String, byte[]> parts) throws IOException, SAXException {
        Element document = xml(parts, "word/document.xml");
        if (document == null) {
            throw new IOException("нет word/document.xml");
        }
        StringBuilder out = new StringBuilder();
        docxBlocks(child(document, "body"), out);
        return out.toString();
    }

    private static void docxBlocks(Element parent, StringBuilder out) {
        for (Node node = parent == null ? null : parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element)) {
                continue;
            }
            switch (element.getLocalName()) {
                case "p" -> {
                    String text = docxRuns(element).strip();
                    if (!text.isEmpty()) {
                        String prefix = docxPrefix(element);
                        if (prefix.equals("- ") && out.length() >= 2 && out.charAt(out.length() - 1) == '\n'
                                && out.charAt(out.length() - 2) == '\n' && listItemBefore(out)) {
                            out.setLength(out.length() - 1);
                        }
                        out.append(prefix).append(text).append("\n\n");
                    }
                }
                case "tbl" -> {
                    List<List<String>> rows = new ArrayList<>();
                    for (Element row : children(element, "tr")) {
                        List<String> cells = new ArrayList<>();
                        for (Element cell : children(row, "tc")) {
                            StringBuilder text = new StringBuilder();
                            for (Element paragraph : descendants(cell, "p")) {
                                String line = docxRuns(paragraph).strip();
                                if (!line.isEmpty()) {
                                    text.append(text.isEmpty() ? "" : " ").append(line);
                                }
                            }
                            cells.add(text.toString());
                        }
                        rows.add(cells);
                    }
                    markdownTable(rows, out);
                }
                case "sdt" -> docxBlocks(child(element, "sdtContent"), out);
                default -> {
                    // разметка раздела, закладки — без текста
                }
            }
        }
    }

    /** Предыдущий абзац — пункт списка: пункты идут без пустой строки. */
    private static boolean listItemBefore(StringBuilder out) {
        int start = out.lastIndexOf("\n", out.length() - 3) + 1;
        return out.indexOf("- ", start) == start;
    }

    private static String docxPrefix(Element paragraph) {
        Element properties = child(paragraph, "pPr");
        String style = attribute(child(properties, "pStyle"), "val");
        Matcher heading = HEADING_STYLE.matcher(style);
        if (heading.find()) {
            return "#".repeat(Math.max(1, Math.min(6, Integer.parseInt(heading.group(1))))) + " ";
        }
        if (style.equalsIgnoreCase("Title")) {
            return "# ";
        }
        return child(properties, "numPr") != null ? "- " : "";
    }

    /** Текст абзаца Word: прогоны, табуляции, переносы; удалённое в режиме правок — нет. */
    private static String docxRuns(Element paragraph) {
        StringBuilder text = new StringBuilder();
        collectDocx(paragraph, text);
        return text.toString();
    }

    private static void collectDocx(Element element, StringBuilder text) {
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                switch (child.getLocalName()) {
                    case "t" -> text.append(child.getTextContent());
                    case "tab" -> text.append('\t');
                    case "br", "cr" -> text.append('\n');
                    case "noBreakHyphen" -> text.append('-');
                    case "del", "delText", "pPr", "rPr", "instrText", "fldData" -> {
                        // не текст документа
                    }
                    default -> collectDocx(child, text);
                }
            }
        }
    }

    private static void markdownTable(List<List<String>> rows, StringBuilder out) {
        rows.removeIf(row -> row.stream().allMatch(String::isBlank));
        if (rows.isEmpty()) {
            return;
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        for (int i = 0; i < rows.size(); i++) {
            out.append('|');
            for (int column = 0; column < columns; column++) {
                String cell = column < rows.get(i).size() ? rows.get(i).get(column) : "";
                out.append(' ').append(cell.replace("|", "\\|").replace('\n', ' ')).append(" |");
            }
            out.append('\n');
            if (i == 0) {
                out.append('|').append(" --- |".repeat(columns)).append('\n');
            }
        }
        out.append('\n');
    }

    // ---- Excel ----

    private static String xlsx(Map<String, byte[]> parts) throws IOException, SAXException {
        Element workbook = xml(parts, "xl/workbook.xml");
        if (workbook == null) {
            throw new IOException("нет xl/workbook.xml");
        }
        Map<String, String> targets = relationships(parts, "xl/workbook.xml");
        List<String> strings = new ArrayList<>();
        Element shared = xml(parts, "xl/sharedStrings.xml");
        for (Element item : children(shared, "si")) {
            StringBuilder text = new StringBuilder();
            for (Element t : descendants(item, "t")) {
                if (!"rPh".equals(t.getParentNode().getLocalName())) {
                    text.append(t.getTextContent());
                }
            }
            strings.add(text.toString());
        }
        List<Boolean> dates = dateStyles(xml(parts, "xl/styles.xml"));
        StringBuilder out = new StringBuilder();
        for (Element sheet : children(child(workbook, "sheets"), "sheet")) {
            String target = targets.get(sheet.getAttributeNS(Ooxml.R, "id"));
            Element data = child(target == null ? null : xml(parts, target), "sheetData");
            StringBuilder rows = new StringBuilder();
            for (Element row : children(data, "row")) {
                List<String> cells = new ArrayList<>();
                for (Element cell : children(row, "c")) {
                    Matcher reference = CELL.matcher(attribute(cell, "r"));
                    int column = reference.matches() ? column(reference.group(1)) : cells.size();
                    while (cells.size() < column) {
                        cells.add("");
                    }
                    String style = attribute(cell, "s");
                    boolean date = !style.isEmpty() && Integer.parseInt(style) < dates.size() && dates.get(Integer.parseInt(style));
                    cells.add(xlsxValue(cell, strings, date));
                }
                tsvRow(cells, rows);
            }
            if (!rows.isEmpty()) {
                out.append("## Лист «").append(attribute(sheet, "name")).append("»\n\n").append(rows).append('\n');
            }
        }
        return out.toString();
    }

    private static int column(String letters) {
        int column = 0;
        for (int i = 0; i < letters.length(); i++) {
            column = column * 26 + letters.charAt(i) - 'A' + 1;
        }
        return column - 1;
    }

    private static String xlsxValue(Element cell, List<String> strings, boolean date) {
        String type = attribute(cell, "t");
        Element value = child(cell, "v");
        String raw = value == null ? "" : value.getTextContent();
        switch (type) {
            case "s" -> {
                int index = raw.isEmpty() ? -1 : Integer.parseInt(raw.strip());
                return index >= 0 && index < strings.size() ? strings.get(index) : "";
            }
            case "inlineStr" -> {
                StringBuilder text = new StringBuilder();
                for (Element t : descendants(child(cell, "is"), "t")) {
                    text.append(t.getTextContent());
                }
                return text.toString();
            }
            case "b" -> {
                return "1".equals(raw) ? "ИСТИНА" : "ЛОЖЬ";
            }
            case "", "n" -> {
                return raw.isEmpty() ? "" : date ? serialDate(raw) : number(raw);
            }
            default -> {
                return raw;
            }
        }
    }

    /** Стили ячеек (cellXfs) с форматом даты. */
    private static List<Boolean> dateStyles(Element styles) {
        Map<String, String> formats = new HashMap<>();
        for (Element format : children(child(styles, "numFmts"), "numFmt")) {
            formats.put(attribute(format, "numFmtId"), attribute(format, "formatCode"));
        }
        List<Boolean> dates = new ArrayList<>();
        for (Element xf : children(child(styles, "cellXfs"), "xf")) {
            String id = attribute(xf, "numFmtId");
            int builtIn = id.isEmpty() ? 0 : Integer.parseInt(id);
            String code = formats.get(id);
            dates.add(code != null ? isDateFormat(code) : builtIn >= 14 && builtIn <= 22 || builtIn >= 45 && builtIn <= 47);
        }
        return dates;
    }

    static boolean isDateFormat(String code) {
        String plain = code.replaceAll("\"[^\"]*\"|\\[[^\\]]*\\]|\\\\.", "").toLowerCase(Locale.ROOT);
        return plain.contains("d") || plain.contains("y") || plain.contains("h") && plain.contains("m");
    }

    /** Дата Excel — дни от 30.12.1899: «2026-10-29» или «2026-10-29 14:30». */
    static String serialDate(String raw) {
        try {
            double serial = Double.parseDouble(raw);
            long days = (long) Math.floor(serial);
            long seconds = Math.round((serial - days) * 86_400);
            LocalDateTime time = LocalDate.of(1899, 12, 30).plusDays(days).atStartOfDay().plusSeconds(seconds);
            if (seconds == 0) {
                return time.toLocalDate().toString();
            }
            return days == 0 ? time.toLocalTime().toString() : time.toLocalDate() + " " + time.toLocalTime();
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return raw;
        }
    }

    private static String number(String raw) {
        try {
            return new BigDecimal(raw.strip()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static void tsvRow(List<String> cells, StringBuilder out) {
        int last = cells.size() - 1;
        while (last >= 0 && cells.get(last).isBlank()) {
            last--;
        }
        if (last < 0) {
            return;
        }
        for (int i = 0; i <= last; i++) {
            out.append(i == 0 ? "" : "\t").append(cells.get(i).replace('\t', ' ').replace("\r", "").replace('\n', ' '));
        }
        out.append('\n');
    }

    // ---- PowerPoint ----

    private static String pptx(Map<String, byte[]> parts) throws IOException, SAXException {
        Element presentation = xml(parts, "ppt/presentation.xml");
        if (presentation == null) {
            throw new IOException("нет ppt/presentation.xml");
        }
        Map<String, String> targets = relationships(parts, "ppt/presentation.xml");
        StringBuilder out = new StringBuilder();
        int number = 0;
        for (Element slide : children(child(presentation, "sldIdLst"), "sldId")) {
            number++;
            String target = targets.get(slide.getAttributeNS(Ooxml.R, "id"));
            Element root = target == null ? null : xml(parts, target);
            StringBuilder text = new StringBuilder();
            for (Element paragraph : descendants(root, "p")) {
                if (!"http://schemas.openxmlformats.org/drawingml/2006/main".equals(paragraph.getNamespaceURI())) {
                    continue;
                }
                StringBuilder line = new StringBuilder();
                for (Node node = paragraph.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element run) {
                        if (run.getLocalName().equals("br")) {
                            line.append('\n');
                        } else if (run.getLocalName().equals("r") || run.getLocalName().equals("fld")) {
                            Element t = child(run, "t");
                            line.append(t == null ? "" : t.getTextContent());
                        }
                    }
                }
                if (!line.toString().isBlank()) {
                    text.append(line.toString().strip()).append('\n');
                }
            }
            out.append("## Слайд ").append(number).append("\n\n").append(text).append('\n');
        }
        return out.toString();
    }

    // ---- LibreOffice (ODF) ----

    private static String odf(Map<String, byte[]> parts) throws IOException, SAXException {
        Element content = xml(parts, "content.xml");
        if (content == null) {
            throw new IOException("нет content.xml");
        }
        Element body = child(content, "body");
        StringBuilder out = new StringBuilder();
        Element spreadsheet = child(body, "spreadsheet");
        if (spreadsheet != null) {
            for (Element table : children(spreadsheet, "table")) {
                StringBuilder rows = new StringBuilder();
                for (Element row : odfRows(table)) {
                    List<String> cells = odfCells(row);
                    int repeat = Math.min(MAX_EMPTY_REPEAT, Math.max(1, intAttribute(row, "number-rows-repeated")));
                    boolean empty = cells.stream().allMatch(String::isBlank);
                    for (int i = 0; i < (empty ? 1 : repeat); i++) {
                        tsvRow(cells, rows);
                    }
                }
                if (!rows.isEmpty()) {
                    out.append("## Лист «").append(attribute(table, "name")).append("»\n\n").append(rows).append('\n');
                }
            }
            return out.toString();
        }
        Element presentation = child(body, "presentation");
        if (presentation != null) {
            int number = 0;
            for (Element page : children(presentation, "page")) {
                out.append("## Слайд ").append(++number).append("\n\n");
                odfBlocks(page, out, "");
            }
            return out.toString();
        }
        odfBlocks(child(body, "text"), out, "");
        return out.toString();
    }

    private static void odfBlocks(Element parent, StringBuilder out, String prefix) {
        for (Node node = parent == null ? null : parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element)) {
                continue;
            }
            switch (element.getLocalName()) {
                case "h" -> {
                    String text = odfText(element).strip();
                    if (!text.isEmpty()) {
                        int level = Math.max(1, Math.min(6, intAttribute(element, "outline-level")));
                        out.append("#".repeat(level)).append(' ').append(text).append("\n\n");
                    }
                }
                case "p" -> {
                    String text = odfText(element).strip();
                    if (!text.isEmpty()) {
                        out.append(prefix).append(text).append(prefix.isEmpty() ? "\n\n" : "\n");
                    }
                }
                case "list" -> {
                    odfBlocks(element, out, prefix.isEmpty() ? "" : prefix);
                    if (prefix.isEmpty()) {
                        out.append('\n');
                    }
                }
                case "list-item" -> odfBlocks(element, out, "- ");
                case "table" -> {
                    List<List<String>> rows = new ArrayList<>();
                    for (Element row : odfRows(element)) {
                        rows.add(odfCells(row));
                    }
                    markdownTable(rows, out);
                }
                default -> odfBlocks(element, out, prefix);
            }
        }
    }

    /** Строки таблицы ODF: и вложенные в группы и шапку. */
    private static List<Element> odfRows(Element table) {
        List<Element> rows = new ArrayList<>();
        for (Node node = table.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                switch (element.getLocalName()) {
                    case "table-row" -> rows.add(element);
                    case "table-header-rows", "table-rows", "table-row-group" -> rows.addAll(odfRows(element));
                    default -> {
                        // колонки, подписи
                    }
                }
            }
        }
        return rows;
    }

    private static List<String> odfCells(Element row) {
        List<String> cells = new ArrayList<>();
        for (Node node = row.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element cell && (cell.getLocalName().equals("table-cell") || cell.getLocalName().equals("covered-table-cell"))) {
                String value = cell.getLocalName().equals("table-cell") ? odfCellValue(cell) : "";
                int repeat = Math.min(MAX_EMPTY_REPEAT, Math.max(1, intAttribute(cell, "number-columns-repeated")));
                for (int i = 0; i < repeat; i++) {
                    cells.add(value);
                }
            }
        }
        int last = cells.size();
        while (last > 0 && cells.get(last - 1).isBlank()) {
            last--;
        }
        return new ArrayList<>(cells.subList(0, last));
    }

    private static String odfCellValue(Element cell) {
        String type = attribute(cell, "value-type");
        if (type.equals("date")) {
            String date = attribute(cell, "date-value");
            return date.endsWith("T00:00:00") ? date.substring(0, date.length() - 9) : date.replace('T', ' ');
        }
        StringBuilder text = new StringBuilder();
        for (Element paragraph : children(cell, "p")) {
            text.append(text.isEmpty() ? "" : " ").append(odfText(paragraph).strip());
        }
        if (text.isEmpty() && (type.equals("float") || type.equals("currency") || type.equals("percentage"))) {
            return number(attribute(cell, "value"));
        }
        return text.toString();
    }

    private static String odfText(Element element) {
        StringBuilder text = new StringBuilder();
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(node.getNodeValue());
            } else if (node instanceof Element child) {
                switch (child.getLocalName()) {
                    case "s" -> text.append(" ".repeat(Math.max(1, Math.min(100, intAttribute(child, "c")))));
                    case "tab" -> text.append('\t');
                    case "line-break" -> text.append('\n');
                    case "note", "annotation", "tracked-changes" -> {
                        // сноски и комментарии — не основной текст
                    }
                    default -> text.append(odfText(child));
                }
            }
        }
        return text.toString();
    }

    private static int intAttribute(Element element, String localName) {
        String value = attribute(element, localName);
        try {
            return value.isEmpty() ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
