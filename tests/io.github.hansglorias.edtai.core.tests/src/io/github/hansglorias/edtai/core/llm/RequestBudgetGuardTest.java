package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolApprover;

class RequestBudgetGuardTest {
    private static final List<ChatMessage> QUESTION = List.of(ChatMessage.of(ChatRole.USER, "вопрос"));

    private static ModelConnection connection(Protocol protocol, String url, int window, int maxTokens) {
        return new ModelConnection(protocol, url, "m", "test-secret-not-real", maxTokens, "", new TrustSettings(false, Optional.empty()), window);
    }

    private static void answer(Protocol protocol, FakeModelServer.Response response) throws Exception {
        response.send(200, "application/json", protocol == Protocol.ANTHROPIC
                ? "{\"content\":[{\"type\":\"text\",\"text\":\"готово\"}]}"
                : "{\"choices\":[{\"message\":{\"content\":\"готово\"}}]}");
    }

    private static ModelException localFailure(CompletableFuture<?> future) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        ModelException cause = assertInstanceOf(ModelException.class, error.getCause());
        assertFalse(cause.contextOverflow());
        assertFalse(cause.rejectsTools());
        assertFalse(cause.getMessage().contains("test-secret-not-real"));
        assertFalse(cause.getMessage().contains("СЕКРЕТНЫЙ_КОД"));
        return cause;
    }

    @Test
    void reservesActualOutputForBothProtocolsAndWindows() throws Exception {
        // Один слушатель на всю матрицу: Windows может быстро переиспользовать
        // ephemeral port прежнего сервера при ещё живом HTTP-соединении.
        try (FakeModelServer server = FakeModelServer.http()) {
            int sent = 0;
            for (Protocol protocol : Protocol.values()) {
                server.handler((request, response) -> answer(protocol, response));
                for (int window : List.of(8192, 16384, 32768)) {
                    for (int max : List.of(0, 512, window, window + 1)) {
                        ModelConnection connection = connection(protocol, server.url(), window, max);
                        PreparedRequest request = PreparedRequest.prepare(connection, QUESTION, List.of(), List.of());
                        int reserve = max > 0 ? max : protocol == Protocol.ANTHROPIC ? 8192 : Math.min(4096, window / 4);
                        assertEquals((window - Math.min(window, reserve)) * 3, RequestBudget.chars(connection));
                        if (reserve >= window) {
                            localFailure(new ModelClient().request(connection, request, text -> {}));
                            assertEquals(sent, server.requests().size());
                        } else {
                            assertEquals("готово", new ModelClient().request(connection, request, text -> {}).get(10, TimeUnit.SECONDS).text());
                            assertEquals(++sent, server.requests().size());
                            FakeModelServer.Request actual = server.requests().get(sent - 1);
                            assertEquals(request.body(), actual.body());
                            assertEquals(max > 0 ? Integer.valueOf(max) : protocol == Protocol.ANTHROPIC ? Integer.valueOf(8192) : null,
                                    Json.at(Json.parse(actual.body()), "max_tokens") instanceof Number n ? n.intValue() : null);
                        }
                    }
                }
            }
        }
    }

    @Test
    void rejectsUnfittableQuestionAndSchemasWithoutHttpOrTools() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            for (int window : List.of(8192, 16384, 32768)) {
                try (FakeModelServer server = FakeModelServer.http()) {
                    AtomicInteger writes = new AtomicInteger();
                    Tool schema = tool(writes, "schema".repeat(window));
                    ModelConnection connection = connection(protocol, server.url(), window, 512);
                    ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient(),
                            problems -> List.of(schema), ToolApprover.ALLOW_ALL);
                    localFailure(provider.reply(QUESTION, text -> {}));
                    localFailure(new ModelClient().stream(connection, List.of(ChatMessage.of(ChatRole.USER,
                            "СЕКРЕТНЫЙ_КОД".repeat(window))), text -> {}));
                    assertEquals(0, server.requests().size());
                    assertEquals(0, writes.get());
                }
            }
        }
    }

    @Test
    void imagesUseEstimateRatherThanBase64Bytes() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            try (FakeModelServer server = FakeModelServer.http()) {
                server.handler((request, response) -> answer(protocol, response));
                ModelConnection connection = connection(protocol, server.url(), 8192, 512);
                Attachment image = Attachment.png("shot.png", new byte[100_000]);
                ChatMessage message = ChatMessage.of(ChatRole.USER, "вопрос").withAttachments(List.of(image));
                PreparedRequest one = PreparedRequest.prepare(connection, List.of(message), List.of(), List.of());
                assertTrue(one.utf8Bytes() > RequestBudget.chars(connection));
                new ModelClient().request(connection, one, text -> {}).get(10, TimeUnit.SECONDS);
                PreparedRequest many = PreparedRequest.prepare(connection, List.of(message.withAttachments(java.util.Collections.nCopies(6, image))), List.of(), List.of());
                localFailure(new ModelClient().request(connection, many, text -> {}));
                assertEquals(1, server.requests().size());
            }
        }
    }

    @Test
    void serverOverflowCannotBypassReducedBudgetOrRepeatWrite() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            try (FakeModelServer server = FakeModelServer.http()) {
                AtomicInteger requests = new AtomicInteger();
                AtomicInteger writes = new AtomicInteger();
                server.handler((request, response) -> {
                    if (requests.getAndIncrement() == 0) {
                        Object call = ProtocolAdapter.of(protocol).assistantMessage(new ModelReply("", List.of(new ToolCall("write1", "write", "{}")), false));
                        response.send(200, "application/json", Json.write(protocol == Protocol.ANTHROPIC ? call
                                : Json.object("choices", List.of(Json.object("message", call)))));
                    } else {
                        assertTrue(request.body().contains("write1"));
                        response.send(400, "application/json", "{\"error\":{\"message\":\"maximum context length exceeded\",\"code\":\"context_length_exceeded\"}}");
                    }
                });
                ModelConnection connection = connection(protocol, server.url(), 8192, 512);
                ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient(),
                        problems -> List.of(tool(writes, "")), ToolApprover.ALLOW_ALL);
                ModelException failure = localFailure(provider.reply(List.of(ChatMessage.of(ChatRole.USER, "я".repeat(10000))), text -> {}));
                assertTrue(failure.getMessage().contains("11520"));
                assertEquals(2, requests.get());
                assertEquals(1, writes.get());
            }
        }
    }

    @Test
    void invalidMainReserveDoesNotCompactButSummaryUsesItsOwnLimit() throws Exception {
        try (FakeModelServer server = FakeModelServer.http()) {
            server.handler((request, response) -> answer(Protocol.ANTHROPIC, response));
            ModelConnection connection = connection(Protocol.ANTHROPIC, server.url(), 8192, 0);
            ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient());
            List<ChatMessage> messages = List.of(ChatMessage.of(ChatRole.USER, "сохранить первый вопрос"),
                    ChatMessage.of(ChatRole.ASSISTANT, "первый ответ"), ChatMessage.of(ChatRole.USER, "следующий вопрос"));
            assertEquals(0, provider.compactCount(messages));
            ModelException invalid = localFailure(provider.reply(messages, text -> {}));
            assertFalse(invalid.getMessage().contains("вход ≈0"));
            assertEquals(0, server.requests().size());
            provider.summarize(messages).get(10, TimeUnit.SECONDS);
            assertTrue(server.requests().get(0).body().contains("сохранить первый вопрос"));
            assertEquals(2048L, Json.at(Json.parse(server.requests().get(0).body()), "max_tokens"));
            ModelConnection large = connection(Protocol.ANTHROPIC, server.url(), Integer.MAX_VALUE, 0);
            new ModelChatProvider(() -> large, new ModelClient()).summarize(messages).get(10, TimeUnit.SECONDS);
            assertTrue(server.requests().get(1).body().contains("сохранить первый вопрос"));
        }
    }

    @Test
    void serviceRequestsAlsoRejectOversizedSystemBeforeHttp() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            try (FakeModelServer server = FakeModelServer.http()) {
                ModelConnection connection = connection(protocol, server.url(), 8192, 512).withSystemPromptPart("СЕКРЕТНЫЙ_КОД".repeat(5000));
                ModelClient client = new ModelClient();
                ModelChatProvider provider = new ModelChatProvider(() -> connection, client);
                localFailure(client.check(connection));
                localFailure(provider.title(QUESTION));
                localFailure(provider.summarize(QUESTION));
                assertEquals(0, server.requests().size());
            }
        }
    }

    private static Tool tool(AtomicInteger writes, String description) {
        return new Tool() {
            public String name() { return "write"; }
            public String description() { return description; }
            public Map<String, Object> inputSchema() { return Json.object("type", "object"); }
            public String summary(Map<String, Object> input) { return "запись"; }
            public Result call(Map<String, Object> input) { writes.incrementAndGet(); return new Result("done", "готово"); }
        };
    }
}
