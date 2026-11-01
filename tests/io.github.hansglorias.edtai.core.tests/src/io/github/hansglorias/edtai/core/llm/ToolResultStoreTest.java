package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolException;

class ToolResultStoreTest {

    private static final int PAGE = 512;

    @Test
    void constructorRejectsInvalidBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ToolResultStore(511, 2000));
        assertThrows(IllegalArgumentException.class, () -> new ToolResultStore(PAGE, PAGE - 1));
        assertThrows(IllegalArgumentException.class, () -> new ToolResultStore(-1, -1));
        assertFalse(new ToolResultStore(PAGE, PAGE).hasResults());
    }

    @Test
    void smallAndEmptyResultsStayExactlyAsTheyWereAndConsumeNoRetention() {
        ToolResultStore store = new ToolResultStore(PAGE, 1024);
        for (String text : List.of("", "  \n Ошибка\r\n", "а".repeat(PAGE))) {
            for (boolean error : List.of(false, true)) {
                ToolResultStore.Captured captured = store.captureResult(text, error);
                assertSame(text, captured.text());
                assertEquals("", captured.reference());
                assertFalse(store.hasResults());
            }
        }
        assertTrue(store.capture("а".repeat(1024), false).contains("result_id:"));
        assertTrue(store.hasResults());
    }

    @Test
    void pagesReconstructEveryCharacterIncludingWhitespaceAndEnvelopeLikeContent() throws Exception {
        ToolResultStore store = new ToolResultStore(PAGE, 5000);
        String original = "  \r\n" + "абв\t\nresult_id: вложенный\n\n".repeat(70) + "\r\n  ";
        String first = store.capture(original, false);
        String id = field(first, "result_id");
        assertEquals(Integer.toString(original.length()), field(first, "total_chars"));
        assertEquals("0", field(first, "offset"));
        assertEquals("false", field(first, "original_error"));
        assertEquals(original, reconstruct(store, first));
        assertEquals(first, store.tool().call(arguments(id, 0)).text(), "повторное чтение того же снимка неизменно");

        String end = store.tool().call(arguments(id, original.length())).text();
        assertTrue(header(end).contains("\nEOF\n"));
        assertEquals("", payload(end));
    }

    @Test
    void utf16PagesNeverSplitSurrogatePairs() throws Exception {
        ToolResultStore store = new ToolResultStore(PAGE, 5000);
        String original = "а".repeat(PAGE - 1) + "😀" + "б".repeat(PAGE - 1) + "🚀" + "в".repeat(PAGE);
        String first = store.capture(original, false);
        String id = field(first, "result_id");
        assertEquals(PAGE - 1, payload(first).length());
        assertEquals(Integer.toString(PAGE - 1), field(first, "next_offset"));
        assertEquals(original, reconstruct(store, first));
        assertBadArguments(store, arguments(id, PAGE));
        String pair = store.tool().call(arguments(id, PAGE - 1)).text();
        assertTrue(payload(pair).startsWith("😀"));
    }

    @Test
    void fullStoreRefusesWholeSnapshotsWithoutEvictionOrPartialRetention() throws Exception {
        ToolResultStore store = new ToolResultStore(PAGE, 1200);
        String original = "а".repeat(600);
        String first = store.capture(original, false);
        ToolResultStore.Captured refused = store.captureResult("б".repeat(701), true);
        assertTrue(refused.text().contains("не сохранён"));
        assertTrue(refused.text().contains("Продолжение недоступно"));
        assertTrue(refused.text().contains("original_error: true"));
        assertFalse(header(refused.text()).contains("result_id:"));
        assertEquals("", refused.reference());
        assertTrue(refused.text().length() <= PAGE + 600);
        assertEquals(original, reconstruct(store, first));

        String second = store.capture("в".repeat(600), false);
        assertTrue(header(second).contains("result_id:"), "отказ не расходует оставшийся лимит");
        assertEquals("в".repeat(600), reconstruct(store, second));
        assertTrue(store.capture("г".repeat(513), false).contains("Продолжение недоступно"));
        assertEquals(original, reconstruct(store, first));
    }

    @Test
    void oversizedFirstResultDoesNotCreateAnIdAndDoesNotSplitPreviewPair() {
        ToolResultStore store = new ToolResultStore(PAGE, PAGE);
        String original = "а".repeat(PAGE - 1) + "😀";
        String refused = store.capture(original, false);
        assertFalse(store.hasResults());
        assertEquals("а".repeat(PAGE - 1), payload(refused));
        assertTrue(refused.length() <= PAGE + 600);
    }

    @Test
    void idsBelongOnlyToTheirStoreAndEachCaptureHasItsOwnSnapshot() throws Exception {
        ToolResultStore firstStore = new ToolResultStore(PAGE, 4000);
        ToolResultStore secondStore = new ToolResultStore(PAGE, 4000);
        String text = "а".repeat(600);
        String first = firstStore.capture(text, false);
        String another = firstStore.capture(text, true);
        String second = secondStore.capture(text, false);
        assertNotEquals(field(first, "result_id"), field(another, "result_id"));
        assertNotEquals(field(first, "result_id"), field(second, "result_id"));
        assertBadArguments(secondStore, arguments(field(first, "result_id"), 0));
        assertBadArguments(firstStore, arguments(field(second, "result_id"), 0));
        assertBadArguments(firstStore, arguments("unknown", 0));
        assertEquals("false", field(firstStore.tool().call(arguments(field(first, "result_id"), 0)).text(), "original_error"));
        assertTrue(firstStore.tool().call(arguments(field(another, "result_id"), 0)).text().contains("original_error: true"));
    }

    @Test
    void errorSnapshotIsClearlyMarkedAndReadingItSucceeds() throws Exception {
        ToolResultStore store = new ToolResultStore(PAGE, 3000);
        String original = "Ошибка инструмента\n".repeat(100);
        String first = store.capture(original, true);
        assertTrue(first.contains("original_error: true (исходный вызов завершился ошибкой)"));
        Tool.Result result = store.tool().call(arguments(field(first, "result_id"), PAGE));
        assertTrue(result.text().contains("original_error: true"));
        assertEquals(null, result.file());
        assertEquals(original, reconstruct(store, first));
    }

    @Test
    void referenceRetainsTheHandleWithoutPayloadAndToStringDoesNotRevealTheResult() throws Exception {
        ToolResultStore store = new ToolResultStore(PAGE, 2000);
        String original = "секретный текст ".repeat(80);
        ToolResultStore.Captured captured = store.captureResult(original, true);
        assertEquals(field(captured.text(), "result_id"), field(captured.reference(), "result_id"));
        assertEquals(Integer.toString(original.length()), field(captured.reference(), "total_chars"));
        assertTrue(captured.reference().contains("original_error: true"));
        assertTrue(captured.reference().contains("read_tool_result"));
        assertTrue(captured.reference().contains("offset: 0"));
        assertFalse(captured.reference().contains("секретный текст"));
        assertFalse(captured.toString().contains("секретный текст"));
        assertFalse(store.toString().contains("секретный текст"));
        assertFalse(store.tool().toString().contains("секретный текст"));
        assertEquals(original, reconstruct(store, store.tool().call(arguments(field(captured.reference(), "result_id"), 0)).text()));
    }

    @Test
    void readerContractIsReadOnlyAndStrict() {
        Tool tool = new ToolResultStore(PAGE, 1000).tool();
        assertEquals("read_tool_result", tool.name());
        assertTrue(tool.repeatableRead());
        assertFalse(tool.needsApproval());
        assertFalse(tool.modifies());
        assertEquals(false, tool.inputSchema().get("additionalProperties"));
        assertEquals(List.of("result_id", "offset"), tool.inputSchema().get("required"));
        assertTrue(tool.description().contains("UTF-16"));
        assertTrue(tool.description().contains("снимок"));
    }

    @Test
    void readerRejectsMissingExtraAndWrongTypedFields() {
        ToolResultStore store = new ToolResultStore(PAGE, 1000);
        String id = field(store.capture("а".repeat(600), false), "result_id");
        assertBadArguments(store, null);
        assertBadArguments(store, Map.of());
        assertBadArguments(store, Map.of("result_id", id));
        assertBadArguments(store, Map.of("offset", 0));
        assertBadArguments(store, Map.of("result_id", id, "offset", 0, "limit", 100));
        assertBadArguments(store, Map.of("result_id", 123, "offset", 0));
        assertBadArguments(store, arguments("", 0));
        assertBadArguments(store, arguments(" ", 0));
        Map<String, Object> nullId = new HashMap<>(arguments(id, 0));
        nullId.put("result_id", null);
        assertBadArguments(store, nullId);
    }

    @Test
    void readerRejectsFractionalNegativeNonFiniteAndOverflowOffsetsWithoutCoercion() {
        ToolResultStore store = new ToolResultStore(PAGE, 1000);
        String id = field(store.capture("а".repeat(600), false), "result_id");
        for (Object offset : List.of("0", true, -1, 0.5, new BigDecimal("512.00001"),
                new BigInteger("4294967296"), Long.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY, 601)) {
            assertBadArguments(store, arguments(id, offset));
        }
        Map<String, Object> nullOffset = new HashMap<>(arguments(id, 0));
        nullOffset.put("offset", null);
        assertBadArguments(store, nullOffset);
    }

    @Test
    void readerAcceptsExactJsonIntegersAndDoesNotConsumeRetention() throws Exception {
        ToolResultStore store = new ToolResultStore(PAGE, 1200);
        String first = store.capture("а".repeat(600), false);
        String id = field(first, "result_id");
        for (Object zero : List.of(0, 0L, new BigDecimal("0.0"), BigInteger.ZERO)) {
            assertEquals(first, store.tool().call(arguments(id, zero)).text());
        }
        for (int i = 0; i < 20; i++) {
            assertEquals("а".repeat(600), reconstruct(store, first));
        }
        assertTrue(store.capture("б".repeat(600), false).contains("result_id:"));
    }

    private static String reconstruct(ToolResultStore store, String first) throws Exception {
        String id = field(first, "result_id");
        StringBuilder result = new StringBuilder();
        String page = first;
        int offset = 0;
        while (true) {
            assertTrue(page.length() <= PAGE + 600);
            assertEquals(Integer.toString(offset), field(page, "offset"));
            String text = payload(page);
            assertTrue(text.length() <= PAGE);
            assertFalse(!text.isEmpty() && Character.isLowSurrogate(text.charAt(0)));
            assertFalse(!text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1)));
            result.append(text);
            offset += text.length();
            if (header(page).contains("\nEOF\n")) {
                assertEquals(Integer.toString(offset), field(page, "total_chars"));
                break;
            }
            int next = Integer.parseInt(field(page, "next_offset"));
            assertEquals(offset, next);
            assertFalse(text.isEmpty());
            page = store.tool().call(arguments(id, next)).text();
        }
        return result.toString();
    }

    private static Map<String, Object> arguments(String id, Object offset) {
        return Map.of("result_id", id, "offset", offset);
    }

    private static void assertBadArguments(ToolResultStore store, Map<String, Object> input) {
        ToolException error = assertThrows(ToolException.class, () -> store.tool().call(input));
        assertEquals(ToolUse.Problem.BAD_ARGUMENTS, error.problem());
    }

    private static String header(String page) {
        int end = page.indexOf("\n\n");
        return end < 0 ? page : page.substring(0, end + 1);
    }

    private static String field(String page, String key) {
        return header(page).lines().filter(line -> line.startsWith(key + ": "))
                .map(line -> line.substring(key.length() + 2)).findFirst().orElseThrow();
    }

    private static String payload(String page) {
        return page.substring(page.indexOf("\n\n") + 2);
    }
}
