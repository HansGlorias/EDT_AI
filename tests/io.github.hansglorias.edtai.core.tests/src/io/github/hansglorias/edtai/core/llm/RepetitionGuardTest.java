package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RepetitionGuardTest {

    @Test
    void exportFailurePatternsStopWithTheSamePrefixAtEveryChunkSize() {
        for (String cycle : new String[] { "</function_results>", "</function_calls>\n</function_results>\n</invoke>\n",
                "Повторяющийся рабочий комментарий. ", "я", "😀" }) {
            String prefix = "Найден полезный результат.\n\n";
            String stream = prefix + cycle.repeat(1500) + "не должен попасть в историю";
            for (int chunk : new int[] { 1, 2, 7, 29, 257, stream.length() }) {
                StreamState state = new StreamState(part -> { });
                GenerationStoppedException stopped = assertThrows(GenerationStoppedException.class, () -> {
                    for (int offset = 0; offset < stream.length(); offset += chunk) {
                        state.text(stream.substring(offset, Math.min(stream.length(), offset + chunk)));
                    }
                });
                assertEquals(prefix.stripTrailing(), stopped.safeText(), "cycle=" + cycle + ", chunk=" + chunk);
            }
        }
    }

    @Test
    void ordinaryXmlCodeQuotesAndWhitespaceRemainExact() {
        String repeated = "</function_results>".repeat(1500);
        for (String sample : new String[] {
                "XML: <root><item>значение</item><item>другое</item></root>",
                "Пример: " + "</function_results>".repeat(10),
                "```xml\r\n" + repeated + "\r\n```\r\nГотово.",
                "   ~~~~xml\n" + repeated + "\n~~~\n" + repeated + "\n~~~~\nГотово.",
                "> " + repeated + "\nГотово.",
                "    " + repeated + "\nГотово.",
                "Пример: ``" + repeated + "``\nГотово.",
                "\n".repeat(1000),
                java.util.stream.IntStream.range(0, 1500).mapToObj(i -> "<item id=\"" + i + "\"/>\n")
                        .collect(java.util.stream.Collectors.joining()) }) {
            StringBuilder shown = new StringBuilder();
            StreamState state = new StreamState(shown::append);
            for (int offset = 0; offset < sample.length(); offset += 7) {
                state.text(sample.substring(offset, Math.min(sample.length(), offset + 7)));
            }
            assertEquals(sample, state.reply().text());
            assertEquals(sample, shown.toString());
        }
    }

    @Test
    void closingFenceAndInlineExampleDoNotHideFollowingDegeneration() {
        for (String prefix : new String[] { "```xml\n<root/>\n```\n", "`<root/>` ", "Тег `<root/>` " }) {
            StreamState state = new StreamState(part -> { });
            GenerationStoppedException stopped = assertThrows(GenerationStoppedException.class,
                    () -> state.text(prefix + "</function_results>".repeat(1500)));
            assertEquals(prefix.stripTrailing(), stopped.safeText());
        }
    }

    @Test
    void longestSupportedPeriodStillStopsWithinAFewThousandCharacters() {
        String unit = java.util.stream.IntStream.range(0, RepetitionGuard.MAX_PERIOD)
                .mapToObj(i -> String.valueOf((char) (0x400 + i))).collect(java.util.stream.Collectors.joining());
        RepetitionGuard guard = new RepetitionGuard();
        int received = 0;
        while (guard.append(unit) < 0) {
            received += unit.length();
            assertTrue(received < RepetitionGuard.MAX_PERIOD * RepetitionGuard.MIN_COPIES);
        }
    }
}
