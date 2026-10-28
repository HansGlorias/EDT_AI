package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SseParserTest {

    @Test
    void parsesEventsSeparatedByBlankLines() {
        List<SseEvent> events = parse("event: a", "data: {\"x\":1}", "", "data:без пробела", "", "");

        assertEquals(List.of(new SseEvent("a", "{\"x\":1}"), new SseEvent("", "без пробела")), events);
    }

    @Test
    void joinsMultilineDataAndSkipsComments() {
        List<SseEvent> events = parse(": keep-alive", "data: one", "data:  two", "id: 5", "retry: 100", "");

        assertEquals(List.of(new SseEvent("", "one\n two")), events);
    }

    @Test
    void eventWithoutDataIsSkipped() {
        assertEquals(List.of(), parse("event: ping", ""));
    }

    @Test
    void lastEventWithoutTrailingBlankLineIsKept() {
        SseParser parser = new SseParser();
        assertNull(parser.line("data: [DONE]"));

        assertEquals(new SseEvent("", "[DONE]"), parser.finish());
        assertNull(parser.finish());
    }

    private static List<SseEvent> parse(String... lines) {
        SseParser parser = new SseParser();
        List<SseEvent> events = new ArrayList<>();
        for (String line : lines) {
            SseEvent event = parser.line(line);
            if (event != null) {
                events.add(event);
            }
        }
        return events;
    }
}
