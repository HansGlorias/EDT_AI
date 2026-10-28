package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;

class ThinkFilterTest {

    @Test
    void thinkingIsHiddenEvenWhenTagsAreSplit() {
        ThinkFilter filter = new ThinkFilter();
        StringBuilder shown = new StringBuilder();
        for (String part : List.of("<th", "ink>Надо пос", "читать.</thi", "nk>\n\nОтвет", ": 3 <b", "> и <", "/think> не тег")) {
            shown.append(filter.feed(part));
        }
        shown.append(filter.finish());
        assertEquals("Ответ: 3 <b> и </think> не тег", shown.toString());

        ThinkFilter unfinished = new ThinkFilter();
        assertEquals("Текст ", unfinished.feed("Текст <thi"));
        assertEquals("<thi", unfinished.finish(), "незакрытое начало тега в конце — это текст");
    }

    @Test
    void stripRemovesBlocksAndOrphanClose() {
        assertEquals("Ответ.", ThinkFilter.strip("<think>рассуждение</think>\n\nОтвет."));
        assertEquals("Ответ.", ThinkFilter.strip("рассуждение без открывающего тега\n</think>\nОтвет."));
        assertEquals("Без рассуждений.", ThinkFilter.strip("Без рассуждений."));
        assertEquals("", ThinkFilter.strip("<think>оборвалось на середине"));
    }

    @Test
    void streamedAnswerWithoutThinkingAndTruncationNotice() throws Exception {
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"<think>Считаю\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"…</think>Длинный отв\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n", "data: [DONE]\n\n"), 0))) {
            List<String> parts = new CopyOnWriteArrayList<>();
            ModelConnection connection = new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "m", "", 0, "",
                    new TrustSettings(false, Optional.empty()));

            String answer = new ModelClient().stream(connection, List.of(ChatMessage.of(ChatRole.USER, "?")), parts::add)
                    .get(10, TimeUnit.SECONDS);

            assertEquals("Длинный отв\n\n" + ModelClient.TRUNCATED, answer);
            assertEquals(answer, String.join("", parts));
            assertTrue(server.requests().get(0).body().contains("\"stream\":true"));
        }
    }

    @Test
    void anthropicMaxTokensIsReported() throws IOException, Exception {
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Нача\"}}\n\n",
                "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}\n\n",
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"), 0))) {
            ModelConnection connection = new ModelConnection(Protocol.ANTHROPIC, server.url(), "m", "", 0, "",
                    new TrustSettings(false, Optional.empty()));

            String answer = new ModelClient().stream(connection, List.of(ChatMessage.of(ChatRole.USER, "?")), part -> { })
                    .get(10, TimeUnit.SECONDS);

            assertEquals("Нача\n\n" + ModelClient.TRUNCATED, answer);
        }
    }
}
