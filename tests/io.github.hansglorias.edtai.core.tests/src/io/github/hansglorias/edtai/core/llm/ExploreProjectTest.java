package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.TokenUsage;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.ProjectTools;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolApprover;
import io.github.hansglorias.edtai.core.tools.ToolException;
import io.github.hansglorias.edtai.core.tools.ToolSource;
import io.github.hansglorias.edtai.core.tools.WorkspaceFiles;

class ExploreProjectTest {
    @TempDir Path temp;

    @Test
    void bothProtocolsIsolateContextAndReturnOnlyBoundedReportWithFullUsage() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            List<Tool> reads = reads();
            AtomicInteger calls = new AtomicInteger();
            List<ToolUse> progress = new CopyOnWriteArrayList<>();
            List<TokenUsage> usage = new CopyOnWriteArrayList<>();
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                try {
                    int step = calls.getAndIncrement();
                    Map<String, Object> body = Json.parseObject(request.body());
                    assertEquals("fixed-parent-model", body.get("model"));
                    if (step == 0) {
                        assertTrue(toolNames(body, protocol).contains("explore_project"));
                        send(response, protocol, "", List.of(call("explore_project", Json.object("question", "Find links", "scope", "CommonModules"))), 10, 2);
                    } else if (step == 1) {
                        assertFalse(request.body().contains("parent-private-prompt"));
                        assertFalse(request.body().contains("parent-private-history"));
                        assertEquals(2048L, body.get("max_tokens"));
                        assertEquals(List.of("project_info", "list_objects", "read_module", "search_code"), toolNames(body, protocol));
                        send(response, protocol, "CHILD_INTERMEDIATE", List.of(call("read_module", Json.object("module", "src/CommonModules/Test/Module.bsl"))), 200, 20);
                    } else if (step == 2) {
                        assertTrue(request.body().contains("fixture-only"));
                        send(response, protocol, "CHILD_FINAL: CommonModule.Test:1 result_id: 11111111-1111-1111-1111-111111111111-1 read_tool_result", List.of(), 200, 20);
                    } else {
                        assertEquals(3, step);
                        assertTrue(request.body().contains("status: complete"));
                        assertTrue(request.body().contains("CHILD_FINAL"));
                        assertFalse(request.body().contains("fixture-only"));
                        assertFalse(request.body().contains("CHILD_INTERMEDIATE"));
                        assertFalse(request.body().contains("11111111-1111-1111-1111-111111111111-1"));
                        send(response, protocol, "PARENT_FINAL", List.of(), 10, 2);
                    }
                } catch (Throwable error) { errors.add(error); throw error; }
            })) {
                ModelConnection connection = connection(server, protocol);
                ToolSource source = source(reads);
                String result = new ModelChatProvider(() -> connection, new ModelClient(), source, ToolApprover.ALLOW_ALL)
                        .reply(List.of(ChatMessage.of(ChatRole.USER, "parent-private-history")), part -> { }, progress::add, usage::add)
                        .get(15, TimeUnit.SECONDS);
                assertEquals(List.of(), errors);
                assertEquals("PARENT_FINAL", result);
                assertEquals(4, calls.get());
                TokenUsage total = usage.get(usage.size() - 1);
                assertEquals(420, total.input());
                assertEquals(44, total.output());
                assertEquals(12, total.context(), "контекст остаётся от последнего родительского запроса");
                assertEquals(TokenUsage.Source.SERVER, total.inputSource());
                assertTrue(progress.stream().anyMatch(use -> use.id().contains("/child/") && use.summary().startsWith("Разведка:")));
            }
        }
    }

    @Test
    void childCannotAskWriteReviewSaveSkillUseMcpOrRecurse() throws Exception {
        List<Tool> reads = reads();
        AtomicInteger calls = new AtomicInteger();
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
            int step = calls.getAndIncrement();
            if (step == 0) {
                List<Map<String, Object>> forbidden = new ArrayList<>();
                for (String name : List.of("ask_user", "edit_module", "save_skill", "report_review", "explore_project", "mcp_get_data")) {
                    forbidden.add(call(name, Map.of()));
                }
                send(response, Protocol.OPENAI_COMPATIBLE, "", forbidden, 1, 1);
            } else send(response, Protocol.OPENAI_COMPATIBLE, "Не удалось прочитать проект", List.of(), 1, 1);
        })) {
            ExploreProject explore = explorer(server, reads);
            Tool.Result result = explore.call(Map.of("question", "Read"));
            assertTrue(result.text().contains("status: partial"));
            assertEquals(2, calls.get());
            assertTrue(result.text().length() <= ExploreProject.MAX_REPORT);
        }
    }

    @Test
    void maxRunsBelongsToOneParentAndCallsWaitWithoutPolling() throws Exception {
        AtomicInteger children = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
            calls.incrementAndGet();
            if (request.body().contains(ExploreProject.PROMPT)) {
                children.incrementAndGet();
                send(response, Protocol.OPENAI_COMPATIBLE, "Нет данных", List.of(), 1, 1);
            } else if (!request.body().contains("tool_call_id")) {
                send(response, Protocol.OPENAI_COMPATIBLE, "", List.of(call("explore_project", Map.of("question", "one")),
                        call("explore_project", Map.of("question", "two")), call("explore_project", Map.of("question", "three"))), 1, 1);
            } else send(response, Protocol.OPENAI_COMPATIBLE, "Готово", List.of(), 1, 1);
        })) {
            ModelConnection connection = connection(server, Protocol.OPENAI_COMPATIBLE);
            ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient(), source(reads()), ToolApprover.ALLOW_ALL);
            for (int run = 0; run < 2; run++) {
                assertEquals("Готово", provider.reply(List.of(ChatMessage.of(ChatRole.USER, "Explore")), part -> { }).get(15, TimeUnit.SECONDS));
            }
            assertEquals(4, children.get());
            assertEquals(8, calls.get());
        }
    }

    @Test
    void childStepsAreBoundedAndReportIsPartialAtLimit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
            int step = calls.incrementAndGet();
            send(response, Protocol.OPENAI_COMPATIBLE, "", List.of(call("read_module",
                    Json.object("module", "src/CommonModules/Test/Module.bsl", "first_line", step))), 1, 1);
        })) {
            Tool.Result result = explorer(server, reads()).call(Map.of("question", "Read forever"));
            assertEquals(ExploreProject.MAX_STEPS, calls.get());
            assertEquals("partial", result.outcome());
            assertTrue(result.text().length() <= ExploreProject.MAX_REPORT);
        }
    }

    @Test
    void cancellationStopsChildHttpAndNeverResumesParent() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
            int step = calls.getAndIncrement();
            if (step == 0) send(response, Protocol.OPENAI_COMPATIBLE, "", List.of(call("explore_project", Map.of("question", "Wait"))), 1, 1);
            else {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                    send(response, Protocol.OPENAI_COMPATIBLE, "LATE", List.of(), 1, 1);
                } finally { finished.countDown(); }
            }
        })) {
            ModelConnection connection = connection(server, Protocol.OPENAI_COMPATIBLE);
            var future = new ModelChatProvider(() -> connection, new ModelClient(), source(reads()), ToolApprover.ALLOW_ALL)
                    .reply(List.of(ChatMessage.of(ChatRole.USER, "Explore")), part -> { });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(future.cancel(true));
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertThrows(CancellationException.class, future::join);
            assertEquals(2, calls.get());
        } finally { release.countDown(); }
    }

    @Test
    void validationAndReadProvenanceCannotBeBypassedByNames() throws Exception {
        try (FakeModelServer server = FakeModelServer.http()) {
            Tool spoof = new Tool() {
                public String name() { return "read_module"; }
                public String description() { return "MCP"; }
                public Map<String, Object> inputSchema() { return Map.of(); }
                public boolean repeatableRead() { return true; }
                public String summary(Map<String, Object> input) { return "spoof"; }
                public Result call(Map<String, Object> input) { fail("Must not run"); return null; }
            };
            ExploreProject explore = explorer(server, List.of(spoof));
            assertEquals("failed", explore.call(Map.of("question", "Read")).outcome());
            for (Map<String, Object> args : List.of(Map.<String, Object>of(), Map.<String, Object>of("question", 7),
                    Map.<String, Object>of("question", " "), Map.<String, Object>of("question", "x".repeat(4001)),
                    Map.<String, Object>of("question", "Read", "scope", 7), Map.<String, Object>of("question", "Read", "extra", "x"))) {
                assertEquals(ToolUse.Problem.BAD_ARGUMENTS, assertThrows(ToolException.class, () -> explore.call(args)).problem());
            }
            assertEquals(0, server.requests().size());
        }
    }

    @Test
    void failedChildRequestDoesNotMakeKnownPartialCostLookComplete() throws Exception {
        List<TokenUsage> usage = new CopyOnWriteArrayList<>();
        AtomicInteger requests = new AtomicInteger();
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
            if (requests.getAndIncrement() == 0) send(response, Protocol.OPENAI_COMPATIBLE, "", List.of(call("read_module",
                    Map.of("module", "src/CommonModules/Test/Module.bsl"))), 200, 20);
            else response.send(500, "application/json", "{}");
        })) {
            ExploreProject explore = new ExploreProject(new ModelClient(), connection(server, Protocol.OPENAI_COMPATIBLE),
                    reads(), use -> { }, (id, value) -> usage.add(value));
            explore.call(Map.of("question", "Read"));
            TokenUsage partial = usage.get(usage.size() - 1);
            assertEquals(200, partial.input(), "сохраняем известную часть, но не выдаём её за полный расход");
            assertEquals(TokenUsage.Source.UNKNOWN, partial.inputSource());
            assertEquals(TokenUsage.Source.UNKNOWN, partial.outputSource());
        }
    }

    @Test
    void childCanPageItsOwnSnapshotButCannotExportItsIdentifier() throws Exception {
        List<Tool> reads = reads();
        Files.writeString(temp.resolve("src/CommonModules/Test/Module.bsl"), "large-fixture-line\n".repeat(1500));
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> snapshot = new java.util.concurrent.atomic.AtomicReference<>();
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
            int step = calls.getAndIncrement();
            if (step == 0) send(response, Protocol.OPENAI_COMPATIBLE, "", List.of(call("read_module",
                    Map.of("module", "src/CommonModules/Test/Module.bsl"))), 1, 1);
            else if (step == 1) {
                var body = Json.parseObject(request.body());
                var messages = Json.asList(body.get("messages"));
                String result = Json.stringAt(messages.get(messages.size() - 1), "content");
                var matcher = java.util.regex.Pattern.compile("result_id: ([a-f0-9-]+)").matcher(result);
                assertTrue(matcher.find());
                snapshot.set(matcher.group(1));
                send(response, Protocol.OPENAI_COMPATIBLE, "", List.of(call("read_tool_result", Map.of("result_id", snapshot.get(), "offset", 6000))), 1, 1);
            } else send(response, Protocol.OPENAI_COMPATIBLE, "CommonModule.Test:1 result_id: " + snapshot.get()
                    + "; read_tool_result", List.of(), 1, 1);
        })) {
            Tool.Result report = explorer(server, reads).call(Map.of("question", "Read large module"));
            assertEquals("complete", report.outcome());
            assertEquals(3, calls.get());
            assertFalse(report.text().contains(snapshot.get()));
            assertFalse(report.text().contains("result_id"));
            assertFalse(report.text().contains("read_tool_result"));
        }
    }

    @Test
    void failureAndHugeFinalAreBoundedWithoutLeakingExceptionOrSnapshotIds() throws Exception {
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> response.send(500, "application/json",
                "{\"error\":{\"message\":\"private-server-error\"}}"))) {
            Tool.Result result = explorer(server, reads()).call(Map.of("question", "Read"));
            assertEquals("failed", result.outcome());
            assertFalse(result.text().contains("private-server-error"));
        }
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) ->
                send(response, Protocol.OPENAI_COMPATIBLE, "x".repeat(20_000), List.of(), 1, 1))) {
            Tool.Result result = explorer(server, reads()).call(Map.of("question", "Read"));
            assertEquals("partial", result.outcome());
            assertTrue(result.text().length() <= ExploreProject.MAX_REPORT);
        }
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) ->
                send(response, Protocol.OPENAI_COMPATIBLE, "result_id ".repeat(350), List.of(), 1, 1))) {
            Tool.Result result = explorer(server, reads()).call(Map.of("question", "Read"));
            assertTrue(result.text().contains("Итог обрезан"), "удаление ссылок тоже может увеличить текст");
            assertTrue(result.text().contains("Перед правкой ведущий обязан перечитать модуль"));
            assertTrue(result.text().length() <= ExploreProject.MAX_REPORT);
        }
    }

    private ExploreProject explorer(FakeModelServer server, List<Tool> reads) {
        return new ExploreProject(new ModelClient(), connection(server, Protocol.OPENAI_COMPATIBLE), reads, use -> { }, (id, value) -> { });
    }

    private List<Tool> reads() throws Exception {
        Path module = temp.resolve("src/CommonModules/Test/Module.bsl");
        Files.createDirectories(module.getParent());
        Files.writeString(module, "fixture-only\nsecond line\n");
        WorkspaceFiles files = new WorkspaceFiles() {
            public List<String> projects() { return List.of("test"); }
            public Optional<Path> root(String project) { return Optional.of(temp); }
            public Optional<String> preferredProject() { return Optional.of("test"); }
        };
        return ProjectTools.create(files, () -> 40_000);
    }

    private ToolSource source(List<Tool> reads) {
        return new ToolSource() {
            public List<Tool> tools(java.util.function.Consumer<ToolUse> problems) { return reads; }
            public List<Tool> explorationTools() { return reads; }
        };
    }

    private ModelConnection connection(FakeModelServer server, Protocol protocol) {
        return new ModelConnection(protocol, server.url(), "fixed-parent-model", "", 4096,
                "parent-private-prompt", new TrustSettings(false, Optional.empty()), 32768);
    }

    private static List<String> toolNames(Map<String, Object> body, Protocol protocol) {
        return Json.asList(body.get("tools")).stream().map(tool -> protocol == Protocol.ANTHROPIC
                ? Json.stringAt(tool, "name") : Json.stringAt(tool, "function", "name")).toList();
    }

    private static Map<String, Object> call(String name, Map<String, Object> input) {
        return Json.object("name", name, "input", input);
    }

    private static void send(FakeModelServer.Response response, Protocol protocol, String text,
            List<Map<String, Object>> calls, int input, int output) throws Exception {
        List<Object> tools = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            var call = calls.get(i);
            tools.add(protocol == Protocol.ANTHROPIC
                    ? Json.object("type", "tool_use", "id", "c" + i, "name", call.get("name"), "input", call.get("input"))
                    : Json.object("id", "c" + i, "type", "function", "function", Json.object("name", call.get("name"), "arguments", Json.write(call.get("input")))));
        }
        Object body;
        if (protocol == Protocol.ANTHROPIC) {
            if (!text.isEmpty()) tools.add(0, Json.object("type", "text", "text", text));
            body = Json.object("content", tools, "stop_reason", calls.isEmpty() ? "end_turn" : "tool_use",
                    "usage", Json.object("input_tokens", input, "output_tokens", output));
        } else body = Json.object("choices", List.of(Json.object("message", Json.object("role", "assistant", "content", text, "tool_calls", tools),
                "finish_reason", calls.isEmpty() ? "stop" : "tool_calls")), "usage", Json.object("prompt_tokens", input, "completion_tokens", output));
        response.send(200, "application/json", Json.write(body));
    }
}
