package io.github.hansglorias.edtai.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesAllValueTypes() {
        Object value = Json.parse(" {\"s\":\"текст\",\"i\":42,\"neg\":-7,\"d\":1.5e2,\"t\":true,\"f\":false,\"n\":null,"
                + "\"a\":[1,[],{}],\"o\":{\"k\":\"v\"}} ");

        Map<String, Object> object = Json.asObject(value);
        assertEquals("текст", object.get("s"));
        assertEquals(42L, object.get("i"));
        assertEquals(-7L, object.get("neg"));
        assertEquals(new BigDecimal("1.5e2"), object.get("d"));
        assertEquals(Boolean.TRUE, object.get("t"));
        assertEquals(Boolean.FALSE, object.get("f"));
        assertTrue(object.containsKey("n"));
        assertNull(object.get("n"));
        assertEquals(List.of(1L, List.of(), Map.of()), object.get("a"));
        assertEquals(Map.of("k", "v"), object.get("o"));
        assertEquals(List.of("s", "i", "neg", "d", "t", "f", "n", "a", "o"), List.copyOf(object.keySet()));
    }

    @Test
    void parsesEscapes() {
        assertEquals("a\"b\\c/d\b\f\n\r\tЖ😀", Json.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\\u0416\\ud83d\\ude00\""));
    }

    @Test
    void parsesBigNumbers() {
        assertEquals(new BigDecimal("123456789012345678901234567890"), Json.parse("123456789012345678901234567890"));
        assertEquals(Long.MAX_VALUE, Json.parse(String.valueOf(Long.MAX_VALUE)));
    }

    @Test
    void rejectsInvalidJson() {
        for (String text : Arrays.asList("", "{", "[1,]", "{\"a\" 1}", "{a:1}", "\"незакрытая", "01", "1.", "-", "tru",
                "[1] 2", "\"\\x\"", "\"\\u12\"", "\"\n\"", "{\"a\":1,}")) {
            assertThrows(JsonException.class, () -> Json.parse(text), text);
        }
    }

    @Test
    void rejectsTooDeepNesting() {
        String deep = "[".repeat(10_000) + "]".repeat(10_000);

        assertThrows(JsonException.class, () -> Json.parse(deep));
    }

    @Test
    void writesValues() {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("s", "кавычка \" и \\ и\nперевод\u0001");
        object.put("n", null);
        object.put("i", 5);
        object.put("l", 6L);
        object.put("d", 2.50);
        object.put("b", true);
        object.put("a", List.of(1, "x"));

        assertEquals("{\"s\":\"кавычка \\\" и \\\\ и\\nперевод\\u0001\",\"n\":null,\"i\":5,\"l\":6,\"d\":2.5,\"b\":true,\"a\":[1,\"x\"]}",
                Json.write(object));
    }

    @Test
    void roundTrips() {
        String text = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"при\\tвет\"}],\"stream\":true,\"x\":1.25}";

        assertEquals(text, Json.write(Json.parse(text)));
    }

    @Test
    void rejectsUnsupportedValues() {
        assertThrows(JsonException.class, () -> Json.write(new Object()));
        assertThrows(JsonException.class, () -> Json.write(Double.NaN));
    }

    @Test
    void navigatesPaths() {
        Object json = Json.parse("{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}");

        assertEquals("x", Json.stringAt(json, "choices", 0, "delta", "content"));
        assertNull(Json.at(json, "choices", 1));
        assertNull(Json.at(json, "choices", "delta"));
        assertNull(Json.stringAt(json, "choices"));
        assertNull(Json.at(null, "a"));
    }

    @Test
    void parseObjectRejectsOtherTypes() {
        assertEquals(Map.of(), Json.parseObject("{}"));
        assertThrows(JsonException.class, () -> Json.parseObject("[]"));
    }
}
