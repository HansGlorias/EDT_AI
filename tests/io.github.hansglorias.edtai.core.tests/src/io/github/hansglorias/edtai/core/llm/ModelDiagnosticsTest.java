package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Provenance;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.RequestType;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Status;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolApprover;

class ModelDiagnosticsTest {
    private static final List<ChatMessage> HISTORY = List.of(ChatMessage.of(ChatRole.USER, "секретный код я😀"));
    private static ModelConnection connection(Protocol protocol, String url) {
        return new ModelConnection(protocol, url, "private-model", "private-token", 512, "private-system", new TrustSettings(false, Optional.empty()));
    }
    private static String full(Protocol protocol, String usage) {
        return protocol == Protocol.ANTHROPIC
                ? "{\"content\":[{\"type\":\"text\",\"text\":\"ответ я😀\"}]" + usage + "}"
                : "{\"choices\":[{\"message\":{\"content\":\"ответ я😀\"}}]" + usage + "}";
    }
    private static void fail(CompletableFuture<?> future) {
        assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
    }

    @Test
    void fullAndStreamBothProtocolsMeasureExactBodyBytesAndPartialUsage() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            for (boolean stream : List.of(false, true)) {
                List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
                String response;
                if (stream) {
                    response = protocol == Protocol.ANTHROPIC
                            ? "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":0}}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ответ я😀\"}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                            : "data: {\"choices\":[{\"delta\":{\"content\":\"ответ я😀\"}}],\"usage\":{\"prompt_tokens\":0}}\n\ndata: [DONE]\n\n";
                } else response = full(protocol, protocol == Protocol.ANTHROPIC ? ",\"usage\":{\"input_tokens\":0}" : ",\"usage\":{\"prompt_tokens\":0}");
                try (FakeModelServer server = FakeModelServer.http()) {
                    server.handler((request, reply) -> reply.send(200, stream ? "text/event-stream" : "application/json", response));
                    ModelClient client = new ModelClient(events::add);
                    PreparedRequest prepared = PreparedRequest.prepare(connection(protocol, server.url()), HISTORY, List.of(), List.of());
                    client.request(connection(protocol, server.url()), prepared, text -> {}).thenAccept(reply -> assertEquals(1, events.size())).get(10, TimeUnit.SECONDS);
                    assertEquals(1, events.size());
                    DiagnosticEvent event = events.get(0);
                    assertEquals(Status.SUCCESS, event.status());
                    assertEquals((long) prepared.utf8Bytes(), event.requestBytes());
                    assertEquals((long) response.getBytes(StandardCharsets.UTF_8).length, event.responseBytes());
                    assertEquals(0L, event.inputTokens().value());
                    assertEquals(Provenance.SERVER, event.inputTokens().provenance());
                    assertEquals(Provenance.ESTIMATE, event.outputTokens().provenance());
                    assertTrue(event.outputTokens().value() > 0);
                    assertEquals(0, event.toolCalls());
                    assertFalse(event.toString().contains("private"));
                    assertFalse(event.toString().contains("секретный"));
                }
            }
        }
    }

    @Test
    void successWithoutUsageIsEstimatedAndCompleteServerZeroRemainsExact() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            for (boolean serverUsage : List.of(false, true)) {
                List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
                try (FakeModelServer server = FakeModelServer.http()) {
                    String usage = !serverUsage ? "" : protocol == Protocol.ANTHROPIC
                            ? ",\"usage\":{\"input_tokens\":0,\"output_tokens\":0}"
                            : ",\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}";
                    server.handler((request, response) -> response.send(200, "application/json", full(protocol, usage)));
                    ModelConnection connection = connection(protocol, server.url());
                    PreparedRequest prepared = PreparedRequest.prepare(connection, HISTORY, List.of(), List.of());
                    new ModelClient(events::add).request(connection, prepared, text -> {}).get(10, TimeUnit.SECONDS);
                    assertEquals(1, events.size());
                    DiagnosticEvent event = events.get(0);
                    assertEquals(serverUsage ? Provenance.SERVER : Provenance.ESTIMATE, event.inputTokens().provenance());
                    assertEquals(serverUsage ? Provenance.SERVER : Provenance.ESTIMATE, event.outputTokens().provenance());
                    assertEquals(serverUsage ? 0L : prepared.estimatedInputTokens(), event.inputTokens().value());
                    assertEquals(serverUsage ? 0L : "ответ я😀".length() / RequestBudget.CHARS_PER_TOKEN, event.outputTokens().value());
                }
            }
        }
    }

    @Test
    void headerTimeoutProducesOneEventAndDisabledClientHasNoTraceState() throws Exception {
        ModelClient disabled = new ModelClient();
        assertNull(disabled.newRunId());
        assertNull(disabled.context(RequestType.ANSWER));
        assertNull(disabled.begin(null));
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        try (FakeModelServer server = FakeModelServer.http()) {
            server.handler((request, response) -> {
                release.await(3, TimeUnit.SECONDS);
                response.send(200, "application/json", full(Protocol.OPENAI_COMPATIBLE, ""));
            });
            ModelClient client = new ModelClient(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(2), events::add);
            fail(client.request(connection(Protocol.OPENAI_COMPATIBLE, server.url()), HISTORY, List.of(), List.of(), text -> {}));
            assertEquals(1, events.size());
            assertEquals(Status.TIMEOUT, events.get(0).status());
            assertEquals(0L, events.get(0).responseBytes());
            assertNull(events.get(0).inputTokens().value());
        } finally { release.countDown(); }
    }

    @Test
    void httpFailureAndPreparationAndLocalRejectEachProduceOneEvent() throws Exception {
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        try (FakeModelServer server = FakeModelServer.http()) {
            String error = "{\"error\":{\"message\":\"private-token secret-code\"}}";
            server.handler((request, response) -> response.send(500, "application/json", error));
            ModelClient client = new ModelClient(events::add);
            ModelConnection connection = connection(Protocol.OPENAI_COMPATIBLE, server.url());
            fail(client.request(connection, HISTORY, List.of(), List.of(), text -> {}));
            assertEquals(1, events.size());
            assertEquals(Status.FAILED, events.get(0).status());
            assertEquals((long) error.getBytes(StandardCharsets.UTF_8).length, events.get(0).responseBytes());
            fail(client.request(connection, HISTORY, List.of(), List.of(tool(new Object())), text -> {}));
            assertEquals(2, events.size());
            assertEquals(Status.FAILED, events.get(1).status());
            assertNull(events.get(1).requestBytes());
            assertNull(events.get(1).responseBytes());
            List<ChatMessage> tooBig = List.of(ChatMessage.of(ChatRole.USER, "private-token".repeat(100000)));
            fail(client.request(connection, tooBig, List.of(), List.of(), text -> {}));
            assertEquals(3, events.size());
            assertEquals(Status.REJECTED, events.get(2).status());
            assertTrue(events.get(2).requestBytes() > 0);
            assertEquals(0L, events.get(2).responseBytes());
            assertEquals(1, server.requests().size());
            for (DiagnosticEvent event : events) {
                assertEquals(Provenance.UNKNOWN, event.inputTokens().provenance());
                assertNull(event.outputTokens().value());
                assertFalse(event.toString().contains("private-token"));
            }
        }
    }

    @Test
    void timeoutAndCancelAreTerminalAndDoNotReadUnsafePartialUsage() throws Exception {
        for (boolean cancel : List.of(false, true)) {
            List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try (FakeModelServer server = FakeModelServer.http()) {
                server.handler((request, response) -> {
                    response.start(200, "text/event-stream");
                    response.write("data: {\"choices\":[{\"delta\":{\"content\":\"начало\"}}],\"usage\":{\"prompt_tokens\":0}}\n\n");
                    started.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    response.write("data: [DONE]\n\n");
                    response.end();
                });
                ModelClient client = new ModelClient(Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofMillis(30), events::add);
                CountDownLatch text = new CountDownLatch(1);
                CompletableFuture<ModelReply> future = client.request(connection(Protocol.OPENAI_COMPATIBLE, server.url()), HISTORY, List.of(), List.of(), part -> text.countDown());
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertTrue(text.await(5, TimeUnit.SECONDS));
                if (cancel) assertTrue(future.cancel(true));
                else fail(future);
                assertEquals(1, events.size());
                assertEquals(cancel ? Status.CANCELLED : Status.TIMEOUT, events.get(0).status());
                assertTrue(events.get(0).responseBytes() > 0);
                assertEquals(Provenance.UNKNOWN, events.get(0).inputTokens().provenance());
                release.countDown();
            } finally { release.countDown(); }
            assertEquals(1, events.size());
        }
    }

    @Test
    void agentStepsAndRetriesShareRunAndServicesHaveExplicitSeparateTypes() throws Exception {
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        AtomicInteger index = new AtomicInteger();
        try (FakeModelServer server = FakeModelServer.http()) {
            server.handler((request, response) -> {
                int at = index.getAndIncrement();
                if (at == 0) response.send(400, "application/json", "{\"error\":{\"message\":\"maximum context length exceeded\",\"code\":\"context_length_exceeded\"}}");
                else if (at == 1) response.send(200, "application/json", Json.write(Json.object("choices", List.of(Json.object("message",
                        new OpenAiAdapter().assistantMessage(new ModelReply("", List.of(new ToolCall("call1", "read", "{}")), false)))))));
                else response.send(200, "application/json", full(Protocol.OPENAI_COMPATIBLE, ""));
            });
            ModelClient client = new ModelClient(events::add);
            ModelConnection connection = connection(Protocol.OPENAI_COMPATIBLE, server.url());
            ModelChatProvider provider = new ModelChatProvider(() -> connection, client, problems -> List.of(tool("object")), ToolApprover.ALLOW_ALL);
            provider.reply(HISTORY, part -> {}).get(10, TimeUnit.SECONDS);
            assertEquals(3, events.size());
            assertEquals(List.of(RequestType.ANSWER, RequestType.RETRY, RequestType.ANSWER), events.stream().map(DiagnosticEvent::requestType).toList());
            assertEquals(List.of(0L, 1L, 0L), events.stream().map(DiagnosticEvent::retries).toList());
            assertEquals(1, events.stream().map(DiagnosticEvent::runId).distinct().count());
            assertEquals(3, events.stream().map(DiagnosticEvent::requestId).distinct().count());
            assertEquals(1, events.get(1).toolCalls());
            provider.title(HISTORY).get(10, TimeUnit.SECONDS);
            provider.summarize(HISTORY).get(10, TimeUnit.SECONDS);
            client.check(connection).get(10, TimeUnit.SECONDS);
            assertEquals(List.of(RequestType.TITLE, RequestType.SUMMARY, RequestType.CHECK), events.subList(3, 6).stream().map(DiagnosticEvent::requestType).toList());
            assertEquals(4, events.stream().map(DiagnosticEvent::runId).distinct().count());
            assertEquals(6, events.stream().map(DiagnosticEvent::requestId).distinct().count());
        }
    }

    @Test
    void cancellationRacesDoNotDuplicateTerminalEventsAndSinkFailureIsIsolated() throws Exception {
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        try (FakeModelServer server = FakeModelServer.http()) {
            server.handler((request, response) -> response.send(200, "application/json", full(Protocol.OPENAI_COMPATIBLE, "")));
            ModelClient client = new ModelClient(event -> { events.add(event); throw new IllegalStateException("sink failed"); });
            for (int i = 0; i < 12; i++) {
                CompletableFuture<ModelReply> future = client.request(connection(Protocol.OPENAI_COMPATIBLE, server.url()), HISTORY, List.of(), List.of(), text -> {});
                if (i % 2 == 0) future.cancel(true);
                if (!future.isCancelled()) future.get(10, TimeUnit.SECONDS);
                assertEquals(i + 1, events.size());
                assertEquals(future.isCancelled() ? Status.CANCELLED : Status.SUCCESS, events.get(i).status());
                assertFalse(future.cancel(true) && !future.isCancelled());
            }
            assertEquals(12, events.stream().map(DiagnosticEvent::requestId).distinct().count());
        }
    }

    @Test
    void enablingDuringUntracedRunAppliesOnlyToNextRunAndPredicateFailureIsOff() throws Exception {
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean enabled = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger index = new AtomicInteger();
        try (FakeModelServer server = FakeModelServer.http()) {
            server.handler((request, response) -> {
                if (index.getAndIncrement() == 0) {
                    enabled.set(true);
                    response.send(200, "application/json", Json.write(Json.object("choices", List.of(Json.object("message",
                            new OpenAiAdapter().assistantMessage(new ModelReply("", List.of(new ToolCall("call1", "read", "{}")), false)))))));
                } else response.send(200, "application/json", full(Protocol.OPENAI_COMPATIBLE, ""));
            });
            ModelConnection connection = connection(Protocol.OPENAI_COMPATIBLE, server.url());
            ModelClient client = new ModelClient(events::add, enabled::get);
            ModelChatProvider provider = new ModelChatProvider(() -> connection, client, problems -> List.of(tool("object")), ToolApprover.ALLOW_ALL);
            provider.reply(HISTORY, text -> {}).get(10, TimeUnit.SECONDS);
            assertEquals(2, server.requests().size());
            assertTrue(events.isEmpty(), "run, начатый при off, не включает tracing посередине");
            provider.reply(HISTORY, text -> {}).get(10, TimeUnit.SECONDS);
            assertEquals(1, events.size());
            ModelClient failingPredicate = new ModelClient(events::add, () -> { throw new IllegalStateException("private-error"); });
            failingPredicate.request(connection, HISTORY, List.of(), List.of(), text -> {}).get(10, TimeUnit.SECONDS);
            assertEquals(1, events.size());
            enabled.set(false);
            assertNull(client.newRunId());
            assertNull(client.context(RequestType.ANSWER));
            assertNull(client.begin(null));
        }
    }

    private static Tool tool(Object schemaType) {
        return new Tool() {
            public String name() { return "read"; }
            public String description() { return "private-description"; }
            public Map<String, Object> inputSchema() { return Json.object("type", schemaType); }
            public String summary(Map<String, Object> input) { return "read"; }
            public Result call(Map<String, Object> input) { return new Result("private-result", "read"); }
        };
    }
}
