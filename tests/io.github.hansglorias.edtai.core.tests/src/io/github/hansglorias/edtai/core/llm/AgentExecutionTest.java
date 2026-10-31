package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolActivity;
import io.github.hansglorias.edtai.core.tools.ToolApprover;
import io.github.hansglorias.edtai.core.tools.ToolException;

/** Сценарии исполнения: проверяют реальные вызовы и то, что получил fake model server. */
class AgentExecutionTest {

    private final List<FakeModelServer> servers = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (FakeModelServer server : servers) {
            server.close();
        }
    }

    @Test
    void readsAgainAfterAnEffectInBothProtocols() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger state = new AtomicInteger();
            AtomicInteger reads = new AtomicInteger();
            Tool read = tool("read", true, () -> {
                reads.incrementAndGet();
                return new Tool.Result("version=" + state.get(), "прочитано");
            });
            Tool write = tool("write", false, () -> new Tool.Result("changed=" + state.incrementAndGet(), "изменено"));
            // В продакшене инструменты MCP проходят через ToolActivity: контракт чтения не должен потеряться.
            List<Tool> tools = new ToolActivity().track(List.of(read, write), item -> true);
            FakeModelServer server = script(protocol, List.of(calls(call("r1", "read")), calls(call("w", "write")),
                    calls(call("r2", "read")), ModelReply.text("done")));
            assertEquals("done", run(protocol, server, tools, 32768, ToolApprover.ALLOW_ALL, use -> { }).get(10, TimeUnit.SECONDS));
            assertEquals(2, reads.get(), protocol.name());
            assertEquals(1, state.get());
            assertTrue(server.requests().get(3).body().contains("version=1"));
        }
    }

    @Test
    void transientReadFailureCanRecoverButCannotLoopForever() throws Exception {
        for (boolean recover : List.of(true, false)) {
            AtomicInteger executions = new AtomicInteger();
            Tool read = tool("read", true, () -> {
                int attempt = executions.incrementAndGet();
                if (!recover || attempt == 1) {
                    throw new ToolException(ToolUse.Problem.TIMEOUT, "temporary");
                }
                return new Tool.Result("recovered", "готово");
            });
            FakeModelServer server = script(Protocol.OPENAI_COMPATIBLE, List.of(calls(call("a", "read")),
                    calls(call("b", "read")), calls(call("c", "read")), ModelReply.text("done")));
            run(Protocol.OPENAI_COMPATIBLE, server, List.of(read), 32768, ToolApprover.ALLOW_ALL, use -> { })
                    .get(10, TimeUnit.SECONDS);
            assertEquals(2, executions.get());
            assertTrue(server.requests().get(3).body().contains(recover ? "recovered" : "исчерпан лимит"));
        }
    }

    @Test
    void uncertainEffectIsNotRepeatedEvenAfterOtherCalls() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        Tool write = tool("write", false, () -> {
            writes.incrementAndGet(); // Сервер успел изменить данные, но ответ не дошёл.
            throw new ToolException(ToolUse.Problem.TIMEOUT, "lost response");
        });
        Tool read = tool("read", true, () -> new Tool.Result("state inspected", "прочитано"));
        FakeModelServer server = script(Protocol.OPENAI_COMPATIBLE, List.of(calls(call("a", "write")),
                calls(call("b", "read")), calls(call("c", "write")), ModelReply.text("done")));
        run(Protocol.OPENAI_COMPATIBLE, server, List.of(write, read), 32768, ToolApprover.ALLOW_ALL, use -> { })
                .get(10, TimeUnit.SECONDS);
        assertEquals(1, writes.get());
        assertTrue(server.requests().get(3).body().contains("успешное завершение не подтверждено"));
    }

    @Test
    void omittedEffectIsReplayedWithoutExecutingOrAttachingAgain() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger writes = new AtomicInteger();
            List<ToolUse> uses = new CopyOnWriteArrayList<>();
            String original = "receipt-" + "я".repeat(7000);
            Tool write = tool("write", false, () -> {
                writes.incrementAndGet();
                return new Tool.Result(original, "изменено", Attachment.review("[]"));
            });
            Tool read = tool("read", true, () -> new Tool.Result("other-" + "ю".repeat(7000), "прочитано"));
            FakeModelServer server = script(protocol, List.of(calls(call("a", "write")), calls(call("b", "read")),
                    calls(call("c", "write")), ModelReply.text("done")));
            run(protocol, server, List.of(write, read), 4096, ToolApprover.ALLOW_ALL, uses::add).get(10, TimeUnit.SECONDS);
            assertEquals(1, writes.get(), protocol.name());
            assertTrue(server.requests().get(2).body().contains(AgentTranscript.OMITTED));
            assertFalse(server.requests().get(2).body().contains("receipt-"));
            assertTrue(server.requests().get(3).body().contains("receipt-"), "исходный результат не уничтожен");
            assertTrue(server.requests().get(3).body().contains("не проверка текущего состояния"));
            assertEquals(1, uses.stream().filter(use -> use.file() != null).count());
        }
    }

    @Test
    void deniedCallIsNotAskedAgainWithReorderedArguments() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger executed = new AtomicInteger();
        Tool write = tool("write", false, () -> new Tool.Result("" + executed.incrementAndGet(), "done"), true);
        FakeModelServer server = script(Protocol.OPENAI_COMPATIBLE, List.of(
                calls(new ToolCall("a", "write", "{\"outer\":{\"x\":1,\"y\":2},\"z\":null}")),
                calls(new ToolCall("b", "write", "{\"z\":null,\"outer\":{\"y\":2.0,\"x\":1.0}}")), ModelReply.text("done")));
        run(Protocol.OPENAI_COMPATIBLE, server, List.of(write), 32768, (use, input) -> {
            asked.incrementAndGet();
            return CompletableFuture.completedFuture(ToolApprover.Decision.DENY);
        }, use -> { }).get(10, TimeUnit.SECONDS);
        assertEquals(1, asked.get());
        assertEquals(0, executed.get());
        assertTrue(server.requests().get(2).body().contains("Повторно разрешение не запрашивается"));
    }

    @Test
    void omittedReadCanRunAgainAndRecoverFromNewTransientFailure() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        Tool read = tool("read", true, () -> {
            int attempt = reads.incrementAndGet();
            if (attempt == 2) {
                throw new ToolException(ToolUse.Problem.SERVER, "temporary");
            }
            return new Tool.Result("read-attempt=" + attempt + "я".repeat(7000), "прочитано");
        });
        Tool other = tool("other", true, () -> new Tool.Result("ю".repeat(7000), "прочитано"));
        FakeModelServer server = script(Protocol.OPENAI_COMPATIBLE, List.of(calls(call("a", "read")),
                calls(call("b", "other")), calls(call("c", "read")), calls(call("d", "read")), ModelReply.text("done")));
        run(Protocol.OPENAI_COMPATIBLE, server, List.of(read, other), 4096, ToolApprover.ALLOW_ALL, use -> { })
                .get(10, TimeUnit.SECONDS);
        assertTrue(server.requests().get(2).body().contains(AgentTranscript.OMITTED));
        assertEquals(3, reads.get(), "успешное прежнее чтение не расходует лимит восстановления после нового сбоя");
        assertTrue(server.requests().get(4).body().contains("read-attempt=3"));
    }

    @Test
    void cancellationWhileWaitingForApprovalDoesNotWrite() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AtomicReference<Thread> worker = new AtomicReference<>();
        CountDownLatch asked = new CountDownLatch(1);
        CompletableFuture<ToolApprover.Decision> approval = new CompletableFuture<>();
        Tool write = tool("write", false, () -> new Tool.Result("" + writes.incrementAndGet(), "done"), true);
        FakeModelServer server = script(Protocol.OPENAI_COMPATIBLE, List.of(calls(call("a", "write")), ModelReply.text("done")));
        CompletableFuture<String> reply = run(Protocol.OPENAI_COMPATIBLE, server, List.of(write), 32768, (use, input) -> {
            worker.set(Thread.currentThread());
            asked.countDown();
            return approval;
        }, use -> { });
        try {
            assertTrue(asked.await(5, TimeUnit.SECONDS));
            reply.cancel(true);
            approval.complete(ToolApprover.Decision.ALLOW);
            worker.get().join(5000);
            assertFalse(worker.get().isAlive());
            assertEquals(0, writes.get());
        } finally {
            reply.cancel(true);
            approval.cancel(true);
        }
    }

    @Test
    void cancellationBetweenCallsStopsTheRestOfTheBatch() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicInteger writes = new AtomicInteger();
        Tool slow = tool("slow", true, () -> {
            worker.set(Thread.currentThread());
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // Имитация инструмента, который проглотил interrupt: отмена должна проверяться самим циклом.
            }
            return new Tool.Result("finished", "done");
        });
        Tool write = tool("write", false, () -> new Tool.Result("" + writes.incrementAndGet(), "done"));
        FakeModelServer server = script(Protocol.OPENAI_COMPATIBLE, List.of(calls(call("a", "slow"), call("b", "write")),
                ModelReply.text("done")));
        CompletableFuture<String> reply = run(Protocol.OPENAI_COMPATIBLE, server, List.of(slow, write), 32768,
                ToolApprover.ALLOW_ALL, use -> {
                    if (use.id().equals("a") && use.status() == ToolUse.Status.DONE) {
                        finished.countDown();
                    }
                });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            reply.cancel(true);
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            // Дождаться окончания рабочего потока, а не угадывать длительность паузой.
            worker.get().join(5000);
            assertFalse(worker.get().isAlive(), "цикл завершился после отмены");
            assertEquals(0, writes.get());
            assertEquals(1, server.requests().size());
        } finally {
            release.countDown();
        }
    }

    private FakeModelServer script(Protocol protocol, List<ModelReply> replies) throws Exception {
        AtomicInteger index = new AtomicInteger();
        FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
            int at = index.getAndIncrement();
            ModelReply reply = at < replies.size() ? replies.get(at) : ModelReply.text("unexpected extra request");
            Object message = ProtocolAdapter.of(protocol).assistantMessage(reply);
            Object body = protocol == Protocol.OPENAI_COMPATIBLE
                    ? Json.object("choices", List.of(Json.object("message", message))) : message;
            response.send(200, "application/json", Json.write(body));
        });
        servers.add(server);
        return server;
    }

    private static CompletableFuture<String> run(Protocol protocol, FakeModelServer server, List<Tool> tools, int window,
            ToolApprover approver, Consumer<ToolUse> onTool) {
        ModelConnection connection = new ModelConnection(protocol, server.url(), "test-model", "", 512, "",
                new TrustSettings(false, Optional.empty()), window);
        return new ModelChatProvider(() -> connection, new ModelClient(), problems -> tools, approver)
                .reply(List.of(ChatMessage.of(ChatRole.USER, "test task")), text -> { }, onTool);
    }

    private static ModelReply calls(ToolCall... calls) {
        return new ModelReply("", List.of(calls));
    }

    private static ToolCall call(String id, String name) {
        return new ToolCall(id, name, "{}");
    }

    @FunctionalInterface
    private interface Body {
        Tool.Result call() throws ToolException;
    }

    private static Tool tool(String name, boolean read, Body body) {
        return tool(name, read, body, false);
    }

    private static Tool tool(String name, boolean read, Body body, boolean approval) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public String summary(Map<String, Object> input) {
                return name;
            }

            @Override
            public boolean repeatableRead() {
                return read;
            }

            @Override
            public boolean needsApproval() {
                return approval;
            }

            @Override
            public Result call(Map<String, Object> input) throws ToolException {
                return body.call();
            }
        };
    }
}
