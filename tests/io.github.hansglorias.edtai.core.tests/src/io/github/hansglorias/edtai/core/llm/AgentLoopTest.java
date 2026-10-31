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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.TokenUsage;
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
    private final List<TokenUsage> usages = new CopyOnWriteArrayList<>();
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
                new ToolUse("call_1", "read_module", "Читает модуль Общий", "строки 1–1 из 1", ToolUse.Status.DONE)),
                withoutUsage());
        assertTrue(uses.stream().allMatch(use -> use.usage() != null && use.usage().estimated()), "токены шага — по оценке");

        Map<String, Object> first = Json.parseObject(server.requests().get(0).body());
        assertEquals("read_module", Json.stringAt(first, "tools", 0, "function", "name"));
        assertEquals("object", Json.stringAt(first, "tools", 0, "function", "parameters", "type"));
        Map<String, Object> second = Json.parseObject(server.requests().get(1).body());
        List<Object> messages = chat(second);
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

        List<Object> messages = chat(Json.parseObject(server.requests().get(1).body()));
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
    void anthropicErrorAboutToolsIsShownNotSilenced() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.send(400, "application/json",
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"tools.0.custom.input_schema: invalid\"}}"));
        ModelConnection connection = new ModelConnection(Protocol.ANTHROPIC, server.url(), "test-model", "", 0, "",
                new TrustSettings(false, Optional.empty()));
        ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient(), problems -> List.of(READ),
                ToolApprover.ALLOW_ALL);

        Exception error = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> provider.reply(HISTORY, parts::add, uses::add).get(10, TimeUnit.SECONDS));
        assertTrue(error.getMessage().contains("input_schema"), error.getMessage());
        assertEquals(1, server.requests().size(), "без молчаливого повтора без инструментов");
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
        assertTrue(uses.get(0).outcome().startsWith(AgentLoop.TOOLS_UNSUPPORTED + " Причина: "), uses.get(0).outcome());
        assertTrue(uses.get(0).outcome().contains("enable-auto-tool-choice"), uses.get(0).outcome());
        assertEquals(2, server.requests().size());

        // Второй вопрос тому же серверу — сразу без инструментов.
        assertEquals("Без инструментов.", provider.reply(HISTORY, parts::add, uses::add).get(10, TimeUnit.SECONDS));
        assertEquals(3, server.requests().size());
        assertNull(Json.parseObject(server.requests().get(2).body()).get("tools"));
        assertEquals(ModelChatProvider.WITHOUT_TOOLS, uses.get(uses.size() - 1).outcome(), "каждый ответ без инструментов — на виду");
        provider.forgetRejections();
        provider.reply(HISTORY, parts::add, uses::add).get(10, TimeUnit.SECONDS);
        assertTrue(server.requests().get(3).body().contains("\"tools\""), "после «Нового чата» — снова с инструментами");
    }

    @Test
    void contextOverflowIsRetriedWithShorterRequest() throws Exception {
        AtomicInteger count = new AtomicInteger();
        server = FakeModelServer.http().handler((request, response) -> {
            if (count.getAndIncrement() == 0) {
                response.send(400, "application/json", "{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens\","
                        + "\"code\":\"context_length_exceeded\"}}");
            } else {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"Поместилось.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
            }
        });

        assertEquals("Поместилось.", run(Protocol.OPENAI_COMPATIBLE));
        assertEquals(2, server.requests().size());
    }

    @Test
    void rejectedMcpSchemaFallsBackToBuiltInTools() throws Exception {
        Tool broken = new Tool() {
            @Override
            public String name() {
                return "mcp_broken";
            }

            @Override
            public String description() {
                return "Инструмент MCP с кривой схемой";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Json.object("type", "object");
            }

            @Override
            public boolean needsApproval() {
                return true;
            }

            @Override
            public String summary(Map<String, Object> input) {
                return "MCP";
            }

            @Override
            public Result call(Map<String, Object> input) {
                return new Result("", "");
            }
        };
        server = FakeModelServer.http().handler((request, response) -> {
            if (request.body().contains("mcp_broken")) {
                response.send(400, "application/json", "{\"error\":{\"message\":\"Invalid schema for function 'mcp_broken'\","
                        + "\"param\":\"tools[1].function.parameters\"}}");
            } else {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"Со встроенными.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
            }
        });
        ModelChatProvider provider = new ModelChatProvider(this::connectionOpenAi, new ModelClient(), problems -> List.of(READ, broken),
                ToolApprover.ALLOW_ALL);

        assertEquals("Со встроенными.", provider.reply(HISTORY, parts::add, uses::add).get(10, TimeUnit.SECONDS));
        assertTrue(uses.get(0).outcome().startsWith(AgentLoop.MCP_REJECTED), uses.get(0).outcome());
        assertEquals("read_module", Json.stringAt(Json.parseObject(server.requests().get(1).body()), "tools", 0, "function", "name"));

        // Следующий ответ снова пробует все инструменты: сервер не попал в «без инструментов».
        provider.reply(HISTORY, parts::add, uses::add).get(10, TimeUnit.SECONDS);
        assertTrue(server.requests().get(2).body().contains("mcp_broken"));
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
        Object assistant = chat(Json.parseObject(server.requests().get(1).body())).get(1);
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
        assertEquals(ToolUse.Problem.DENIED, uses.get(1).problem());
        assertEquals("Читает модуль Общий — отклонено", uses.get(1).withUsage(null).label());
        String result = Json.stringAt(chat(Json.parseObject(server.requests().get(1).body())).get(2), "content");
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

    @Test
    void anthropicUsageIsSummedOverSteps() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            boolean first = !request.body().contains("tool_result");
            response.sse(List.of(
                    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":"
                            + (first ? "100,\"cache_read_input_tokens\":50" : "200") + ",\"output_tokens\":1}}}\n\n",
                    first ? "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                            + "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"read_module\",\"input\":{}}}\n\n"
                            : "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                                    + "{\"type\":\"text_delta\",\"text\":\"Готово.\"}}\n\n",
                    "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{},\"usage\":{\"output_tokens\":"
                            + (first ? 20 : 10) + "}}\n\n",
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"), 0);
        });

        assertEquals("Готово.", runWithUsage(Protocol.ANTHROPIC));

        assertEquals(List.of(new TokenUsage(150, 20, 170, false), new TokenUsage(350, 30, 210, false)), usages);
        ToolUse call = uses.get(uses.size() - 1);
        assertEquals(new TokenUsage(150, 20, 170, false), call.usage(), "у вызова — токены шага, на котором он решён");
        assertTrue(call.label().endsWith(" · ↑150 ↓20"), call.label());
    }

    @Test
    void openAiUsageFromLastChunk() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Да.\"}}]}\n\n",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}\n\n",
                "data: [DONE]\n\n"), 0));

        runWithUsage(Protocol.OPENAI_COMPATIBLE);

        assertEquals(List.of(new TokenUsage(12, 3, 15, false)), usages);
    }

    @Test
    void usageWithoutServerDataIsEstimated() throws Exception {
        server = FakeModelServer.http().handler(this::oneToolCallThenText);

        runWithUsage(Protocol.OPENAI_COMPATIBLE);

        assertEquals(2, usages.size());
        TokenUsage last = usages.get(1);
        assertTrue(last.estimated());
        assertTrue(last.input() > usages.get(0).input(), "второй шаг добавляет запрос целиком: " + usages);
        assertTrue(last.context() > 0 && last.context() < last.input());
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
    void repeatedCallIsNotRunAndRulesAreInSystemPrompt() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            int results = request.body().split("\"role\":\"tool\"", -1).length - 1;
            response.sse(List.of(results < 2
                    ? toolCalls("{\"index\":0,\"id\":\"call_" + results + "\",\"function\":{\"name\":\"read_module\","
                            + "\"arguments\":\"{\\\"module\\\":\\\"Общий\\\"}\"}}")
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"Готово.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
        });

        assertEquals("Готово.", run(Protocol.OPENAI_COMPATIBLE));

        assertEquals(ToolUse.failed("call_1", "read_module", "Читает модуль Общий", ToolUse.Problem.REPEATED, ""),
                uses.get(uses.size() - 1).withUsage(null));
        assertEquals("Читает модуль Общий — повтор, не выполнен", uses.get(uses.size() - 1).withUsage(null).label());
        List<Object> messages = Json.asList(Json.parseObject(server.requests().get(2).body()).get("messages"));
        assertEquals("Ошибка: " + AgentLoop.REPEATED, Json.stringAt(messages.get(messages.size() - 1), "content"));
        assertEquals("system", Json.stringAt(messages.get(0), "role"));
        assertTrue(Json.stringAt(messages.get(0), "content").contains("Правила работы с инструментами"));
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
                    ? toolCalls("{\"index\":0,\"id\":\"call_" + results + "\",\"function\":{\"name\":\"big\",\"arguments\":\"{\\\"part\\\":" + results + "}\"}}")
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"Готово.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
        });
        // Окно 1000 токенов — бюджет 2250 символов: два результата по 1500 не помещаются.
        ModelConnection connection = new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", "", 0, "",
                new TrustSettings(false, Optional.empty()), 1000);
        ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient(), problems -> List.of(big),
                ToolApprover.ALLOW_ALL);

        assertEquals("Готово.", provider.reply(HISTORY, parts::add, uses::add).get(15, TimeUnit.SECONDS));

        List<Object> messages = chat(Json.parseObject(server.requests().get(2).body()));
        assertEquals(AgentLoop.OMITTED, Json.stringAt(messages.get(2), "content"));
        assertEquals("я".repeat(1500), Json.stringAt(messages.get(4), "content"));
    }

    /** Вызовы без токенов шага: для сравнения с ожидаемыми. */
    private List<ToolUse> withoutUsage() {
        return uses.stream().map(use -> use.withUsage(null)).toList();
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

    private String runWithUsage(Protocol protocol) throws Exception {
        ModelConnection connection = new ModelConnection(protocol, server.url(), "test-model", "", 0, "",
                new TrustSettings(false, Optional.empty()));
        ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient(), problems -> List.of(READ),
                ToolApprover.ALLOW_ALL);
        return provider.reply(HISTORY, parts::add, uses::add, usages::add).get(15, TimeUnit.SECONDS);
    }

    private ModelConnection connectionOpenAi() {
        return new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", "", 0, "",
                new TrustSettings(false, Optional.empty()));
    }

    /** Сообщения запроса OpenAI без системного: там правила работы с инструментами. */
    private static List<Object> chat(Map<String, Object> body) {
        return Json.asList(body.get("messages")).stream().filter(message -> !"system".equals(Json.stringAt(message, "role"))).toList();
    }

    private static String toolCalls(String... calls) {
        return "data: {\"choices\":[{\"delta\":{\"tool_calls\":[" + String.join(",", calls) + "]}}]}\n\n";
    }
}
