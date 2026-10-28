package io.github.hansglorias.edtai.core.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Разбор и запись JSON без сторонних библиотек.
 *
 * <p>Соответствие типов: объект — {@code Map<String, Object>} (порядок ключей
 * сохраняется), массив — {@code List<Object>}, строка — {@link String},
 * число — {@link Long} или {@link BigDecimal}, {@code true}/{@code false} —
 * {@link Boolean}, {@code null} — {@code null}.
 */
public final class Json {

    private static final int MAX_DEPTH = 512;

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /**
     * @throws JsonException если текст — не корректный JSON
     */
    public static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (parser.pos < text.length()) {
            throw parser.error("лишние символы после значения");
        }
        return value;
    }

    /**
     * Разбирает JSON-объект.
     *
     * @throws JsonException если текст — не корректный JSON или не объект
     */
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (value instanceof Map<?, ?>) {
            return asObject(value);
        }
        throw new JsonException("Ожидался JSON-объект");
    }

    /** Записывает значение в компактный JSON. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /** Объект или {@code null}, если значение — не объект. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /** Массив или пустой список, если значение — не массив. */
    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    /** Строка или {@code null}, если значение — не строка. */
    public static String asString(Object value) {
        return value instanceof String string ? string : null;
    }

    /** Вложенное значение по пути из ключей объектов и индексов массивов, {@code null} — если пути нет. */
    public static Object at(Object root, Object... path) {
        Object current = root;
        for (Object step : path) {
            if (step instanceof String key && current instanceof Map<?, ?> map) {
                current = map.get(key);
            } else if (step instanceof Integer index && current instanceof List<?> list && index >= 0 && index < list.size()) {
                current = list.get(index);
            } else {
                return null;
            }
        }
        return current;
    }

    /** Строка по пути или {@code null}. */
    public static String stringAt(Object root, Object... path) {
        return asString(at(root, path));
    }

    // ---- разбор ----

    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("слишком глубокая вложенность");
        }
        if (pos >= text.length()) {
            throw error("неожиданный конец текста");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return readObject(depth);
            case '[':
                return readArray(depth);
            case '"':
                return readString();
            case 't':
                expectWord("true");
                return Boolean.TRUE;
            case 'f':
                expectWord("false");
                return Boolean.FALSE;
            case 'n':
                expectWord("null");
                return null;
            default:
                if (c == '-' || c >= '0' && c <= '9') {
                    return readNumber();
                }
                throw error("неожиданный символ «" + c + "»");
        }
    }

    private Map<String, Object> readObject(int depth) {
        Map<String, Object> object = new LinkedHashMap<>();
        pos++;
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return object;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("ожидалось имя поля в кавычках");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            object.put(key, readValue(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return object;
            }
            if (c != ',') {
                pos--;
                throw error("ожидалась «,» или «}»");
            }
        }
    }

    private List<Object> readArray(int depth) {
        List<Object> array = new ArrayList<>();
        pos++;
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return array;
            }
            if (c != ',') {
                pos--;
                throw error("ожидалась «,» или «]»");
            }
        }
    }

    private String readString() {
        pos++;
        StringBuilder out = null;
        int start = pos;
        while (true) {
            if (pos >= text.length()) {
                throw error("незакрытая строка");
            }
            char c = text.charAt(pos);
            if (c == '"') {
                String result = out == null ? text.substring(start, pos) : out.append(text, start, pos).toString();
                pos++;
                return result;
            }
            if (c < 0x20) {
                throw error("управляющий символ в строке");
            }
            if (c == '\\') {
                if (out == null) {
                    out = new StringBuilder();
                }
                out.append(text, start, pos);
                pos++;
                out.append(readEscape());
                start = pos;
            } else {
                pos++;
            }
        }
    }

    private char readEscape() {
        char c = next();
        switch (c) {
            case '"':
            case '\\':
            case '/':
                return c;
            case 'b':
                return '\b';
            case 'f':
                return '\f';
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case 'u':
                if (pos + 4 > text.length()) {
                    throw error("неполная последовательность \\u");
                }
                try {
                    char decoded = (char) Integer.parseInt(text.substring(pos, pos + 4), 16);
                    pos += 4;
                    return decoded;
                } catch (NumberFormatException e) {
                    throw error("некорректная последовательность \\u");
                }
            default:
                pos--;
                throw error("некорректная escape-последовательность");
        }
    }

    private Number readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
        } else if (isDigit(peek())) {
            skipDigits();
        } else {
            throw error("некорректное число");
        }
        boolean integer = true;
        if (peek() == '.') {
            integer = false;
            pos++;
            if (!isDigit(peek())) {
                throw error("некорректное число");
            }
            skipDigits();
        }
        if (peek() == 'e' || peek() == 'E') {
            integer = false;
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            if (!isDigit(peek())) {
                throw error("некорректное число");
            }
            skipDigits();
        }
        String number = text.substring(start, pos);
        if (integer) {
            BigInteger value = new BigInteger(number);
            if (value.bitLength() < 64) {
                return value.longValue();
            }
        }
        return new BigDecimal(number);
    }

    private void skipDigits() {
        while (isDigit(peek())) {
            pos++;
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void expectWord(String word) {
        if (!text.startsWith(word, pos)) {
            throw error("ожидалось «" + word + "»");
        }
        pos += word.length();
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw error("ожидалось «" + expected + "»");
        }
        pos++;
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private char next() {
        if (pos >= text.length()) {
            throw error("неожиданный конец текста");
        }
        return text.charAt(pos++);
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private JsonException error(String message) {
        return new JsonException("Некорректный JSON (позиция " + pos + "): " + message);
    }

    // ---- запись ----

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            writeString(string, out);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                throw new JsonException("Число " + number + " нельзя записать в JSON");
            }
            out.append(BigDecimal.valueOf(number).stripTrailingZeros().toPlainString());
        } else if (value instanceof BigDecimal decimal) {
            out.append(decimal.toString());
        } else if (value instanceof Number) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(item, out);
            }
            out.append(']');
        } else {
            throw new JsonException("Тип " + value.getClass().getName() + " нельзя записать в JSON");
        }
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Неизменяемый объект из пар ключ–значение, удобно для сборки запросов. */
    public static Map<String, Object> object(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Нужно чётное число аргументов");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
