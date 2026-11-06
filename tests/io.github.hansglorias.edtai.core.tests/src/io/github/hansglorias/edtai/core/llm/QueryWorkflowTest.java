package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.skills.SkillLibrary;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.SkillTools;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolActivity;
import io.github.hansglorias.edtai.core.tools.ToolApprover;

class QueryWorkflowTest {
    @TempDir Path temp;
    private static final List<ChatMessage> HISTORY = List.of(ChatMessage.of(ChatRole.USER, "Проверь запрос"));

    @Test void rootIsAutomaticAndQueryMethodIsLoadedOnDemandForBothProtocols() throws Exception {
        SkillLibrary library = library(Set.of());
        String root = library.find("development-workflow").orElseThrow().body();
        String method = library.find("query-development").orElseThrow().body();
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger step = new AtomicInteger();
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                if (step.getAndIncrement() == 0) send(response, protocol, "skill", Map.of("name", "query-development"));
                else send(response, protocol, null, Map.of());
            })) {
                List<ToolUse> uses = new CopyOnWriteArrayList<>();
                assertEquals("Итог", run(server, protocol, library.prompt(), SkillTools.create(library), ToolApprover.ALLOW_ALL, uses));
                assertEquals(2, server.requests().size(), "нет скрытого запроса выбора методики или второго агента");
                String first = server.requests().get(0).body();
                assertTrue(first.contains(Json.write(root).substring(1, Json.write(root).length() - 1)));
                assertFalse(first.contains(Json.write(method).substring(1, Json.write(method).length() - 1)), "полная методика не в каталоге");
                assertTrue(server.requests().get(1).body().contains(Json.write(method).substring(1, Json.write(method).length() - 1)),
                        "вся методика пришла одним результатом skill даже при окне 8k с резервом 4k");
                assertEquals(1, uses.stream().filter(use -> use.status() == ToolUse.Status.DONE).count());
            }
        }
    }

    @Test void userAndProjectRootOverridesAndDisablingAreHonoredInRealRequests() throws Exception {
        SkillLibrary library = library(Set.of());
        library.save("development-workflow", "Свой маршрут", "USER_WORKFLOW_MARKER");
        Path project = Files.createDirectories(temp.resolve("project/.edtai/skills"));
        Files.writeString(project.resolve("development-workflow.md"),
                "---\nname: development-workflow\ndescription: Маршрут проекта\n---\nPROJECT_WORKFLOW_MARKER");
        for (Protocol protocol : Protocol.values()) {
            for (int mode = 0; mode < 4; mode++) {
                SkillLibrary selected = switch (mode) {
                    case 0, 3 -> library;
                    case 1 -> new SkillLibrary(temp.resolve("user"), () -> List.of(project), Set.of());
                    default -> new SkillLibrary(temp.resolve("user"), () -> List.of(project), Set.of(), () -> Set.of("development-workflow"));
                };
                List<Tool> tools = mode == 3 ? SkillTools.create(library).subList(1, 2) : SkillTools.create(selected);
                try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> send(response, protocol, null, Map.of()))) {
                    run(server, protocol, "", tools, ToolApprover.ALLOW_ALL, new CopyOnWriteArrayList<>());
                    String body = server.requests().get(0).body();
                    assertEquals(mode == 0, body.contains("USER_WORKFLOW_MARKER"));
                    assertEquals(mode == 1, body.contains("PROJECT_WORKFLOW_MARKER"));
                }
            }
        }
    }

    @Test void defaultValidatorUsesOriginalSchemaApprovalAndResultWithoutDiscovery() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            for (boolean allow : List.of(true, false)) {
                AtomicInteger executed = new AtomicInteger();
                AtomicInteger approvals = new AtomicInteger();
                AtomicInteger step = new AtomicInteger();
                Tool validator = new ToolActivity().track(List.of(validator(executed, 0)), tool -> true).get(0);
                assertEquals("validate_query", validator.mcpName(), "обёртка сохраняет исходное имя");
                try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                    if (step.getAndIncrement() == 0) send(response, protocol, validator.name(), Map.of("queryText", "ВЫБРАТЬ 1 КАК Значение"));
                    else send(response, protocol, null, Map.of());
                })) {
                    List<ToolUse> uses = new CopyOnWriteArrayList<>();
                    run(server, protocol, "", List.of(validator), (use, input) -> {
                        approvals.incrementAndGet();
                        assertEquals(validator.name(), use.name());
                        return CompletableFuture.completedFuture(allow ? ToolApprover.Decision.ALLOW : ToolApprover.Decision.DENY);
                    }, uses);
                    assertEquals(1, approvals.get(), "публикация схемы не заменяет разрешение вызова");
                    assertEquals(allow ? 1 : 0, executed.get());
                    assertEquals(2, server.requests().size());
                    assertTrue(server.requests().get(0).body().contains("installed_validator_schema"));
                    assertEquals(allow, server.requests().get(1).body().contains("INVALID"));
                    assertEquals(allow, uses.stream().anyMatch(use -> use.name().equals(validator.name()) && use.status() == ToolUse.Status.DONE));
                    assertTrue(uses.stream().noneMatch(use -> use.name().equals("discover_mcp_tools")));
                }
            }
        }
    }

    @Test void oversizedDefaultStaysInCatalogWithoutOverflow() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger executed = new AtomicInteger();
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> send(response, protocol, null, Map.of()))) {
                run(server, protocol, "", List.of(validator(executed, 80_000)), ToolApprover.ALLOW_ALL, new CopyOnWriteArrayList<>());
                String body = server.requests().get(0).body();
                assertFalse(body.contains("installed_validator_schema"));
                assertTrue(body.contains("discover_mcp_tools"));
                assertEquals(0, executed.get());
            }
        }
    }

    @Test void rejectedDefaultDoesNotReturnThroughDiscoveryForEitherProtocol() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger executed = new AtomicInteger();
            AtomicInteger step = new AtomicInteger();
            Tool validator = validator(executed, 0);
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                int current = step.getAndIncrement();
                if (current == 0) response.send(400, "application/json", "{\"error\":{\"message\":\"invalid tool schema\"}}");
                else if (current == 1) send(response, protocol, "discover_mcp_tools", Map.of("action", "select", "names", List.of(validator.name())));
                else send(response, protocol, null, Map.of());
            })) {
                List<ToolUse> uses = new CopyOnWriteArrayList<>();
                run(server, protocol, "", List.of(validator), ToolApprover.ALLOW_ALL, uses);
                assertEquals(3, server.requests().size());
                assertTrue(server.requests().get(0).body().contains("installed_validator_schema"));
                assertFalse(server.requests().get(1).body().contains("installed_validator_schema"));
                assertFalse(server.requests().get(2).body().contains("installed_validator_schema"));
                assertEquals(0, executed.get());
                assertTrue(uses.stream().anyMatch(use -> use.outcome().startsWith(AgentLoop.MCP_REJECTED)));
                assertTrue(uses.stream().anyMatch(use -> use.name().equals("discover_mcp_tools") && use.status() == ToolUse.Status.FAILED));
            }
        }
    }

    @Test void stopWhileApprovingDefaultValidatorCancelsWithoutExecution() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger executed = new AtomicInteger();
            Tool validator = validator(executed, 0);
            CountDownLatch waiting = new CountDownLatch(1);
            CompletableFuture<ToolApprover.Decision> permission = new CompletableFuture<>();
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) ->
                    send(response, protocol, validator.name(), Map.of("queryText", "ВЫБРАТЬ 1 КАК Значение")))) {
                ModelChatProvider provider = new ModelChatProvider(() -> connection(server, protocol, ""), new ModelClient(),
                        problems -> List.of(validator), (use, arguments) -> { waiting.countDown(); return permission; });
                CompletableFuture<String> reply = provider.reply(HISTORY, text -> { });
                try {
                    assertTrue(waiting.await(10, TimeUnit.SECONDS));
                    assertTrue(reply.cancel(true));
                    assertThrows(java.util.concurrent.CancellationException.class, () -> permission.get(10, TimeUnit.SECONDS));
                    assertEquals(0, executed.get());
                    assertEquals(1, server.requests().size());
                } finally { reply.cancel(true); }
            }
        }
    }

    @Test void defaultSchemasAreNotInjectedIntoSpecialistRequests() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            AtomicInteger executed = new AtomicInteger();
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> send(response, protocol, null, Map.of()))) {
                AgentLoop child = new AgentLoop(new ModelClient(), connection(server, protocol, ""), HISTORY,
                        problems -> List.of(validator(executed, 0)), ToolApprover.ALLOW_ALL, text -> { }, use -> { }, usage -> { }, 2, true);
                child.start().get(15, TimeUnit.SECONDS);
                assertFalse(server.requests().get(0).body().contains("installed_validator_schema"));
                assertEquals(0, executed.get());
            }
        }
    }

    private SkillLibrary library(Set<String> disabled) {
        return new SkillLibrary(temp.resolve("user"), List::of, Set.of(), () -> disabled);
    }

    private static Tool validator(AtomicInteger executed, int padding) {
        return new Tool() {
            public String name() { return "EDT__validate_query"; }
            public String mcpName() { return "validate_query"; }
            public String description() { return "Installed validator"; }
            public boolean mcp() { return true; }
            public boolean needsApproval() { return true; }
            public boolean repeatableRead() { return true; }
            public Map<String, Object> inputSchema() {
                return Json.object("type", "object", "properties", Json.object("queryText",
                        Json.object("type", "string", "description", "installed_validator_schema" + "x".repeat(padding))),
                        "required", List.of("queryText"));
            }
            public String summary(Map<String, Object> input) { return "Проверка запроса"; }
            public Result call(Map<String, Object> input) {
                executed.incrementAndGet();
                assertEquals("ВЫБРАТЬ 1 КАК Значение", input.get("queryText"));
                return new Result("{\"success\":true,\"valid\":false,\"validationState\":\"INVALID\"}", "INVALID");
            }
        };
    }

    private static ModelConnection connection(FakeModelServer server, Protocol protocol, String prompt) {
        return new ModelConnection(protocol, server.url(), "test-model", "", 4096, prompt,
                new TrustSettings(false, Optional.empty()), 8192);
    }

    private static String run(FakeModelServer server, Protocol protocol, String prompt, List<Tool> tools,
            ToolApprover approver, List<ToolUse> uses) throws Exception {
        ModelChatProvider provider = new ModelChatProvider(() -> connection(server, protocol, prompt), new ModelClient(),
                problems -> tools, approver);
        return provider.reply(HISTORY, text -> { }, uses::add).get(15, TimeUnit.SECONDS);
    }

    private static void send(FakeModelServer.Response response, Protocol protocol, String tool, Map<String, Object> input) throws Exception {
        if (protocol == Protocol.ANTHROPIC) {
            response.send(200, "application/json", Json.write(Json.object("type", "message", "role", "assistant", "content",
                    tool == null ? List.of(Json.object("type", "text", "text", "Итог"))
                            : List.of(Json.object("type", "tool_use", "id", "call", "name", tool, "input", input)),
                    "stop_reason", tool == null ? "end_turn" : "tool_use")));
        } else {
            Map<String, Object> message = tool == null ? Json.object("role", "assistant", "content", "Итог")
                    : Json.object("role", "assistant", "content", "", "tool_calls", List.of(Json.object("id", "call", "type", "function",
                            "function", Json.object("name", tool, "arguments", Json.write(input)))));
            response.send(200, "application/json", Json.write(Json.object("choices", List.of(Json.object("message", message,
                    "finish_reason", tool == null ? "stop" : "tool_calls")))));
        }
    }
}
