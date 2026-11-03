package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolApprover;
import io.github.hansglorias.edtai.core.tools.UserQuestions;
import io.github.hansglorias.edtai.core.tools.UserQuestions.Answer;

class AgentQuestionsTest {
    private static final String INPUT = "{\"questions\":[{\"id\":\"scope\",\"text\":\"Где менять?\"}]}";

    @Test
    void waitsWithoutAnotherRequestOrExecutingNextCallThenReturnsLinkedAnswerOnce() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            CompletableFuture<List<Answer>> pending = new CompletableFuture<>();
            CountDownLatch shown = new CountDownLatch(1);
            AtomicInteger subsequent = new AtomicInteger();
            Tool ask = UserQuestions.tool(request -> { shown.countDown(); return pending; });
            Tool next = next(subsequent);
            try (FakeModelServer server = script(protocol, List.of(new ToolCall("question-id", "ask_user", INPUT),
                    new ToolCall("next-id", "next", "{}")))) {
                CompletableFuture<String> reply = run(protocol, server, List.of(ask, next));
                try {
                    assertTrue(shown.await(5, TimeUnit.SECONDS));
                    assertFalse(reply.isDone());
                    assertEquals(1, server.requests().size());
                    assertEquals(0, subsequent.get(), "следующий вызов пакета ждёт ответы");
                    assertTrue(pending.complete(List.of(new Answer("scope", "", "Только в этой функции"))));
                    assertFalse(pending.complete(List.of(new Answer("scope", "", "Чужой повтор"))));
                    assertEquals("готово", reply.get(10, TimeUnit.SECONDS));
                    assertEquals(1, subsequent.get());
                    assertEquals(2, server.requests().size());
                    Map<String, Object> body = Json.parseObject(server.requests().get(1).body());
                    List<?> messages = (List<?>) body.get("messages");
                    Object result = protocol == Protocol.OPENAI_COMPATIBLE
                            ? messages.stream().filter(message -> "tool".equals(Json.stringAt(message, "role"))).findFirst().orElseThrow()
                            : messages.stream().flatMap(message -> Json.asList(Json.at(message, "content")).stream())
                                    .filter(part -> "tool_result".equals(Json.stringAt(part, "type"))).findFirst().orElseThrow();
                    assertEquals("question-id", Json.stringAt(result,
                            protocol == Protocol.OPENAI_COMPATIBLE ? "tool_call_id" : "tool_use_id"));
                    assertTrue(Json.stringAt(result, "content").contains("Только в этой функции"));
                    assertFalse(server.requests().get(1).body().contains("Чужой повтор"));
                } finally { reply.cancel(true); }
            }
        }
    }

    @Test
    void stopCancelsQuestionAndPreventsTheRestOfTheBatch() throws Exception {
        CompletableFuture<List<Answer>> pending = new CompletableFuture<>();
        CountDownLatch shown = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicInteger writes = new AtomicInteger();
        Tool ask = UserQuestions.tool(request -> { worker.set(Thread.currentThread()); shown.countDown(); return pending; });
        try (FakeModelServer server = script(Protocol.OPENAI_COMPATIBLE, List.of(new ToolCall("q", "ask_user", INPUT),
                new ToolCall("write", "next", "{}")))) {
            var reply = run(Protocol.OPENAI_COMPATIBLE, server, List.of(ask, next(writes)));
            assertTrue(shown.await(5, TimeUnit.SECONDS));
            reply.cancel(true);
            worker.get().join(5000);
            assertFalse(worker.get().isAlive());
            assertTrue(pending.isCancelled());
            assertFalse(pending.complete(List.of(new Answer("scope", "", "поздний ответ"))));
            assertEquals(0, writes.get());
            assertEquals(1, server.requests().size());
        }
    }

    @Test
    void invalidArgumentsReturnToolErrorWithoutShowingForm() throws Exception {
        AtomicInteger shown = new AtomicInteger();
        Tool ask = UserQuestions.tool(request -> { shown.incrementAndGet(); return new CompletableFuture<>(); });
        try (FakeModelServer server = script(Protocol.OPENAI_COMPATIBLE,
                List.of(new ToolCall("bad-q", "ask_user", "{\"questions\":[]}")))) {
            assertEquals("готово", run(Protocol.OPENAI_COMPATIBLE, server, List.of(ask)).get(10, TimeUnit.SECONDS));
            assertEquals(0, shown.get());
            assertTrue(server.requests().get(1).body().contains("ask_user: questions"));
        }
    }

    private static CompletableFuture<String> run(Protocol protocol, FakeModelServer server, List<Tool> tools) {
        var connection = new ModelConnection(protocol, server.url(), "test-model", "", 512, "",
                new TrustSettings(false, Optional.empty()), 32768);
        return new ModelChatProvider(() -> connection, new ModelClient(), problems -> tools, ToolApprover.ALLOW_ALL)
                .reply(List.of(ChatMessage.of(ChatRole.USER, "План")), text -> { });
    }
    private static FakeModelServer script(Protocol protocol, List<ToolCall> calls) throws Exception {
        AtomicInteger index = new AtomicInteger();
        return FakeModelServer.http().handler((request, response) -> {
            ModelReply reply = index.getAndIncrement() == 0 ? new ModelReply("", calls) : ModelReply.text("готово");
            Object message = ProtocolAdapter.of(protocol).assistantMessage(reply);
            Object body = protocol == Protocol.OPENAI_COMPATIBLE
                    ? Json.object("choices", List.of(Json.object("message", message))) : message;
            response.send(200, "application/json", Json.write(body));
        });
    }
    private static Tool next(AtomicInteger calls) {
        return new Tool() {
            @Override public String name() { return "next"; }
            @Override public String description() { return "next"; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
            @Override public String summary(Map<String, Object> input) { return "next"; }
            @Override public Result call(Map<String, Object> input) { calls.incrementAndGet(); return new Result("ok", "ok"); }
        };
    }
}
