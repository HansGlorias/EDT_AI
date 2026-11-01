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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import io.github.hansglorias.edtai.core.tools.ToolApprover;
import io.github.hansglorias.edtai.core.tools.ToolException;

/** Проверяет страницы на границе с моделью, а не только внутри хранилища снимков. */
class AgentResultPagingTest {

    private static final String READER = "read_tool_result";
    private final List<FakeModelServer> servers = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (FakeModelServer server : servers) {
            server.close();
        }
    }

    @Test
    void pagesReachTheTailWithoutRepeatingTheEffectOrAttachmentInBothProtocols() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger writes = new AtomicInteger();
            List<ToolUse> uses = new CopyOnWriteArrayList<>();
            List<Page> pages = new CopyOnWriteArrayList<>();
            String original = "receipt-start:" + "я😀".repeat(3000) + ":receipt-tail";
            Tool write = tool("write", () -> {
                writes.incrementAndGet();
                return new Tool.Result(original, "изменено", Attachment.review("[]"));
            });
            FakeModelServer server = model(protocol, request -> {
                List<WireResult> results = results(protocol, request);
                if (results.isEmpty()) {
                    return calls(new ToolCall("effect", "write", "{}"));
                }
                Page page = Page.parse(results.get(results.size() - 1).text());
                pages.add(page);
                return page.next() == null ? ModelReply.text("done")
                        : calls(read("page-" + pages.size(), page.id(), page.next()));
            });

            assertEquals("done", run(protocol, server, List.of(write), uses::add).get(10, TimeUnit.SECONDS));
            assertEquals(1, writes.get(), protocol.name());
            assertEquals(1, uses.stream().filter(use -> use.file() != null).count());
            assertFalse(hasReader(protocol, server.requests().get(0)));
            assertTrue(hasReader(protocol, server.requests().get(1)));
            assertTrue(pages.size() >= 3);
            assertEquals(original, pages.stream().map(Page::payload).reduce("", String::concat));
            int offset = 0;
            for (Page page : pages) {
                assertEquals(pages.get(0).id(), page.id());
                assertEquals(original.length(), page.total());
                assertEquals(offset, page.offset());
                assertFalse(page.originalError());
                assertTrue(page.payload().length() <= 2880, "страница ограничена бюджетом окна 8192");
                offset += page.payload().length();
                assertEquals(offset == original.length() ? null : Integer.valueOf(offset), page.next());
            }
        }
    }

    @Test
    void originalFailureRemainsAnErrorButReadingItsSnapshotSucceedsInBothProtocols() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger executions = new AtomicInteger();
            List<ToolUse> uses = new CopyOnWriteArrayList<>();
            List<Page> pages = new CopyOnWriteArrayList<>();
            String failure = "failure-start:" + "подробности ".repeat(600) + ":failure-tail";
            Tool source = tool("write", () -> {
                executions.incrementAndGet();
                throw new ToolException(ToolUse.Problem.TIMEOUT, failure);
            });
            FakeModelServer server = model(protocol, request -> {
                List<WireResult> results = results(protocol, request);
                if (results.isEmpty()) {
                    return calls(new ToolCall("failed-effect", "write", "{}"));
                }
                Page page = Page.parse(results.get(results.size() - 1).text());
                pages.add(page);
                return page.next() == null ? ModelReply.text("done")
                        : calls(read("error-page-" + pages.size(), page.id(), page.next()));
            });

            assertEquals("done", run(protocol, server, List.of(source), uses::add).get(10, TimeUnit.SECONDS));
            assertEquals(1, executions.get());
            assertEquals(failure, pages.stream().map(Page::payload).reduce("", String::concat));
            assertTrue(pages.stream().allMatch(Page::originalError));
            assertTrue(uses.stream().anyMatch(use -> use.name().equals("write")
                    && use.status() == ToolUse.Status.FAILED && use.problem() == ToolUse.Problem.TIMEOUT));
            assertTrue(uses.stream().anyMatch(use -> use.name().equals(READER) && use.status() == ToolUse.Status.DONE));
            assertFalse(uses.stream().anyMatch(use -> use.name().equals(READER) && use.status() == ToolUse.Status.FAILED));
            if (protocol == Protocol.ANTHROPIC) {
                assertTrue(results(protocol, server.requests().get(1)).get(0).error());
                List<WireResult> finalResults = results(protocol, server.requests().get(server.requests().size() - 1));
                assertFalse(finalResults.get(finalResults.size() - 1).error());
            }
        }
    }

    @Test
    void unknownSnapshotDoesNotExecuteItsSourceInBothProtocols() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger writes = new AtomicInteger();
            AtomicInteger step = new AtomicInteger();
            List<ToolUse> uses = new CopyOnWriteArrayList<>();
            Tool source = tool("write", () -> new Tool.Result("receipt:" + writes.incrementAndGet() + "я".repeat(7000), "done"));
            FakeModelServer server = model(protocol, request -> switch (step.getAndIncrement()) {
                case 0 -> calls(new ToolCall("effect", "write", "{}"));
                case 1 -> calls(read("unknown", "missing-result-id", 0));
                default -> ModelReply.text("done");
            });

            assertEquals("done", run(protocol, server, List.of(source), uses::add).get(10, TimeUnit.SECONDS));
            assertEquals(1, writes.get());
            assertTrue(uses.stream().anyMatch(use -> use.id().equals("unknown")
                    && use.status() == ToolUse.Status.FAILED && use.problem() == ToolUse.Problem.BAD_ARGUMENTS));
            List<WireResult> finalResults = results(protocol, server.requests().get(2));
            assertTrue(finalResults.get(finalResults.size() - 1).text().contains("не найден"));
            if (protocol == Protocol.ANTHROPIC) {
                assertTrue(finalResults.get(finalResults.size() - 1).error());
            }
        }
    }

    @Test
    void overflowKeepsAReadableReferenceToTheOmittedResultInBothProtocols() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger writes = new AtomicInteger();
            AtomicInteger step = new AtomicInteger();
            AtomicReference<String> id = new AtomicReference<>();
            String original = "receipt-start:" + "я".repeat(7000) + ":receipt-tail";
            Tool source = tool("write", () -> {
                writes.incrementAndGet();
                return new Tool.Result(original, "done");
            });
            Tool other = tool("other", () -> new Tool.Result("other:" + "ю".repeat(7000), "done"));
            FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                int at = step.getAndIncrement();
                if (at == 2 || at == 3) {
                    response.send(400, "application/json", Json.write(Json.object("error", Json.object(
                            "message", "This model's maximum context length is 8192 tokens", "code", "context_length_exceeded"))));
                    return;
                }
                ModelReply reply;
                if (at == 0) {
                    reply = calls(new ToolCall("effect", "write", "{}"));
                } else if (at == 1) {
                    id.set(Page.parse(results(protocol, request).get(0).text()).id());
                    reply = calls(new ToolCall("other", "other", "{}"));
                } else if (at == 4) {
                    reply = calls(read("tail", id.get(), original.length() - 30));
                } else {
                    reply = ModelReply.text("done");
                }
                send(protocol, response, reply);
            });
            servers.add(server);

            assertEquals("done", run(protocol, server, List.of(source, other), use -> { }).get(10, TimeUnit.SECONDS));
            String omitted = results(protocol, server.requests().get(4)).get(0).text();
            assertTrue(omitted.contains("result_id: " + id.get()), "ссылка пережила сокращение запроса");
            assertFalse(omitted.contains("receipt-start:"));
            List<WireResult> finalResults = results(protocol, server.requests().get(5));
            Page tail = Page.parse(finalResults.get(finalResults.size() - 1).text());
            assertEquals(original.substring(original.length() - 30), tail.payload());
            assertEquals(1, writes.get());
        }
    }

    private FakeModelServer model(Protocol protocol, Function<FakeModelServer.Request, ModelReply> reply) throws Exception {
        FakeModelServer server = FakeModelServer.http().handler((request, response) -> send(protocol, response, reply.apply(request)));
        servers.add(server);
        return server;
    }

    private static void send(Protocol protocol, FakeModelServer.Response response, ModelReply reply) throws Exception {
        Object message = ProtocolAdapter.of(protocol).assistantMessage(reply);
        Object body = protocol == Protocol.OPENAI_COMPATIBLE
                ? Json.object("choices", List.of(Json.object("message", message))) : message;
        response.send(200, "application/json", Json.write(body));
    }

    private static CompletableFuture<String> run(Protocol protocol, FakeModelServer server, List<Tool> tools,
            Consumer<ToolUse> onTool) {
        ModelConnection connection = new ModelConnection(protocol, server.url(), "test-model", "", 512, "",
                new TrustSettings(false, Optional.empty()), 8192);
        return new ModelChatProvider(() -> connection, new ModelClient(), problems -> tools, ToolApprover.ALLOW_ALL)
                .reply(List.of(ChatMessage.of(ChatRole.USER, "test task")), text -> { }, onTool);
    }

    private static boolean hasReader(Protocol protocol, FakeModelServer.Request request) {
        for (Object tool : Json.asList(Json.at(Json.parse(request.body()), "tools"))) {
            String name = protocol == Protocol.OPENAI_COMPATIBLE ? Json.stringAt(tool, "function", "name") : Json.stringAt(tool, "name");
            if (READER.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private record WireResult(String text, boolean error) { }

    private static List<WireResult> results(Protocol protocol, FakeModelServer.Request request) {
        List<WireResult> results = new ArrayList<>();
        for (Object message : Json.asList(Json.at(Json.parse(request.body()), "messages"))) {
            if (protocol == Protocol.OPENAI_COMPATIBLE && "tool".equals(Json.stringAt(message, "role"))) {
                results.add(new WireResult(Json.stringAt(message, "content"), false));
            } else if (protocol == Protocol.ANTHROPIC) {
                Object content = Json.at(message, "content");
                if (content instanceof List<?>) {
                    for (Object block : Json.asList(content)) {
                        if ("tool_result".equals(Json.stringAt(block, "type"))) {
                            results.add(new WireResult(Json.stringAt(block, "content"), Boolean.TRUE.equals(Json.at(block, "is_error"))));
                        }
                    }
                }
            }
        }
        return results;
    }

    private record Page(String id, boolean originalError, int total, int offset, Integer next, String payload) {
        static Page parse(String text) {
            int separator = text.indexOf("\n\n");
            String header = text.substring(0, separator);
            String next = field(header, "next_offset");
            return new Page(field(header, "result_id"), field(header, "original_error").startsWith("true"),
                    Integer.parseInt(field(header, "total_chars")), Integer.parseInt(field(header, "offset")),
                    next == null ? null : Integer.valueOf(next), text.substring(separator + 2));
        }

        private static String field(String header, String name) {
            Matcher matcher = Pattern.compile("(?m)^" + name + ": ([^\\r\\n]*)$").matcher(header);
            return matcher.find() ? matcher.group(1).strip() : null;
        }
    }

    private static ModelReply calls(ToolCall call) {
        return new ModelReply("", List.of(call));
    }

    private static ToolCall read(String callId, String resultId, int offset) {
        return new ToolCall(callId, READER, Json.write(Json.object("result_id", resultId, "offset", offset)));
    }

    @FunctionalInterface
    private interface Body {
        Tool.Result call() throws ToolException;
    }

    private static Tool tool(String name, Body body) {
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
            public Result call(Map<String, Object> input) throws ToolException {
                return body.call();
            }
        };
    }
}
