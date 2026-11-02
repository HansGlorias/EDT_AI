package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import io.github.hansglorias.edtai.core.chat.TokenUsage;
import io.github.hansglorias.edtai.core.chat.TokenUsage.Source;
import io.github.hansglorias.edtai.core.json.Json;

class PartialUsageTest {
    @Test void partialJsonBothProtocols() throws Exception {
        for (boolean anthropic : new boolean[] { false, true }) {
            ProtocolAdapter adapter = anthropic ? new AnthropicAdapter() : new OpenAiAdapter();
            String body = anthropic ? "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]" : "\"choices\":[{\"message\":{\"content\":\"ok\"}}]";
            for (boolean input : new boolean[] { false, true }) {
                String field = anthropic ? (input ? "input_tokens" : "output_tokens") : (input ? "prompt_tokens" : "completion_tokens");
                TokenUsage usage = adapter.fullResponse(Json.parseObject("{" + body + ",\"usage\":{\"" + field + "\":0}}")).usage();
                assertEquals(input ? Source.SERVER : Source.UNKNOWN, usage.inputSource());
                assertEquals(input ? Source.UNKNOWN : Source.SERVER, usage.outputSource());
                assertEquals(Source.UNKNOWN, usage.contextSource());
                assertTrue(usage.describeStep().contains("неизвестно"));
                TokenUsage filled = usage.fillMissing(TokenUsage.step(10, 20, true));
                assertEquals(input ? 0 : 10, filled.input());
                assertEquals(input ? 20 : 0, filled.output());
                assertEquals(Source.MIXED, filled.contextSource());
            }
            assertNull(adapter.fullResponse(Json.parseObject("{" + body + "}")).usage());
        }
    }

    @Test void partialStreamsBothProtocolsAndEvents() throws Exception {
        for (boolean anthropic : new boolean[] { false, true }) {
            ProtocolAdapter adapter = anthropic ? new AnthropicAdapter() : new OpenAiAdapter();
            for (boolean input : new boolean[] { false, true }) {
                StreamState state = new StreamState(text -> {});
                String field = anthropic ? (input ? "input_tokens" : "output_tokens") : (input ? "prompt_tokens" : "completion_tokens");
                String usage = "\"usage\":{\"" + field + "\":0}";
                adapter.onEvent(new SseEvent(anthropic ? "message_delta" : "", "{" + usage + "}"), state);
                assertEquals(input ? Source.SERVER : Source.UNKNOWN, state.reply().usage().inputSource());
                assertEquals(input ? Source.UNKNOWN : Source.SERVER, state.reply().usage().outputSource());
            }
            StreamState state = new StreamState(text -> {});
            assertNull(state.reply().usage());
            state.usage(0, -1);
            state.usage(-1, 0);
            assertEquals(TokenUsage.step(0, 0, false), state.reply().usage());
        }
    }

    @Test void sumsAndContextSourcesAreIndependent() {
        TokenUsage sum = TokenUsage.step(10, 20, true).plus(TokenUsage.step(0, 5, false));
        assertEquals(Source.MIXED, sum.inputSource());
        assertEquals(Source.MIXED, sum.outputSource());
        assertEquals(Source.SERVER, sum.contextSource());
        assertTrue(sum.describe(100).startsWith("контекст 5 из 100"));
        assertEquals(Source.UNKNOWN, sum.plus(TokenUsage.serverStep(-1, 0)).inputSource());
        assertThrows(IllegalArgumentException.class, () -> TokenUsage.step(-1, 0, false));
    }
}
