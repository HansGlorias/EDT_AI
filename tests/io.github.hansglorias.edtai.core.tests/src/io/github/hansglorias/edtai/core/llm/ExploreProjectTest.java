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
            List<io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent> diagnostics = new CopyOnWriteArrayList<>();
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                try {
                    int step = calls.getAndIncrement();
                    Map<String, Object> body = Json.parseObject(request.body());
                    assertEquals("fixed-parent-model", body.get("model"));
                    if (step == 0) {
                        assertTrue(toolNames(body, protocol).contains("explore_project"));
                        assertTrue(request.body().contains("Специалисты:"));
                        assertTrue(request.body().contains("role=review"));
                        send(response, protocol, "", List.of(call("explore_project", Json.object("question", "Find links", "scope", "CommonModules"))), 10, 2);
                    } else if (step == 1) {
                        assertFalse(request.body().contains("parent-private-prompt"));
                        assertFalse(request.body().contains("parent-private-history"));
                        assertFalse(request.body().contains("Специалисты:"), "ребёнку не предлагают делегирование");
                        assertEquals(4096L, body.get("max_tokens"));
                        assertEquals(List.of("project_info", "list_objects", "read_module", "search_code", "check_module"), toolNames(body, protocol));
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
                String result = new ModelChatProvider(() -> connection, new ModelClient(diagnostics::add), source, ToolApprover.ALLOW_ALL)
                        .reply(List.of(ChatMessage.of(ChatRole.USER, "parent-private-history")), part -> { }, progress::add, usage::add)
                        .get(15, TimeUnit.SECONDS);
                assertEquals(List.of(), errors);
                assertEquals("PARENT_FINAL", result);
                assertEquals(4, calls.get());
                assertEquals(4, diagnostics.size(), "родительские и дочерние физические запросы учтены один раз");
                assertEquals(2, diagnostics.stream().map(event -> event.runId()).distinct().count());
                assertEquals(4, diagnostics.stream().map(event -> event.requestId()).distinct().count());
                assertTrue(diagnostics.stream().allMatch(event -> event.status()
                        == io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Status.SUCCESS));
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
            assertTrue(result.text().contains("Причина: были ошибки чтения или инструментов"));
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
    void childSearchesOnlyTheRequestedModuleWithTheUpdatedSchema() throws Exception {
        List<Tool> reads = reads();
        Path other = temp.resolve("src/CommonModules/Other/Module.bsl");
        Files.createDirectories(other.getParent());
        Files.writeString(other, "fixture-only other-module-marker\n");
        AtomicInteger requests = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
            try {
                if (requests.getAndIncrement() == 0) {
                    Map<String, Object> schema = Json.asList(Json.parseObject(request.body()).get("tools")).stream()
                            .filter(tool -> "search_code".equals(Json.stringAt(tool, "function", "name")))
                            .map(tool -> Json.asObject(Json.at(tool, "function", "parameters"))).findFirst().orElseThrow();
                    assertEquals("string", Json.stringAt(schema, "properties", "module", "type"));
                    send(response, Protocol.OPENAI_COMPATIBLE, "", List.of(call("search_code", Map.of(
                            "query", "fixture-only", "module", "src/CommonModules/Test/Module.bsl"))), 1, 1);
                } else {
                    assertTrue(request.body().contains("Test.Модуль:1: fixture-only"));
                    assertFalse(request.body().contains("other-module-marker"));
                    send(response, Protocol.OPENAI_COMPATIBLE, "Ссылки: CommonModule.Test:1. Другие модули не исследованы.", List.of(), 1, 1);
                }
            } catch (Throwable error) { errors.add(error); throw error; }
        })) {
            Tool.Result report = explorer(server, reads).call(Map.of("question", "Find fixture-only in Test"));
            assertEquals(List.of(), errors);
            assertEquals("complete", report.outcome());
            assertEquals(2, requests.get());
        }
    }

    @Test
    void childUsesParentOutputSettingIncludingProtocolDefaults() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            for (int limit : new int[] { 0, 1024, 8192 }) {
                AtomicInteger calls = new AtomicInteger();
                try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                    send(response, protocol, "Проверено", calls.getAndIncrement() == 0
                            ? List.of(call("read_module", Map.of("module", "src/CommonModules/Test/Module.bsl")))
                            : List.of(), 1, 1);
                })) {
                    ModelConnection parent = new ModelConnection(protocol, server.url(), "fixed-parent-model", "", limit,
                            "parent-private-prompt", new TrustSettings(false, Optional.empty()), 32768);
                    Tool.Result result = new ExploreProject(new ModelClient(), parent, reads(), use -> { }, (id, value) -> { })
                            .call(Map.of("question", "Read"));
                    assertEquals("complete", result.outcome());
                    for (var request : server.requests()) {
                        Long expected = limit > 0 ? Long.valueOf(limit) : protocol == Protocol.ANTHROPIC
                                ? Long.valueOf(ModelConnection.ANTHROPIC_DEFAULT_MAX_TOKENS) : null;
                        assertEquals(expected, Json.parseObject(request.body()).get("max_tokens"));
                    }
                }
            }
        }
    }

    @Test
    void childCanFinishResearchBeyondTwelveRequests() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            List<Tool> reads = reads();
            Files.writeString(temp.resolve("src/CommonModules/Test/Module.bsl"), "fixture\n".repeat(50));
            AtomicInteger calls = new AtomicInteger();
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                int step = calls.incrementAndGet();
                send(response, protocol, step == 16 ? "FINISHED_AFTER_FIFTEEN_READS" : "", step == 16 ? List.of()
                        : List.of(call("read_module", Map.of("module", "src/CommonModules/Test/Module.bsl", "first_line", step))), 1, 1);
            })) {
                Tool.Result result = new ExploreProject(new ModelClient(), connection(server, protocol), reads,
                        use -> { }, (id, value) -> { }).call(Map.of("question", "Read several parts"));
                assertEquals(16, calls.get());
                assertEquals("complete", result.outcome(), result.text());
                assertTrue(result.text().contains("FINISHED_AFTER_FIFTEEN_READS"));
            }
        }
    }

    @Test
    void fullChildReportIsPreservedInPanelAndParentCanPageItsTail() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger calls = new AtomicInteger();
            List<ToolUse> progress = new CopyOnWriteArrayList<>();
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            String report = java.util.stream.IntStream.range(0, 1200).mapToObj(i -> "Проверенный вывод " + i + ".\n")
                    .collect(java.util.stream.Collectors.joining()) + "FULL_REPORT_TAIL";
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                try {
                    int step = calls.getAndIncrement();
                    if (step == 0) send(response, protocol, "", List.of(call("delegate_task",
                            Map.of("role", "review", "task", "Review module"))), 1, 1);
                    else if (step == 1) send(response, protocol, "", List.of(call("read_module",
                            Map.of("module", "src/CommonModules/Test/Module.bsl"))), 1, 1);
                    else if (step == 2) send(response, protocol, report, List.of(), 1, 1);
                    else if (step == 3) {
                        assertFalse(request.body().contains("FULL_REPORT_TAIL"), "большой отчёт передан первой страницей");
                        var matcher = java.util.regex.Pattern.compile("result_id: ([a-f0-9-]+)").matcher(request.body());
                        assertTrue(matcher.find(), request.body());
                        send(response, protocol, "", List.of(call("read_tool_result",
                                Map.of("result_id", matcher.group(1), "offset", report.length() - 1000))), 1, 1);
                    } else {
                        assertEquals(4, step);
                        assertTrue(request.body().contains("FULL_REPORT_TAIL"), "хвост отчёта доступен ведущему");
                        send(response, protocol, "PARENT_FINAL", List.of(), 1, 1);
                    }
                } catch (Throwable error) { errors.add(error); throw error; }
            })) {
                String result = new ModelChatProvider(() -> connection(server, protocol), new ModelClient(), source(reads()), ToolApprover.ALLOW_ALL)
                        .reply(List.of(ChatMessage.of(ChatRole.USER, "Review with a specialist")), part -> { }, progress::add, value -> { })
                        .get(15, TimeUnit.SECONDS);
                assertEquals(List.of(), errors);
                assertEquals("PARENT_FINAL", result);
                assertEquals(5, calls.get());
                ToolUse child = progress.stream().filter(use -> use.name().equals(ToolUse.SUBAGENT)
                        && use.status() == ToolUse.Status.DONE).findFirst().orElseThrow();
                assertEquals(report, child.outcome());
                assertTrue(progress.stream().anyMatch(use -> use.name().equals("delegate_task") && use.outcome().equals("complete")));
            }
        }
    }

    @Test
    void actualServerOutputLimitIsExplainedInResultAndPanel() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger calls = new AtomicInteger();
            List<ToolUse> progress = new CopyOnWriteArrayList<>();
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                boolean last = calls.getAndIncrement() > 0;
                send(response, protocol, last ? "UNFINISHED_REPORT" : "", last ? List.of()
                        : List.of(call("read_module", Map.of("module", "src/CommonModules/Test/Module.bsl"))), 1, 1, last);
            })) {
                Tool.Result result = new ExploreProject(new ModelClient(), connection(server, protocol), reads(),
                        progress::add, (id, value) -> { }).call(Map.of("question", "Read"));
                assertEquals(2, calls.get(), "явный лимит пользователя не обходится новыми генерациями");
                assertEquals("partial", result.outcome());
                assertTrue(result.text().contains("сервер остановил генерацию по лимиту токенов ответа"));
                assertTrue(result.text().contains("UNFINISHED_REPORT"));
                String shown = progress.get(progress.size() - 1).outcome();
                assertTrue(shown.startsWith("Завершено частично: сервер остановил генерацию по лимиту токенов ответа"));
                assertTrue(shown.contains("UNFINISHED_REPORT"));
            }
        }
    }

    @Test
    void physicalRequestBudgetReservesSummaryAfterOverflowRetry() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger calls = new AtomicInteger();
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                try {
                    int step = calls.getAndIncrement();
                    if (step == 0) send(response, protocol, "", List.of(call("read_module",
                            Map.of("module", "src/CommonModules/Test/Module.bsl"))), 1, 1);
                    else if (step == 1) response.send(400, "application/json", "{\"error\":{\"message\":\"maximum context length exceeded\"}}");
                    else {
                        assertEquals(2, step);
                        assertTrue(request.body().contains(AgentLoop.SUMMARY_ONLY));
                        send(response, protocol, "SUMMARY_AFTER_RETRY", List.of(), 1, 1);
                    }
                } catch (Throwable error) { errors.add(error); throw error; }
            })) {
                List<Tool> reads = reads();
                AgentLoop child = new AgentLoop(new ModelClient(), connection(server, protocol),
                        List.of(ChatMessage.of(ChatRole.USER, "Read")), problems -> reads, ToolApprover.ALLOW_ALL,
                        part -> { }, use -> { }, value -> { }, 2, true);
                assertEquals("SUMMARY_AFTER_RETRY", child.start().get(15, TimeUnit.SECONDS));
                assertEquals(List.of(), errors);
                assertTrue(child.incomplete());
                assertTrue(child.incompleteReason().contains("лимит 2 физических запросов"));
                assertEquals(3, calls.get());
            }
        }
    }

    @Test
    void summaryCannotExecuteToolsEvenIfModelIgnoresInstruction() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger executed = new AtomicInteger();
            Tool read = new Tool() {
                public String name() { return "read_fixture"; }
                public String description() { return "Read fixture"; }
                public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
                public String summary(Map<String, Object> input) { return "Read fixture"; }
                public Result call(Map<String, Object> input) { executed.incrementAndGet(); return new Result("fixture", "read"); }
            };
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                int step = calls.incrementAndGet();
                send(response, protocol, step == 3 ? "SUMMARY_WITH_UNEXECUTED_CALL" : "",
                        List.of(call("read_fixture", Map.of("step", step))), 1, 1);
            })) {
                AgentLoop child = new AgentLoop(new ModelClient(), connection(server, protocol),
                        List.of(ChatMessage.of(ChatRole.USER, "Read")), problems -> List.of(read), ToolApprover.ALLOW_ALL,
                        part -> { }, use -> { }, value -> { }, 2, true);
                assertEquals("SUMMARY_WITH_UNEXECUTED_CALL", child.start().get(15, TimeUnit.SECONDS));
                assertTrue(child.incomplete());
                assertEquals(3, calls.get());
                assertEquals(1, executed.get(), "ни вызовы граничного шага, ни вызовы резервного итога не исполняются");
            }
        }
    }

    @Test
    void childStepsAreBoundedAndReportIsPartialAtLimit() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger calls = new AtomicInteger();
            List<ToolUse> progress = new CopyOnWriteArrayList<>();
            List<Tool> reads = reads();
            Files.writeString(temp.resolve("src/CommonModules/Test/Module.bsl"), "fixture\n".repeat(100));
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                int step = calls.incrementAndGet();
                send(response, protocol, step > ExploreProject.MAX_STEPS ? "SUMMARY_AT_LIMIT" : "",
                        step > ExploreProject.MAX_STEPS ? List.of() : List.of(call("read_module",
                                Json.object("module", "src/CommonModules/Test/Module.bsl", "first_line", step))), 1, 1);
            })) {
                ExploreProject runner = new ExploreProject(new ModelClient(), connection(server, protocol), reads,
                        progress::add, (id, value) -> { });
                Tool.Result result = runner.call(Map.of("question", "Read forever"));
                assertEquals(ExploreProject.MAX_STEPS + 1, calls.get(), "рабочие запросы и резервный текстовый итог");
                String last = server.requests().get(ExploreProject.MAX_STEPS).body();
                assertEquals(protocol == Protocol.ANTHROPIC, last.contains("\"tools\""));
                assertTrue(last.contains(AgentLoop.SUMMARY_ONLY));
                assertEquals("partial", result.outcome());
                assertTrue(result.text().contains("лимит 50 шагов"), result.text());
                assertTrue(result.text().contains("SUMMARY_AT_LIMIT"));
                assertTrue(progress.get(progress.size() - 1).outcome().contains("лимит 50 шагов"));
            }
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
    void failureAndMissingReadsAreExplainedWithoutLeakingExceptionOrSnapshotIds() throws Exception {
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> response.send(500, "application/json",
                "{\"error\":{\"message\":\"private-server-error\"}}"))) {
            Tool.Result result = explorer(server, reads()).call(Map.of("question", "Read"));
            assertEquals("failed", result.outcome());
            assertFalse(result.text().contains("private-server-error"));
            assertTrue(result.text().contains("запрос специалиста завершился ошибкой"));
        }
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) ->
                send(response, Protocol.OPENAI_COMPATIBLE, "```text\n" + "x".repeat(20_000) + "\n```", List.of(), 1, 1))) {
            Tool.Result result = explorer(server, reads()).call(Map.of("question", "Read"));
            assertEquals("partial", result.outcome());
            assertTrue(result.text().contains("нет подтверждённых чтений проекта"));
            assertTrue(result.text().contains("x".repeat(20_000)));
        }
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) ->
                send(response, Protocol.OPENAI_COMPATIBLE, "```text\n" + "result_id ".repeat(350) + "\n```", List.of(), 1, 1))) {
            Tool.Result result = explorer(server, reads()).call(Map.of("question", "Read"));
            assertFalse(result.text().contains("result_id"));
            assertTrue(result.text().contains("[ссылка недоступна] ".repeat(350).stripTrailing()));
            assertTrue(result.text().contains("Перед правкой ведущий обязан перечитать модуль"));
        }
    }

    @Test
    void rolesShareQuotaValidateScopeAndDiscoverOnlyApprovedReadOnlyMcp() throws Exception {
        List<ToolUse> progress = new CopyOnWriteArrayList<>();
        List<ToolUse> approvals = new CopyOnWriteArrayList<>();
        List<TokenUsage> usage = new CopyOnWriteArrayList<>();
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Tool safe = mcp("diagnostic_read", true, false, executed);
        Tool unknown = mcp("unknown_tool", false, false, executed);
        Tool write = mcp("write_tool", true, true, executed);
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
            try {
                int step = requests.getAndIncrement();
                Map<String, Object> body = Json.parseObject(request.body());
                List<String> names = toolNames(body, Protocol.OPENAI_COMPATIBLE);
                assertFalse(names.contains("delegate_task"));
                assertFalse(names.contains("explore_project"));
                assertFalse(names.contains("unknown_tool"));
                assertFalse(names.contains("write_tool"));
                assertTrue(request.body().contains("scope-marker"));
                if (step == 0) {
                    assertTrue(request.body().contains("Проверяй гипотезу"));
                    assertFalse(names.contains("diagnostic_read"));
                    send(response, Protocol.OPENAI_COMPATIBLE, "", List.of(call("discover_mcp_tools", Map.of("action", "list"))), 10, 2);
                } else if (step == 1) {
                    assertTrue(request.body().contains("diagnostic_read"));
                    assertFalse(request.body().contains("unknown_tool"));
                    assertFalse(request.body().contains("write_tool"));
                    send(response, Protocol.OPENAI_COMPATIBLE, "", List.of(call("discover_mcp_tools", Map.of("action", "select", "names", List.of("diagnostic_read")))), 10, 2);
                } else if (step == 2) {
                    assertTrue(names.contains("diagnostic_read"));
                    send(response, Protocol.OPENAI_COMPATIBLE, "", List.of(call("diagnostic_read", Map.of())), 10, 2);
                } else if (step == 3) send(response, Protocol.OPENAI_COMPATIBLE, "Проверено чтением диагностики", List.of(), 10, 2);
                else {
                    assertTrue(request.body().contains("Ищи конкретные ошибки"));
                    send(response, Protocol.OPENAI_COMPATIBLE, "Ревью без чтений", List.of(), 10, 2);
                }
            } catch (Throwable error) { errors.add(error); throw error; }
        })) {
            ExploreProject runner = new ExploreProject(new ModelClient(), connection(server, Protocol.OPENAI_COMPATIBLE),
                    List.of(safe, unknown, write), progress::add, (id, value) -> usage.add(value), (use, args) -> {
                        approvals.add(use);
                        return java.util.concurrent.CompletableFuture.completedFuture(ToolApprover.Decision.ALLOW);
                    });
            DelegateTask delegate = new DelegateTask(runner);
            Tool.Result verified = delegate.call(Map.of("role", "verify", "task", "check", "scope", "scope-marker"), "parent");
            assertEquals("complete", verified.outcome());
            assertTrue(verified.text().contains("Роль: verify"));
            assertEquals(1, executed.get());
            assertEquals(1, approvals.size());
            assertTrue(progress.stream().anyMatch(use -> use.id().equals(approvals.get(0).id())));
            assertEquals(40, usage.get(usage.size() - 1).input());
            assertEquals("partial", delegate.call(Map.of("role", "review", "task", "review", "scope", "scope-marker")).outcome());
            assertEquals("partial", runner.call(Map.of("question", "third")).outcome());
            assertEquals(5, requests.get());
            assertEquals(List.of(), errors);
            for (Map<String, Object> input : List.of(Map.<String, Object>of("role", "write", "task", "x"),
                    Map.<String, Object>of("role", "verify", "task", "x", "scope", 7),
                    Map.<String, Object>of("role", "review", "task", "x", "extra", "x"))) {
                assertThrows(ToolException.class, () -> delegate.call(input));
            }
        }
    }

    @Test
    void corruptChildKeepsItsPrefixAndCheckedAreaWithoutRetry() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger requests = new AtomicInteger();
            List<ToolUse> progress = new CopyOnWriteArrayList<>();
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                if (requests.getAndIncrement() == 0) send(response, protocol, "Нашёл модуль.",
                        List.of(call("read_module", Map.of("module", "src/CommonModules/Test/Module.bsl"))), 1, 1);
                else send(response, protocol, "Найдена полезная связь.\n\n" + "</function_results>".repeat(1500), List.of(), 1, 1);
            })) {
                ExploreProject runner = new ExploreProject(new ModelClient(), connection(server, protocol), reads(),
                        progress::add, (id, usage) -> { });
                Tool.Result report = runner.call(Map.of("question", "Найди связи"));
                assertEquals("partial", report.outcome());
                assertTrue(report.text().contains("Найдена полезная связь."));
                assertTrue(report.text().contains("read_module") || report.text().contains("Читает модуль"));
                assertFalse(report.text().contains("</function_results>"));
                assertTrue(progress.stream().anyMatch(use -> use.name().equals(ToolUse.SUBAGENT)
                        && use.status() == ToolUse.Status.DONE && use.outcome().contains("Найдена полезная связь.")));
                assertEquals(2, requests.get());
            }
        }
    }

    @Test
    void catalogSelectionAloneIsNotEvidenceOfVerification() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
            if (requests.getAndIncrement() == 0) send(response, Protocol.OPENAI_COMPATIBLE, "", List.of(call("discover_mcp_tools", Map.of("action", "list"))), 1, 1);
            else send(response, Protocol.OPENAI_COMPATIBLE, "Everything works", List.of(), 1, 1);
        })) {
            ExploreProject runner = explorer(server, List.of(mcp("diagnostic_read", true, false, new AtomicInteger())));
            assertEquals("partial", new DelegateTask(runner).call(Map.of("role", "verify", "task", "check")).outcome());
        }
    }

    private static Tool mcp(String name, boolean readOnly, boolean modifies, AtomicInteger calls) {
        return new Tool() {
            public String name() { return name; }
            public String description() { return "test read"; }
            public Map<String, Object> inputSchema() { return Map.of("type", "object", "properties", Map.of()); }
            public boolean mcp() { return true; }
            public boolean safeInPlan() { return readOnly; }
            public boolean modifies() { return modifies; }
            public boolean needsApproval() { return true; }
            public String summary(Map<String, Object> input) { return name; }
            public Result call(Map<String, Object> input) { calls.incrementAndGet(); return new Result("diagnostic fixture", "read"); }
        };
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
        send(response, protocol, text, calls, input, output, false);
    }

    private static void send(FakeModelServer.Response response, Protocol protocol, String text,
            List<Map<String, Object>> calls, int input, int output, boolean truncated) throws Exception {
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
            body = Json.object("content", tools, "stop_reason", truncated ? "max_tokens" : calls.isEmpty() ? "end_turn" : "tool_use",
                    "usage", Json.object("input_tokens", input, "output_tokens", output));
        } else body = Json.object("choices", List.of(Json.object("message", Json.object("role", "assistant", "content", text, "tool_calls", tools),
                "finish_reason", truncated ? "length" : calls.isEmpty() ? "stop" : "tool_calls")), "usage", Json.object("prompt_tokens", input, "completion_tokens", output));
        response.send(200, "application/json", Json.write(body));
    }
}
