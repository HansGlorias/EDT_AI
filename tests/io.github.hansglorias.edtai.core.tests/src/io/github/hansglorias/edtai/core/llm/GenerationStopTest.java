package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.chat.ChatHistoryFile;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.ChatSession;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolApprover;

class GenerationStopTest {

    @TempDir Path temp;

    private static final String TAG = "</function_results>";

    @Test
    void bothProtocolsCloseTheStreamKeepToolsAndSendOnlyTheCleanPrefixNextTime() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger requests = new AtomicInteger();
            AtomicInteger sent = new AtomicInteger();
            AtomicInteger writes = new AtomicInteger();
            CountDownLatch closed = new CountDownLatch(1);
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                int step = requests.getAndIncrement();
                if (step == 0) {
                    response.sse(List.of(text(protocol, "Проверяю проект."), call(protocol), done(protocol)), 0);
                } else if (step == 1) {
                    response.start(200, "text/event-stream");
                    response.write(call(protocol)); // Даже полностью полученный вызов испорченного шага не выполняется.
                    response.write(text(protocol, "Правка подтверждена инструментом.\n\n"));
                    try {
                        // Синтетический аналог message-6/10 из разбора: тег разрезан между SSE-событиями.
                        for (int i = 0; i < 1500; i++) {
                            response.write(text(protocol, "</function_"));
                            response.write(text(protocol, "results>"));
                            sent.incrementAndGet();
                            Thread.sleep(1);
                        }
                        response.write(done(protocol));
                        response.end();
                    } catch (java.io.IOException expected) {
                        closed.countDown();
                    }
                } else {
                    response.sse(List.of(text(protocol, "Продолжение по сохранённым фактам."), done(protocol)), 0);
                }
            })) {
                Tool edit = new Tool() {
                    @Override public String name() { return "edit_module"; }
                    @Override public String description() { return "Синтетическая правка"; }
                    @Override public Map<String, Object> inputSchema() { return Json.object("type", "object"); }
                    @Override public String summary(Map<String, Object> input) { return "Правка тестового модуля"; }
                    @Override public boolean modifies() { return true; }
                    @Override public Result call(Map<String, Object> input) {
                        writes.incrementAndGet();
                        return new Result("Правка выполнена", "изменено");
                    }
                };
                ChatSession session = new ChatSession(new ModelChatProvider(() -> connection(server, protocol),
                        new ModelClient(), problems -> List.of(edit), ToolApprover.ALLOW_ALL));
                ChatMessage failure = session.send("Внеси правку").get(10, TimeUnit.SECONDS);
                assertEquals(ChatRole.ERROR, failure.role());
                assertTrue(failure.text().contains("зациклилась"));
                assertEquals(2, requests.get(), "без автоматического восстановления");
                assertTrue(closed.await(5, TimeUnit.SECONDS), "HTTP body должен быть закрыт до конца потока");
                assertTrue(sent.get() < 500, "остановка до тысяч тегов, sent=" + sent.get());
                ChatMessage partial = session.messages().get(1);
                assertEquals(ChatMessage.Status.STOPPED, partial.status());
                assertEquals("Проверяю проект.\n\nПравка подтверждена инструментом.", partial.text());
                assertEquals(1, partial.tools().size());
                assertEquals(ToolUse.Status.DONE, partial.tools().get(0).status());
                assertEquals(1, writes.get());
                assertFalse(session.isBusy());
                ChatHistoryFile saved = new ChatHistoryFile(temp.resolve(protocol.name() + ".json"));
                saved.save(session.messages());
                assertFalse(Files.readString(saved.path()).contains(TAG));
                session.restore(saved.load());
                session.send("Что уже выполнено?").get(10, TimeUnit.SECONDS);
                String next = server.requests().get(2).body();
                assertTrue(next.contains("Правка подтверждена инструментом."));
                assertTrue(next.contains("edit_module"));
                assertFalse(next.contains(TAG));
                assertFalse(next.contains("зациклилась"), "ошибка не передаётся как речь модели");
                assertEquals(1, writes.get(), "выполненная операция не повторяется");
            }
        }
    }

    @Test
    void plainAndNonStreamingRepliesUseTheSameProtection() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            for (boolean streaming : new boolean[] { true, false }) {
                try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                    String broken = "Полезный префикс.\n\n" + TAG.repeat(1500);
                    if (streaming) response.sse(List.of(text(protocol, broken), done(protocol)), 0);
                    else response.send(200, "application/json", Json.write(protocol == Protocol.OPENAI_COMPATIBLE
                            ? Json.object("choices", List.of(Json.object("message", Json.object("content", broken))))
                            : Json.object("content", List.of(Json.object("type", "text", "text", broken)))));
                })) {
                    ChatSession session = new ChatSession(new ModelChatProvider(() -> connection(server, protocol), new ModelClient()));
                    assertEquals(ChatRole.ERROR, session.send("Вопрос").get(10, TimeUnit.SECONDS).role());
                    assertEquals("Полезный префикс.", session.messages().get(1).text());
                    assertEquals(1, server.requests().size());
                }
            }
        }
    }

    @Test
    void clientReportsProtectiveStopAsDistinctFailure() throws Exception {
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                call(Protocol.OPENAI_COMPATIBLE), text(Protocol.OPENAI_COMPATIBLE, TAG.repeat(1500)),
                done(Protocol.OPENAI_COMPATIBLE)), 0))) {
            ExecutionException error = assertThrows(ExecutionException.class, () -> new ModelClient()
                    .stream(connection(server, Protocol.OPENAI_COMPATIBLE), List.of(ChatMessage.of(ChatRole.USER, "Вопрос")), part -> { })
                    .get(10, TimeUnit.SECONDS));
            assertInstanceOf(GenerationStoppedException.class, error.getCause());
            assertEquals(1, server.requests().size());
        }
    }

    private static ModelConnection connection(FakeModelServer server, Protocol protocol) {
        return new ModelConnection(protocol, server.url(), "test-model", "", 0, "", new TrustSettings(false, Optional.empty()));
    }

    private static String text(Protocol protocol, String content) {
        return protocol == Protocol.OPENAI_COMPATIBLE
                ? "data: " + Json.write(Json.object("choices", List.of(Json.object("delta", Json.object("content", content))))) + "\n\n"
                : "event: content_block_delta\ndata: " + Json.write(Json.object("type", "content_block_delta", "index", 1,
                        "delta", Json.object("type", "text_delta", "text", content))) + "\n\n";
    }

    private static String call(Protocol protocol) {
        return protocol == Protocol.OPENAI_COMPATIBLE
                ? "data: " + Json.write(Json.object("choices", List.of(Json.object("delta", Json.object("tool_calls", List.of(
                        Json.object("index", 0, "id", "edit", "function", Json.object("name", "edit_module", "arguments", "{}")))))))) + "\n\n"
                : "event: content_block_start\ndata: " + Json.write(Json.object("type", "content_block_start", "index", 0,
                        "content_block", Json.object("type", "tool_use", "id", "edit", "name", "edit_module", "input", Map.of()))) + "\n\n";
    }

    private static String done(Protocol protocol) {
        return protocol == Protocol.OPENAI_COMPATIBLE ? "data: [DONE]\n\n" : "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
    }
}
