package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.TokenUsage;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.Tool;

class PreparedRequestTest {
    private static final String TOKEN = "test-token-not-real";

    private static ModelConnection connection(Protocol protocol, String url) {
        return new ModelConnection(protocol, url, "m", TOKEN, 0, "Правила проекта", new TrustSettings(false, Optional.empty()));
    }

    @Test
    void sendsFrozenPayloadAndMetricsForBothProtocols() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            try (FakeModelServer server = FakeModelServer.http()) {
                ModelConnection connection = connection(protocol, server.url());
                Attachment image = Attachment.png("shot.png", new byte[9000]);
                ChatMessage message = ChatMessage.of(ChatRole.USER, "Процедура Тест()\nСообщить(\"😀\");\nКонецПроцедуры")
                        .withAttachments(List.of(image));
                Map<String, Object> property = new LinkedHashMap<>(Json.object("type", "string"));
                Map<String, Object> schema = new LinkedHashMap<>(Json.object("type", "object", "properties", new LinkedHashMap<>(Map.of("module", property))));
                AtomicInteger schemaCalls = new AtomicInteger();
                Tool tool = tool(schema, schemaCalls);
                ProtocolAdapter adapter = ProtocolAdapter.of(protocol);
                List<Object> result = new ArrayList<>(adapter.toolResults(List.of(new ProtocolAdapter.ToolResult(
                        new ToolCall("call1", "read_module", "{}"), "Текст результата", false))));
                Object assistant = new LinkedHashMap<>(Json.asObject(adapter.assistantMessage(new ModelReply("", List.of(new ToolCall("call1", "read_module", "{}")), false))));
                result.set(0, new LinkedHashMap<>(Json.asObject(result.get(0))));
                List<Object> extra = new ArrayList<>();
                extra.add(assistant);
                extra.addAll(result);
                List<ChatMessage> history = new ArrayList<>(List.of(message));
                PreparedRequest prepared = PreparedRequest.prepare(connection, history, extra, List.of(tool));
                String expected = prepared.body();
                int expectedSize = expected.length() + RequestBudget.IMAGE_CHARS - image.data().length();
                assertEquals(1, schemaCalls.get());
                assertEquals(expected.length(), prepared.utf16Chars());
                assertEquals(expected.getBytes(StandardCharsets.UTF_8).length, prepared.utf8Bytes());
                assertTrue(prepared.utf8Bytes() > prepared.utf16Chars());
                assertEquals(expectedSize, prepared.estimatedInputChars());
                assertEquals(expectedSize / RequestBudget.CHARS_PER_TOKEN, prepared.estimatedInputTokens());
                assertEquals(1, prepared.schemaCount());
                assertEquals(protocol == Protocol.ANTHROPIC ? Optional.of(8192) : Optional.empty(),
                        prepared.maxTokens().isPresent() ? Optional.of(prepared.maxTokens().getAsInt()) : Optional.empty());
                assertTrue(expected.contains("Правила проекта"));
                assertTrue(expected.contains("Текст результата"));
                assertTrue(expected.contains("read_module"));
                assertFalse(prepared.toString().contains(TOKEN));
                assertFalse(prepared.toString().contains("Правила проекта"));
                assertFalse(prepared.toString().contains(image.data()));
                property.put("type", "number");
                property.put("description", "Изменённая схема".repeat(1000));
                Json.asObject(assistant).put("changed", "Изменено после подготовки");
                Json.asObject(result.get(0)).put("content", "Результат изменён");
                history.clear();
                extra.clear();
                server.handler((request, response) -> response.send(200, "application/json", protocol == Protocol.ANTHROPIC
                        ? "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}"
                        : "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
                assertEquals("ok", new ModelClient().request(connection, prepared, text -> {}).get(10, TimeUnit.SECONDS).text());
                assertEquals(expected, server.requests().get(0).body());
                assertEquals(expectedSize, prepared.estimatedInputChars());
                assertEquals(1, schemaCalls.get());
                assertEquals(321, PreparedRequest.prepare(connection.withMaxTokens(321), List.of(message), List.of(), List.of()).maxTokens().orElseThrow());
            }
        }
    }

