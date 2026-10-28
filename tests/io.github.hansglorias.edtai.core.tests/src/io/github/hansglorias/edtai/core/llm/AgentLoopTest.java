package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolApprover;
import io.github.hansglorias.edtai.core.tools.ToolException;
import io.github.hansglorias.edtai.core.tools.ToolSource;

class AgentLoopTest {

    private static final List<ChatMessage> HISTORY = List.of(ChatMessage.of(ChatRole.USER, "Что в модуле?"));

    /** Инструмент для тестов: возвращает имя модуля из аргументов. */
    private static final Tool READ = new Tool() {
        @Override
        public String name() {
            return "read_module";
        }

        @Override
        public String description() {
            return "Читает модуль";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Json.object("type", "object", "properties", Json.object("module", Json.object("type", "string")));
        }

        @Override
        public String summary(Map<String, Object> input) {
            return "Читает модуль " + input.get("module");
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            if ("Нет".equals(input.get("module"))) {
                throw new ToolException("Модуля нет");
            }
            return new Result("Процедура А() КонецПроцедуры // " + input.get("module"), "строки 1–1 из 1");
        }
    };

    private final List<String> parts = new CopyOnWriteArrayList<>();
    private final List<ToolUse> uses = new CopyOnWriteArrayList<>();
    private FakeModelServer server;

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void openAiCallsToolAndSendsResultBack() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("\"role\":\"tool\"")) {
                response.sse(List.of(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Смотрю модуль.\"}}]}\n\n",
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                                + "\"function\":{\"name\":\"read_module\",\"arguments\":\"\"}}]}}]}\n\n",
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"module\\\":\"}}]}}]}\n\n",
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Общий\\\"}\"}}]}}]}\n\n",
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n", "data: [DONE]\n\n"), 5);
            } else {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"В нём процедура А.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
            }
        });

        String answer = run(Protocol.OPENAI_COMPATIBLE);

        assertEquals("Смотрю модуль.\n\nВ нём процедура А.", answer);
        assertEquals(String.join("", parts), answer);
        assertEquals(List.of(new ToolUse("call_1", "read_module", "Читает модуль Общий", "", ToolUse.Status.RUNNING),
                new ToolUse("call_1", "read_module", "Читает модуль Общий", "строки 1–1 из 1", ToolUse.Status.DONE)), uses);

        Map<String, Object> first = Json.parseObject(server.requests().get(0).body());
        assertEquals("read_module", Json.stringAt(first, "tools", 0, "function", "name"));
        assertEquals("object", Json.stringAt(first, "tools", 0, "function", "parameters", "type"));
        Map<String, Object> second = Json.parseObject(server.requests().get(1).body());
        List<Object> messages = Json.asList(second.get("messages"));
        assertEquals(3, messages.size());
        assertEquals("assistant", Json.stringAt(messages.get(1), "role"));
        assertEquals("Смотрю модуль.", Json.stringAt(messages.get(1), "content"));
        assertEquals("call_1", Json.stringAt(messages.get(1), "tool_calls", 0, "id"));
        assertEquals("{\"module\":\"Общий\"}", Json.stringAt(messages.get(1), "tool_calls", 0, "function", "arguments"));
        assertEquals(Map.of("role", "tool", "tool_call_id", "call_1", "content", "Процедура А() КонецПроцедуры // Общий"),
                messages.get(2));
    }

    @Test
    void anthropicCallsToolAndSendsResultBack() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("tool_result")) {
                response.sse(List.of(
                        "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n",
                        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Смотрю.\"}}\n\n",
                        "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read_module\",\"input\":{}}}\n\n",
                        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"module\\\": \\\"Об\"}}\n\n",
                        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"щий\\\"}\"}}\n\n",
                        "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}\n\n",
                        "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"), 5);
            } else {
                response.sse(List.of(
                        "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Готово.\"}}\n\n",
                        "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"), 0);
            }
        });

        String answer = run(Protocol.ANTHROPIC);

        assertEquals("Смотрю.\n\nГотово.", answer);
        Map<String, Object> first = Json.parseObject(server.requests().get(0).body());
        assertEquals("read_module", Json.stringAt(first, "tools", 0, "name"));
        assertEquals("object", Json.stringAt(first, "tools", 0, "input_schema", "type"));
        List<Object> messages = Json.asList(Json.parseObject(server.requests().get(1).body()).get("messages"));
        assertEquals(3, messages.size());
        assertEquals(Map.of("type", "text", "text", "Смотрю."), Json.at(messages.get(1), "content", 0));
        assertEquals("tool_use", Json.stringAt(messages.get(1), "content", 1, "type"));
        assertEquals("Общий", Json.stringAt(messages.get(1), "content", 1, "input", "module"));
        assertEquals("user", Json.stringAt(messages.get(2), "role"));
        assertEquals(Map.of("type", "tool_result", "tool_use_id", "toolu_1", "content", "Процедура А() КонецПроцедуры // Общий"),
                Json.at(messages.get(2), "content", 0));
    }

    @Test
    void toolErrorsGoBackToModel() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("\"role\":\"tool\"")) {
                response.sse(List.of(toolCalls("{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"read_module\",\"arguments\":\"{\\\"module\\\":\\\"Нет\\\"}\"}}",
                        "{\"index\":1,\"id\":\"b\",\"function\":{\"name\":\"delete_all\",\"arguments\":\"{}\"}}",
                        "{\"index\":2,\"id\":\"c\",\"function\":{\"name\":\"read_module\",\"arguments\":\"не json\"}}"), "data: [DONE]\n\n"), 0);
            } else {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"Не вышло.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
            }
        });

        assertEquals("Не вышло.", run(Protocol.OPENAI_COMPATIBLE));

        List<Object> messages = Json.asList(Json.parseObject(server.requests().get(1).body()).get("messages"));
        assertEquals("Ошибка: Модуля нет", Json.stringAt(messages.get(2), "content"));
        assertEquals("Ошибка: Инструмента «delete_all» нет", Json.stringAt(messages.get(3), "content"));
        assertTrue(Json.stringAt(messages.get(4), "content").startsWith("Ошибка: Аргументы вызова — не JSON-объект"));
        assertEquals(List.of(ToolUse.Status.RUNNING, ToolUse.Status.FAILED, ToolUse.Status.FAILED, ToolUse.Status.FAILED),
                uses.stream().map(ToolUse::status).toList());
    }

    @Test
    void stepLimitStopsEndlessToolCalls() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                toolCalls("{\"index\":0,\"id\":\"x\",\"function\":{\"name\":\"read_module\",\"arguments\":\"{\\\"module\\\":\\\"А\\\"}\"}}"),
                "data: [DONE]\n\n"), 0));

        String answer = run(Protocol.OPENAI_COMPATIBLE);

        assertEquals(AgentLoop.MAX_STEPS, server.requests().size());
        assertTrue(answer.startsWith("_Остановлено: модель вызвала инструменты " + AgentLoop.MAX_STEPS + " раз подряд."), answer);
    }

    @Test
    void serverWithoutToolsGetsPlainRequest() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            if (request.body().contains("\"tools\"")) {
                response.send(400, "application/json",
                        "{\"error\":{\"message\":\"\\\"auto\\\" tool choice requires --enable-auto-tool-choice and --tool-call-parser to be set\"}}");
            } else {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"Без инструментов.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
            }
        });
        ModelChatProvider provider = new ModelChatProvider(this::connectionOpenAi, new ModelClient(), problems -> List.of(READ),
                ToolApprover.ALLOW_ALL);

        assertEquals("Без инструментов.", provider.reply(HISTORY, parts::add, uses::add).get(10, TimeUnit.SECONDS));
        assertEquals(ToolUse.Status.FAILED, uses.get(0).status());
        assertEquals(AgentLoop.TOOLS_UNSUPPORTED, uses.get(0).outcome());
        assertEquals(2, server.requests().size());

        // Второй вопрос тому же серверу — сразу без инструментов.
        assertEquals("Без инструментов.", provider.reply(HISTORY, parts::add, uses::add).get(10, TimeUnit.SECONDS));
        assertEquals(3, server.requests().size());
        assertNull(Json.parseObject(server.requests().get(2).body()).get("tools"));
    }

    @Test
    void fullResponseWithToolCalls() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.send(200, "application/json",
                request.body().contains("\"role\":\"tool\"")
                        ? "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Итог.\"}}]}"
                        : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"f1\","
                                + "\"type\":\"function\",\"function\":{\"name\":\"read_module\",\"arguments\":{\"module\":\"Б\"}}}]}}]}"));

        assertEquals("Итог.", run(Protocol.OPENAI_COMPATIBLE));
        assertEquals("строки 1–1 из 1", uses.get(1).outcome());
        Object assistant = Json.at(Json.parse(server.requests().get(1).body()), "messages", 1);
        assertFalse(Json.asObject(assistant).containsKey("content"), "пустой текст ассистента не передаём");
        assertEquals("{\"module\":\"Б\"}", Json.stringAt(assistant, "tool_calls", 0, "function", "arguments"));
    }

    @Test
    void approvalDeniedGoesBackToModel() throws Exception {
        server = FakeModelServer.http().handler(this::oneToolCallThenText);
        List<String> asked = new CopyOnWriteArrayList<>();
        ToolApprover deny = (use, arguments) -> {
            asked.add(use.summary() + " " + arguments);
            return CompletableFuture.completedFuture(ToolApprover.Decision.DENY);
        };

        assertEquals("Понял.", run(Protocol.OPENAI_COMPATIBLE, problems -> List.of(approved(READ)), deny));

        assertEquals(List.of("Читает модуль Общий {\"module\":\"Общий\"}"), asked);
        assertEquals(List.of(ToolUse.Status.AWAITING, ToolUse.Status.FAILED), uses.stream().map(ToolUse::status).toList());
        assertEquals("отклонено", uses.get(1).outcome());
        String result = Json.stringAt(Json.parse(server.requests().get(1).body()), "messages", 2, "content");
        assertTrue(result.startsWith("Ошибка: Пользователь отклонил вызов"), result);
    }

    @Test
    void approvalAllowedRunsTool() throws Exception {
        server = FakeModelServer.http().handler(this::oneToolCallThenText);

        assertEquals("Понял.", run(Protocol.OPENAI_COMPATIBLE, problems -> List.of(approved(READ)),
                (use, arguments) -> CompletableFuture.completedFuture(ToolApprover.Decision.ALLOW_IN_CHAT)));

        assertEquals(List.of(ToolUse.Status.AWAITING, ToolUse.Status.RUNNING, ToolUse.Status.DONE),
                uses.stream().map(ToolUse::status).toList());
    }

    @Test
    void toolSourceProblemsAreShownAndAnswerContinues() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Ответ.\"}}]}\n\n", "data: [DONE]\n\n"), 0));
        ToolUse problem = new ToolUse("mcp:EDT", "mcp", "MCP «EDT»", "не подключился: нет", ToolUse.Status.FAILED);

        assertEquals("Ответ.", run(Protocol.OPENAI_COMPATIBLE, problems -> {
            problems.accept(problem);
            return List.of();
        }, ToolApprover.ALLOW_ALL));

        assertEquals(List.of(problem), uses);
        assertNull(Json.parseObject(server.requests().get(0).body()).get("tools"));
    }

    private void oneToolCallThenText(FakeModelServer.Request request, FakeModelServer.Response response) throws Exception {
        if (!request.body().contains("\"role\":\"tool\"")) {
            response.sse(List.of(toolCalls("{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"read_module\",\"arguments\":"
                    + "\"{\\\"module\\\":\\\"Общий\\\"}\"}}"), "data: [DONE]\n\n"), 0);
        } else {
            response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"Понял.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
        }
    }

    /** Тот же инструмент, но с подтверждением, как у MCP-серверов. */
    private static Tool approved(Tool tool) {
        return new Tool() {
            @Override
            public String name() {
                return tool.name();
            }

            @Override
            public String description() {
                return tool.description();
            }

            @Override
            public Map<String, Object> inputSchema() {
                return tool.inputSchema();
            }

            @Override
            public boolean needsApproval() {
                return true;
            }

            @Override
            public String summary(Map<String, Object> input) {
                return tool.summary(input);
            }

            @Override
            public Result call(Map<String, Object> input) throws ToolException {
                return tool.call(input);
            }
        };
    }

    @Test
    void oldToolResultsAreOmittedWhenWindowIsSmall() throws Exception {
        Tool big = new Tool() {
            @Override
            public String name() {
                return "big";
            }

            @Override
            public String description() {
                return "Много текста";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Json.object("type", "object");
            }

            @Override
            public String summary(Map<String, Object> input) {
                return "Большой результат";
            }

            @Override
            public Result call(Map<String, Object> input) {
                return new Result("я".repeat(1500), "1500 символов");
            }
        };
        server = FakeModelServer.http().handler((request, response) -> {
            int results = request.body().split("\"role\":\"tool\"", -1).length - 1;
            response.sse(List.of(results < 2
                    ? toolCalls("{\"index\":0,\"id\":\"call_" + results + "\",\"function\":{\"name\":\"big\",\"arguments\":\"{}\"}}")
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"Готово.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
        });
        // Окно 1000 токенов — бюджет 2250 символов: два результата по 1500 не помещаются.
        ModelConnection connection = new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", "", 0, "",
                new TrustSettings(false, Optional.empty()), 1000);
        ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient(), problems -> List.of(big),
                ToolApprover.ALLOW_ALL);

        assertEquals("Готово.", provider.reply(HISTORY, parts::add, uses::add).get(15, TimeUnit.SECONDS));

        List<Object> messages = Json.asList(Json.parseObject(server.requests().get(2).body()).get("messages"));
        assertEquals(AgentLoop.OMITTED, Json.stringAt(messages.get(2), "content"));
        assertEquals("я".repeat(1500), Json.stringAt(messages.get(4), "content"));
    }

    private String run(Protocol protocol) throws Exception {
        return run(protocol, problems -> List.of(READ), ToolApprover.ALLOW_ALL);
    }

    private String run(Protocol protocol, ToolSource tools, ToolApprover approver) throws Exception {
        ModelConnection connection = new ModelConnection(protocol, server.url(), "test-model", "", 0, "",
                new TrustSettings(false, Optional.empty()));
        ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient(), tools, approver);
        return provider.reply(HISTORY, parts::add, uses::add).get(15, TimeUnit.SECONDS);
    }

    private ModelConnection connectionOpenAi() {
        return new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", "", 0, "",
                new TrustSettings(false, Optional.empty()));
    }

    private static String toolCalls(String... calls) {
        return "data: {\"choices\":[{\"delta\":{\"tool_calls\":[" + String.join(",", calls) + "]}}]}\n\n";
    }
}
