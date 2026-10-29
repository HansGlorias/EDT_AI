package io.github.hansglorias.edtai.core.documents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Текст PDF без библиотек: объекты (и потоки объектов PDF 1.5), дерево
 * страниц, потоки содержимого со сжатием Flate, операторы вывода текста
 * ({@code Tj}, {@code TJ}, {@code '}, {@code "}) и перевод кодов глифов в
 * Unicode по {@code ToUnicode} шрифта, а без него — по кодировке простого
 * шрифта. Строки — по смене вертикали; колонки, порядок чтения и
 * зашифрованные файлы не разбираются. Сканы текста не дают.
 */
final class PdfText {

    private static final Charset LATIN1 = StandardCharsets.ISO_8859_1;
    private static final Pattern OBJECT = Pattern.compile("(\\d+)\\s+(\\d+)\\s+obj\\b");
    private static final Pattern ENCRYPT = Pattern.compile("/Encrypt\\s*(?:\\d+\\s+\\d+\\s+R|<<)");
    /** Распакованный поток больше — не разжимаем дальше. */
    private static final int MAX_STREAM = 50_000_000;
    private static final int MAX_DEPTH = 8;

    record Name(String value) {
    }

    record Ref(int number) {
    }

    record Op(String value) {
    }

    /** Строка PDF — байты: что они значат, решает шрифт. */
    record Bytes(byte[] value) {
    }

    record Stream(Map<String, Object> dictionary, byte[] raw) {
    }

    private final byte[] pdf;
    private final Map<Integer, Object> objects = new HashMap<>();
    private final Map<Object, Font> fonts = new HashMap<>();

    private PdfText(byte[] pdf) {
        this.pdf = pdf;
    }

    static String extract(byte[] bytes) throws IOException {
        String head = new String(bytes, 0, Math.min(bytes.length, 1024), LATIN1);
        if (!head.contains("%PDF-")) {
            throw new IOException("это не PDF");
        }
        PdfText reader = new PdfText(bytes);
        reader.readObjects();
        return reader.text().strip();
    }

    // ---- объекты ----

    private void readObjects() throws IOException {
        String text = new String(pdf, LATIN1);
        if (ENCRYPT.matcher(text).find()) {
            throw new IOException("PDF зашифрован — снимите защиту или приложите страницы картинками");
        }
        Matcher matcher = OBJECT.matcher(text);
        List<Stream> objectStreams = new ArrayList<>();
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            Lexer lexer = new Lexer(pdf, matcher.end());
            try {
                Object value = lexer.value();
                if (value instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dictionary = (Map<String, Object>) map;
                    byte[] raw = lexer.stream(dictionary);
                    if (raw != null) {
                        Stream stream = new Stream(dictionary, raw);
                        value = stream;
                        if (new Name("ObjStm").equals(dictionary.get("Type"))) {
                            objectStreams.add(stream);
                        }
                    }
                }
                objects.put(number, value);
            } catch (RuntimeException e) {
                // битый объект — пропускаем, остальные читаем
            }
        }
        for (Stream stream : objectStreams) {
            readObjectStream(stream);
        }
    }

    private void readObjectStream(Stream stream) {
        byte[] data = decode(stream);
        if (data == null || !(stream.dictionary().get("N") instanceof Number count)
                || !(stream.dictionary().get("First") instanceof Number first)) {
            return;
        }
        Lexer header = new Lexer(data, 0);
        List<int[]> entries = new ArrayList<>();
        for (int i = 0; i < count.intValue(); i++) {
            if (!(header.value() instanceof Number number) || !(header.value() instanceof Number offset)) {
                break;
            }
            entries.add(new int[] { number.intValue(), offset.intValue() });
        }
        for (int[] entry : entries) {
            try {
                objects.putIfAbsent(entry[0], new Lexer(data, first.intValue() + entry[1]).value());
            } catch (RuntimeException e) {
                // битый объект — пропускаем
            }
        }
    }

    private Object resolve(Object value) {
        Set<Integer> seen = new HashSet<>();
        while (value instanceof Ref ref && seen.add(ref.number())) {
            value = objects.get(ref.number());
        }
        return value instanceof Ref ? null : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dictionary(Object value) {
        Object resolved = resolve(value);
        if (resolved instanceof Stream stream) {
            return stream.dictionary();
        }
        return resolved instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<Object> array(Object value) {
        Object resolved = resolve(value);
        if (resolved instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return resolved == null ? List.of() : List.of(value);
    }

    /** Данные потока: без фильтра или Flate; другие фильтры (картинки) — {@code null}. */
    private byte[] decode(Stream stream) {
        List<Object> filters = array(stream.dictionary().get("Filter"));
        byte[] data = stream.raw();
        for (Object filter : filters) {
            Object name = resolve(filter);
            if (name instanceof Name n && (n.value().equals("FlateDecode") || n.value().equals("Fl"))) {
                data = inflate(data);
            } else {
                return null;
            }
        }
        return data;
    }

    private static byte[] inflate(byte[] data) {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
        byte[] buffer = new byte[64 * 1024];
        try {
            while (!inflater.finished() && out.size() < MAX_STREAM) {
                int count = inflater.inflate(buffer);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, count);
            }
        } catch (DataFormatException e) {
            // что успели распаковать — оставляем
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    // ---- страницы ----

    private String text() {
        List<Map<String, Object>> pages = new ArrayList<>();
        Map<Map<String, Object>, Object> resources = new LinkedHashMap<>();
        Map<String, Object> catalog = null;
        for (Object value : objects.values()) {
            Map<String, Object> dictionary = value instanceof Map<?, ?> ? dictionary(value) : Map.of();
            if (new Name("Catalog").equals(dictionary.get("Type"))) {
                catalog = dictionary;
            }
        }
        if (catalog != null) {
            collectPages(catalog.get("Pages"), null, pages, resources, new HashSet<>(), 0);
        }
        if (pages.isEmpty()) {
            List<Integer> numbers = new ArrayList<>(objects.keySet());
            numbers.sort(null);
            for (int number : numbers) {
                Map<String, Object> dictionary = dictionary(objects.get(number));
                if (new Name("Page").equals(dictionary.get("Type"))) {
                    pages.add(dictionary);
                    resources.put(dictionary, dictionary.get("Resources"));
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (Map<String, Object> page : pages) {
            StringBuilder content = new StringBuilder();
            for (Object part : array(page.get("Contents"))) {
                if (resolve(part) instanceof Stream stream) {
                    byte[] data = decode(stream);
                    if (data != null) {
                        content.append(new String(data, LATIN1)).append('\n');
                    }
                }
            }
            String text = new Page(dictionary(resources.get(page))).run(content.toString().getBytes(LATIN1), 0).strip();
            if (!text.isEmpty()) {
                out.append(text).append("\n\n");
            }
        }
        return out.toString();
    }

    private void collectPages(Object node, Object inherited, List<Map<String, Object>> pages, Map<Map<String, Object>, Object> resources,
            Set<Object> seen, int depth) {
        if (depth > 64 || node instanceof Ref ref && !seen.add(ref.number())) {
            return;
        }
        Map<String, Object> dictionary = dictionary(node);
        Object own = dictionary.containsKey("Resources") ? dictionary.get("Resources") : inherited;
        if (dictionary.containsKey("Kids")) {
            for (Object kid : array(dictionary.get("Kids"))) {
                collectPages(kid, own, pages, resources, seen, depth + 1);
            }
        } else if (!dictionary.isEmpty()) {
            pages.add(dictionary);
            resources.put(dictionary, own);
        }
    }

    /**
     * Вывод текста одной страницы или формы. Где стоит текст, считаем по
     * матрице текста ({@code Td}, {@code Tm}, {@code T*}) и ширинам глифов
     * шрифта: куски одной строки склеиваются, пробел — где есть зазор,
     * колонки таблицы — через табуляцию.
     */
    private final class Page {

        private final Map<String, Object> resources;
        private final StringBuilder out = new StringBuilder();
        private Font font;
        private double fontSize = 10;
        private double leading;
        /** Матрица текста: a b c d e f; e, f — начало строки. */
        private double a = 1, b, c, d = 1, lineX, lineY;
        private double x;
        private Double lastY;
        private double lastEnd;

        Page(Map<String, Object> resources) {
            this.resources = resources;
        }

        String run(byte[] content, int depth) {
            Lexer lexer = new Lexer(content, 0);
            List<Object> operands = new ArrayList<>();
            Object token;
            while ((token = lexer.contentToken()) != Lexer.END) {
                if (!(token instanceof Op op)) {
                    operands.add(token);
                    continue;
                }
                operate(op.value(), operands, depth);
                operands.clear();
            }
            return out.toString();
        }

        private void operate(String op, List<Object> operands, int depth) {
            switch (op) {
                case "BT" -> {
                    a = 1;
                    b = 0;
                    c = 0;
                    d = 1;
                    lineX = 0;
                    lineY = 0;
                    x = 0;
                }
                case "Tf" -> {
                    if (operands.size() >= 2 && operands.get(0) instanceof Name name) {
                        font = font(dictionary(resources.get("Font")).get(name.value()));
                        fontSize = operands.get(1) instanceof Number size ? Math.abs(size.doubleValue()) : fontSize;
                    }
                }
                case "TL" -> leading = number(operands, 0);
                case "Td" -> move(number(operands, 0), number(operands, 1));
                case "TD" -> {
                    leading = -number(operands, 1);
                    move(number(operands, 0), number(operands, 1));
                }
                case "Tm" -> {
                    if (operands.size() >= 6) {
                        a = number(operands, 0);
                        b = number(operands, 1);
                        c = number(operands, 2);
                        d = number(operands, 3);
                        lineX = number(operands, 4);
                        lineY = number(operands, 5);
                        x = lineX;
                    }
                }
                case "T*" -> move(0, -leading);
                case "Tj" -> show(last(operands));
                case "'" -> {
                    move(0, -leading);
                    show(last(operands));
                }
                case "\"" -> {
                    move(0, -leading);
                    show(last(operands));
                }
                case "TJ" -> {
                    if (last(operands) instanceof List<?> parts) {
                        for (Object part : parts) {
                            if (part instanceof Number shift) {
                                x -= shift.doubleValue() / 1000 * size();
                            } else {
                                show(part);
                            }
                        }
                    }
                }
                case "Do" -> {
                    if (depth < MAX_DEPTH && !operands.isEmpty() && operands.get(0) instanceof Name name) {
                        Object form = resolve(dictionary(resources.get("XObject")).get(name.value()));
                        if (form instanceof Stream stream && new Name("Form").equals(stream.dictionary().get("Subtype"))) {
                            byte[] data = decode(stream);
                            if (data != null) {
                                Map<String, Object> own = dictionary(stream.dictionary().get("Resources"));
                                Page inner = new Page(own.isEmpty() ? resources : own);
                                newLine();
                                out.append(inner.run(data, depth + 1).strip());
                                newLine();
                                lastY = null;
                            }
                        }
                    }
                }
                default -> {
                    // графика, цвет, состояние — не текст
                }
            }
        }

        private void move(double tx, double ty) {
            lineX += tx * a + ty * c;
            lineY += tx * b + ty * d;
            x = lineX;
        }

        /** Кегль на странице: с масштабом матрицы текста. */
        private double size() {
            double scale = Math.abs(d) > 1e-6 ? Math.abs(d) : Math.abs(b);
            return Math.max(1, fontSize * (scale > 1e-6 ? scale : 1));
        }

        private double number(List<Object> operands, int index) {
            return index < operands.size() && operands.get(index) instanceof Number n ? n.doubleValue() : 0;
        }

        private Object last(List<Object> operands) {
            return operands.isEmpty() ? null : operands.get(operands.size() - 1);
        }

        private void show(Object value) {
            if (!(value instanceof Bytes bytes)) {
                return;
            }
            String text = font != null ? font.decode(bytes.value()) : new String(bytes.value(), LATIN1);
            double size = size();
            if (lastY != null) {
                if (Math.abs(lineY - lastY) > size * 0.5) {
                    newLine();
                } else if (x - lastEnd > size * 1.5) {
                    tab();
                } else if (x - lastEnd > size * 0.15 || x < lastEnd - size) {
                    space();
                }
            }
            out.append(text);
            x += (font != null ? font.advance(bytes.value()) : bytes.value().length * 0.5) * size;
            lastY = lineY;
            lastEnd = x;
        }

        private void newLine() {
            trimSpace();
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
        }

        private void space() {
            if (!out.isEmpty() && !Character.isWhitespace(out.charAt(out.length() - 1))) {
                out.append(' ');
            }
        }

        private void tab() {
            trimSpace();
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                out.append('\t');
            }
        }

        private void trimSpace() {
            while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
                out.setLength(out.length() - 1);
            }
        }
    }

    // ---- шрифты ----

    private Font font(Object reference) {
        Object key = reference instanceof Ref ref ? ref : System.identityHashCode(resolve(reference));
        return fonts.computeIfAbsent(key, k -> new Font(dictionary(reference)));
    }

    private final class Font {

        private final Map<Integer, String> unicode = new HashMap<>();
        /** Ширины глифов в тысячных кегля: {@code /Widths} простого шрифта, {@code /W} составного. */
        private final Map<Integer, Double> widths = new HashMap<>();
        private final double defaultWidth;
        private final String[] simple;
        private int codeBytes;

        Font(Map<String, Object> dictionary) {
            boolean composite = new Name("Type0").equals(dictionary.get("Subtype"));
            codeBytes = composite ? 2 : 1;
            if (composite) {
                List<Object> descendants = array(dictionary.get("DescendantFonts"));
                Map<String, Object> cid = descendants.isEmpty() ? Map.of() : PdfText.this.dictionary(descendants.get(0));
                defaultWidth = cid.get("DW") instanceof Number dw ? dw.doubleValue() : 1000;
                readCidWidths(array(cid.get("W")));
            } else {
                Map<String, Object> descriptor = PdfText.this.dictionary(dictionary.get("FontDescriptor"));
                defaultWidth = descriptor.get("MissingWidth") instanceof Number missing && missing.doubleValue() > 0 ? missing.doubleValue()
                        : 500;
                int first = resolve(dictionary.get("FirstChar")) instanceof Number n ? n.intValue() : 0;
                List<Object> list = array(dictionary.get("Widths"));
                for (int i = 0; i < list.size(); i++) {
                    if (resolve(list.get(i)) instanceof Number width) {
                        widths.put(first + i, width.doubleValue());
                    }
                }
            }
            if (resolve(dictionary.get("ToUnicode")) instanceof Stream stream) {
                byte[] data = PdfText.this.decode(stream);
                if (data != null) {
                    readCMap(data);
                }
            }
            simple = composite ? null : simpleEncoding(dictionary.get("Encoding"));
        }

        /** {@code [c [w1 w2 …]]} и {@code [c1 c2 w]}. */
        private void readCidWidths(List<Object> list) {
            for (int i = 0; i + 1 < list.size();) {
                if (!(resolve(list.get(i)) instanceof Number first)) {
                    i++;
                    continue;
                }
                Object next = resolve(list.get(i + 1));
                if (next instanceof List<?> each) {
                    for (int j = 0; j < each.size() && j < 0x10000; j++) {
                        if (resolve(each.get(j)) instanceof Number width) {
                            widths.put(first.intValue() + j, width.doubleValue());
                        }
                    }
                    i += 2;
                } else if (next instanceof Number last && i + 2 < list.size() && resolve(list.get(i + 2)) instanceof Number width) {
                    for (int code = first.intValue(); code <= last.intValue() && code - first.intValue() < 0x10000; code++) {
                        widths.put(code, width.doubleValue());
                    }
                    i += 3;
                } else {
                    i++;
                }
            }
        }

        /** Ширина строки в кеглях. */
        double advance(byte[] bytes) {
            double total = 0;
            for (int i = 0; i + codeBytes <= bytes.length; i += codeBytes) {
                int code = 0;
                for (int j = 0; j < codeBytes; j++) {
                    code = code << 8 | bytes[i + j] & 0xFF;
                }
                total += widths.getOrDefault(code, defaultWidth);
            }
            return total / 1000;
        }

        String decode(byte[] bytes) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i + codeBytes <= bytes.length; i += codeBytes) {
                int code = 0;
                for (int j = 0; j < codeBytes; j++) {
                    code = code << 8 | bytes[i + j] & 0xFF;
                }
                String mapped = unicode.get(code);
                if (mapped == null && simple != null && code < 256) {
                    mapped = simple[code];
                }
                if (mapped != null) {
                    text.append(mapped);
                }
            }
            return text.toString().replace(' ', ' ');
        }

        private void readCMap(byte[] data) {
            Lexer lexer = new Lexer(data, 0);
            List<Object> operands = new ArrayList<>();
            Object token;
            boolean codespaceRead = false;
            while ((token = lexer.contentToken()) != Lexer.END) {
                if (!(token instanceof Op op)) {
                    operands.add(token);
                    continue;
                }
                switch (op.value()) {
                    case "endcodespacerange" -> {
                        if (!codespaceRead && !operands.isEmpty() && operands.get(0) instanceof Bytes low) {
                            codeBytes = Math.max(1, Math.min(4, low.value().length));
                            codespaceRead = true;
                        }
                    }
                    case "endbfchar" -> {
                        for (int i = 0; i + 1 < operands.size(); i += 2) {
                            if (operands.get(i) instanceof Bytes source && operands.get(i + 1) instanceof Bytes target) {
                                unicode.put(code(source.value()), utf16(target.value()));
                            }
                        }
                    }
                    case "endbfrange" -> {
                        for (int i = 0; i + 2 < operands.size(); i += 3) {
                            if (!(operands.get(i) instanceof Bytes low) || !(operands.get(i + 1) instanceof Bytes high)) {
                                continue;
                            }
                            int from = code(low.value());
                            int to = Math.min(code(high.value()), from + 0xFFFF);
                            Object target = operands.get(i + 2);
                            for (int code = from; code <= to; code++) {
                                if (target instanceof Bytes start) {
                                    unicode.put(code, increment(start.value(), code - from));
                                } else if (target instanceof List<?> list && code - from < list.size()
                                        && list.get(code - from) instanceof Bytes each) {
                                    unicode.put(code, utf16(each.value()));
                                }
                            }
                        }
                    }
                    default -> {
                        // begin…, def и прочее — не нужно
                    }
                }
                operands.clear();
            }
        }
    }

    private static int code(byte[] bytes) {
        int code = 0;
        for (byte b : bytes) {
            code = code << 8 | b & 0xFF;
        }
        return code;
    }

    private static String utf16(byte[] bytes) {
        return bytes.length % 2 == 0 ? new String(bytes, StandardCharsets.UTF_16BE) : new String(bytes, LATIN1);
    }

    /** Начало диапазона bfrange плюс смещение: увеличивается последний символ. */
    private static String increment(byte[] start, int delta) {
        String text = utf16(start);
        if (text.isEmpty()) {
            return text;
        }
        return text.substring(0, text.length() - 1) + (char) (text.charAt(text.length() - 1) + delta);
    }

    /** Простой шрифт: WinAnsi (или Standard) и {@code /Differences} по именам глифов. */
    private String[] simpleEncoding(Object encoding) {
        String[] table = new String[256];
        Charset windows = Charset.forName("windows-1252");
        for (int code = 32; code < 256; code++) {
            table[code] = new String(new byte[] { (byte) code }, windows);
        }
        Object resolved = resolve(encoding);
        if (resolved instanceof Map<?, ?>) {
            int code = 0;
            for (Object item : array(dictionary(resolved).get("Differences"))) {
                Object value = resolve(item);
                if (value instanceof Number number) {
                    code = number.intValue();
                } else if (value instanceof Name name && code >= 0 && code < 256) {
                    String glyph = Glyphs.unicode(name.value());
                    if (glyph != null) {
                        table[code] = glyph;
                    }
                    code++;
                }
            }
        }
        return table;
    }

    // ---- лексер ----

    static final class Lexer {

        static final Object END = new Object();
        private static final Object DICT_END = new Object();
        private static final Object ARRAY_END = new Object();
        private static final Object SKIP = new Object();

        /** Глубже — битый или злонамеренный файл: без переполнения стека. */
        private static final int MAX_NESTING = 64;

        private final byte[] data;
        private int pos;
        private int nesting;

        Lexer(byte[] data, int pos) {
            this.data = data;
            this.pos = pos;
        }

        /** Значение объекта: ссылки {@code n g R} собираются. */
        Object value() {
            Object token = token(false);
            if (token == END || token == DICT_END || token == ARRAY_END) {
                throw new IllegalStateException("нет значения");
            }
            return token;
        }

        /** Токен потока содержимого: операнд или оператор. */
        Object contentToken() {
            Object token = token(true);
            while (token == DICT_END || token == ARRAY_END) {
                token = token(true);
            }
            if (token instanceof Op op && op.value().equals("BI")) {
                skipInlineImage();
                return new Op("EI");
            }
            return token;
        }

        /** Данные потока после словаря: по {@code /Length} или до {@code endstream}. */
        byte[] stream(Map<String, Object> dictionary) {
            int start = pos;
            skipSpace();
            if (!startsWith("stream")) {
                pos = start;
                return null;
            }
            pos += 6;
            if (pos < data.length && data[pos] == '\r') {
                pos++;
            }
            if (pos < data.length && data[pos] == '\n') {
                pos++;
            }
            int begin = pos;
            if (dictionary.get("Length") instanceof Number length && length.intValue() >= 0 && begin + length.intValue() <= data.length) {
                int end = begin + length.intValue();
                pos = end;
                skipSpace();
                if (startsWith("endstream")) {
                    return java.util.Arrays.copyOfRange(data, begin, end);
                }
            }
            int end = indexOf("endstream", begin);
            if (end < 0) {
                return null;
            }
            pos = end + 9;
            int stop = end;
            if (stop > begin && data[stop - 1] == '\n') {
                stop--;
            }
            if (stop > begin && data[stop - 1] == '\r') {
                stop--;
            }
            return java.util.Arrays.copyOfRange(data, begin, stop);
        }

        private Object token(boolean content) {
            Object token;
            do {
                token = next(content);
            } while (token == SKIP);
            return token;
        }

        /** Очередной токен; {@link #SKIP} — пропущен мусорный знак. */
        private Object next(boolean content) {
            if (nesting > MAX_NESTING) {
                throw new IllegalStateException("слишком глубокая вложенность");
            }
            skipSpace();
            if (pos >= data.length) {
                return END;
            }
            int c = data[pos] & 0xFF;
            switch (c) {
                case '<' -> {
                    if (pos + 1 < data.length && data[pos + 1] == '<') {
                        pos += 2;
                        nesting++;
                        Map<String, Object> dictionary = dictionary(content);
                        nesting--;
                        return dictionary;
                    }
                    return hexString();
                }
                case '>' -> {
                    pos += pos + 1 < data.length && data[pos + 1] == '>' ? 2 : 1;
                    return DICT_END;
                }
                case '[' -> {
                    pos++;
                    nesting++;
                    List<Object> list = new ArrayList<>();
                    Object item;
                    while ((item = token(content)) != ARRAY_END && item != END) {
                        if (item != DICT_END) {
                            list.add(item);
                        }
                    }
                    nesting--;
                    return list;
                }
                case ']' -> {
                    pos++;
                    return ARRAY_END;
                }
                case '(' -> {
                    return literalString();
                }
                case '/' -> {
                    return name();
                }
                case '{', '}' -> {
                    pos++;
                    return content ? new Op(c == '{' ? "{" : "}") : SKIP;
                }
                default -> {
                    if (c == '+' || c == '-' || c == '.' || c >= '0' && c <= '9') {
                        Number number = number();
                        if (!content && number instanceof Long first) {
                            int save = pos;
                            skipSpace();
                            if (pos < data.length && Character.isDigit(data[pos])) {
                                Number second = number();
                                skipSpace();
                                if (second instanceof Long && pos < data.length && data[pos] == 'R' && delimiterAt(pos + 1)) {
                                    pos++;
                                    return new Ref(first.intValue());
                                }
                            }
                            pos = save;
                        }
                        return number;
                    }
                    String word = keyword();
                    if (word.isEmpty()) {
                        pos++;
                        return SKIP;
                    }
                    return switch (word) {
                        case "true" -> Boolean.TRUE;
                        case "false" -> Boolean.FALSE;
                        case "null" -> null;
                        default -> new Op(word);
                    };
                }
            }
        }

        private Map<String, Object> dictionary(boolean content) {
            Map<String, Object> dictionary = new LinkedHashMap<>();
            while (true) {
                Object key = token(content);
                if (key == DICT_END || key == END) {
                    return dictionary;
                }
                if (key instanceof Name name) {
                    Object value = token(content);
                    if (value == DICT_END || value == END) {
                        return dictionary;
                    }
                    dictionary.put(name.value(), value);
                }
            }
        }

        private Bytes hexString() {
            pos++;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int high = -1;
            while (pos < data.length && data[pos] != '>') {
                int digit = Character.digit(data[pos++], 16);
                if (digit < 0) {
                    continue;
                }
                if (high < 0) {
                    high = digit;
                } else {
                    out.write(high << 4 | digit);
                    high = -1;
                }
            }
            if (high >= 0) {
                out.write(high << 4);
            }
            pos++;
            return new Bytes(out.toByteArray());
        }

        private Bytes literalString() {
            pos++;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int depth = 1;
            while (pos < data.length) {
                int c = data[pos++] & 0xFF;
                if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    break;
                } else if (c == '\\' && pos < data.length) {
                    int e = data[pos++] & 0xFF;
                    switch (e) {
                        case 'n' -> c = '\n';
                        case 'r' -> c = '\r';
                        case 't' -> c = '\t';
                        case 'b' -> c = '\b';
                        case 'f' -> c = '\f';
                        case '\r' -> {
                            if (pos < data.length && data[pos] == '\n') {
                                pos++;
                            }
                            continue;
                        }
                        case '\n' -> {
                            continue;
                        }
                        default -> {
                            if (e >= '0' && e <= '7') {
                                int value = e - '0';
                                for (int i = 0; i < 2 && pos < data.length && data[pos] >= '0' && data[pos] <= '7'; i++) {
                                    value = value * 8 + data[pos++] - '0';
                                }
                                c = value & 0xFF;
                            } else {
                                c = e;
                            }
                        }
                    }
                }
                out.write(c);
            }
            return new Bytes(out.toByteArray());
        }

        private Name name() {
            pos++;
            StringBuilder name = new StringBuilder();
            while (pos < data.length && !delimiterAt(pos)) {
                int c = data[pos++] & 0xFF;
                if (c == '#' && pos + 1 < data.length && Character.digit(data[pos], 16) >= 0 && Character.digit(data[pos + 1], 16) >= 0) {
                    c = Character.digit(data[pos], 16) << 4 | Character.digit(data[pos + 1], 16);
                    pos += 2;
                }
                name.append((char) c);
            }
            return new Name(name.toString());
        }

        private Number number() {
            int start = pos;
            pos++;
            while (pos < data.length && (Character.isDigit(data[pos]) || data[pos] == '.')) {
                pos++;
            }
            String text = new String(data, start, pos - start, LATIN1);
            try {
                if (text.contains(".")) {
                    return Double.parseDouble(text.startsWith(".") || text.startsWith("-.") ? text.replace(".", "0.") : text);
                }
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }

        private String keyword() {
            int start = pos;
            while (pos < data.length && !delimiterAt(pos)) {
                pos++;
            }
            return new String(data, start, pos - start, LATIN1);
        }

        private void skipInlineImage() {
            int at = pos;
            while (at + 2 < data.length) {
                if (data[at] == 'I' && data[at + 1] == 'D' && isSpace(data[at + 2])) {
                    break;
                }
                at++;
            }
            at += 3;
            while (at + 2 < data.length) {
                if (isSpace(data[at - 1]) && data[at] == 'E' && data[at + 1] == 'I' && (at + 2 >= data.length || delimiterAt(at + 2))) {
                    pos = at + 2;
                    return;
                }
                at++;
            }
            pos = data.length;
        }

        private void skipSpace() {
            while (pos < data.length) {
                if (isSpace(data[pos])) {
                    pos++;
                } else if (data[pos] == '%') {
                    while (pos < data.length && data[pos] != '\n' && data[pos] != '\r') {
                        pos++;
                    }
                } else {
                    return;
                }
            }
        }

        private boolean startsWith(String word) {
            if (pos + word.length() > data.length) {
                return false;
            }
            for (int i = 0; i < word.length(); i++) {
                if (data[pos + i] != word.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        private int indexOf(String word, int from) {
            outer: for (int i = from; i + word.length() <= data.length; i++) {
                for (int j = 0; j < word.length(); j++) {
                    if (data[i + j] != word.charAt(j)) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }

        private boolean delimiterAt(int at) {
            if (at >= data.length) {
                return true;
            }
            byte c = data[at];
            return isSpace(c) || c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}' || c == '/'
                    || c == '%';
        }

        private static boolean isSpace(byte c) {
            return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\f' || c == 0;
        }
    }
}