    @Test
    void omittedMessagesDoNotAddPhantomImageCost() {
        Attachment image = Attachment.png("shot.png", new byte[9000]);
        for (Protocol protocol : Protocol.values()) {
            ModelConnection connection = connection(protocol, "http://localhost");
            for (ChatRole role : List.of(ChatRole.ERROR, ChatRole.ASSISTANT, ChatRole.USER)) {
                String text = role == ChatRole.ERROR ? "ошибка" : "";
                ChatMessage omitted = new ChatMessage(role, text, Instant.EPOCH, ChatMessage.Status.DONE).withAttachments(List.of(image));
                PreparedRequest prepared = PreparedRequest.prepare(connection, List.of(omitted, ChatMessage.of(ChatRole.USER, "вопрос")), List.of(), List.of());
                assertFalse(prepared.body().contains(image.data()));
                assertEquals(prepared.utf16Chars(), prepared.estimatedInputChars());
            }
        }
    }

    @Test
    void compatibilityRequestAndPlainProviderFailViaFuture() {
        ModelConnection connection = connection(Protocol.OPENAI_COMPATIBLE, "http://localhost");
        Tool broken = tool(Json.object("type", new Object()), new AtomicInteger());
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> new ModelClient().request(connection, List.of(ChatMessage.of(ChatRole.USER, "вопрос")), List.of(), List.of(broken), part -> {}).get());
        assertInstanceOf(ModelException.class, error.getCause());
        error = assertThrows(ExecutionException.class, () -> new ModelChatProvider(() -> connection, new ModelClient())
                .reply(java.util.Arrays.asList((ChatMessage) null), part -> {}).get());
        assertInstanceOf(ModelException.class, error.getCause());
    }

    @Test
    void plainProviderFillsOnlyMissingUsage() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            try (FakeModelServer server = FakeModelServer.http()) {
                server.handler((request, response) -> response.send(200, "application/json", protocol == Protocol.ANTHROPIC
                        ? "{\"content\":[{\"type\":\"text\",\"text\":\"Ответ длиннее трёх символов\"}],\"usage\":{\"output_tokens\":0}}"
                        : "{\"choices\":[{\"message\":{\"content\":\"Ответ длиннее трёх символов\"}}],\"usage\":{\"completion_tokens\":0}}"));
                List<TokenUsage> usages = new ArrayList<>();
                new ModelChatProvider(() -> connection(protocol, server.url()), new ModelClient()).reply(
                        List.of(ChatMessage.of(ChatRole.USER, "вопрос")), part -> {}, tool -> {}, usages::add).get(10, TimeUnit.SECONDS);
                assertEquals(2, usages.size(), "перед запросом — оценка его размера, потом итог");
                assertEquals(server.requests().get(0).body().length() / RequestBudget.CHARS_PER_TOKEN, usages.get(0).context());
                TokenUsage usage = usages.get(1);
                assertEquals(TokenUsage.Source.ESTIMATE, usage.inputSource());
                assertEquals(server.requests().get(0).body().length() / RequestBudget.CHARS_PER_TOKEN, usage.input());
                assertEquals(TokenUsage.Source.SERVER, usage.outputSource());
                assertEquals(0, usage.output());
                assertEquals(TokenUsage.Source.MIXED, usage.contextSource());
            }
        }
    }

    @Test
    void agentFallbackUsesSentSnapshotWhenSchemaChangesDuringResponse() throws Exception {
        Map<String, Object> schema = new LinkedHashMap<>(Json.object("type", "object"));
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger callsAtSend = new AtomicInteger();
        try (FakeModelServer server = FakeModelServer.http()) {
            server.handler((request, response) -> {
                callsAtSend.set(calls.get());
                schema.put("description", "Изменение после отправки".repeat(1000));
                response.send(200, "application/json", "{\"choices\":[{\"message\":{\"content\":\"Готово\"}}]}");
            });
            List<TokenUsage> usages = new ArrayList<>();
            new ModelChatProvider(() -> connection(Protocol.OPENAI_COMPATIBLE, server.url()), new ModelClient(),
                    problems -> List.of(tool(schema, calls)), io.github.hansglorias.edtai.core.tools.ToolApprover.ALLOW_ALL)
                    .reply(List.of(ChatMessage.of(ChatRole.USER, "вопрос")), part -> {}, use -> {}, usages::add).get(10, TimeUnit.SECONDS);
            assertEquals(callsAtSend.get(), calls.get(), "после отправки схема больше не запрашивается");
            assertEquals(server.requests().get(0).body().length() / RequestBudget.CHARS_PER_TOKEN, usages.get(1).input());
        }
    }

    private static Tool tool(Map<String, Object> schema, AtomicInteger calls) {
        return new Tool() {
            public String name() { return "read_module"; }
            public String description() { return "Чтение модуля"; }
            public Map<String, Object> inputSchema() { calls.incrementAndGet(); return schema; }
            public String summary(Map<String, Object> input) { return "Чтение"; }
            public Result call(Map<String, Object> input) { return new Result("", ""); }
        };
    }
}
