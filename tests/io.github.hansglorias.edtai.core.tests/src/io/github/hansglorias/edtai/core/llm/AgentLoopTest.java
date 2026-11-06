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
        public boolean repeatableRead() {
            return true;
        }

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

    /**
     * DeepSeek и Kimi в режиме рассуждений: reasoning_content шага с вызовом инструмента
     * обязателен в следующем запросе, иначе 400. В ленту рассуждение не попадает.
     */
    @Test
    void openAiReturnsReasoningContentWithToolCall() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("\"role\":\"tool\"")) {
                response.sse(List.of(
                        "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null,\"reasoning_content\":\"Надо \"}}]}\n\n",
                        "data: {\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":\"прочитать модуль.\"}}]}\n\n",
                        toolCalls("{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read_module\","
                                + "\"arguments\":\"{\\\"module\\\":\\\"Общий\\\"}\"}}"),
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n", "data: [DONE]\n\n"), 0);
            } else if (!request.body().contains("\"reasoning_content\":\"Надо прочитать модуль.\"")) {
                response.send(400, "application/json", "{\"error\":{\"message\":\"Missing `reasoning_content` field in the assistant"
                        + " message at message index 1\",\"type\":\"invalid_request_error\"}}");
            } else {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"Всё ясно.\"}}]}\n\n",
                        "data: {\"choices\":[{\"delta\":{\"content\":\"В нём процедура А.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
            }
        });

        ModelConnection connection = new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", "", 0, "",
                new TrustSettings(false, Optional.empty()));
        AtomicInteger reasoning = new AtomicInteger();
        List<Integer> beforeText = new CopyOnWriteArrayList<>();
        String answer = new ModelChatProvider(() -> connection, new ModelClient(), problems -> List.of(READ), ToolApprover.ALLOW_ALL)
                .reply(HISTORY, part -> {
                    beforeText.add(reasoning.get());
                    parts.add(part);
                }, uses::add, usage -> { }, reasoning::incrementAndGet).get(15, TimeUnit.SECONDS);

        assertEquals("В нём процедура А.", answer);
        assertEquals(String.join("", parts), answer);
        assertEquals(3, reasoning.get(), "каждая часть рассуждения — сигнал для «рассуждает…»");
        assertEquals(List.of(3), beforeText);
        List<Object> messages = chat(Json.parseObject(server.requests().get(1).body()));
        assertEquals("Надо прочитать модуль.", Json.stringAt(messages.get(1), "reasoning_content"));
        assertNull(Json.at(messages.get(1), "content"), "рассуждение не подмешивается в текст");
        assertEquals("call_1", Json.stringAt(messages.get(1), "tool_calls", 0, "id"));
    }

    /** vLLM и OpenRouter присылают поле reasoning: возвращаем его под тем же именем. */
    @Test
    void openAiReturnsReasoningFieldUnderSameName() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("\"role\":\"tool\"")) {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"reasoning\":\"Читаю.\"}}]}\n\n",
                        toolCalls("{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read_module\","
                                + "\"arguments\":\"{}\"}}"),
                        "data: [DONE]\n\n"), 0);
            } else {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"Готово.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
            }
        });

        assertEquals("Готово.", run(Protocol.OPENAI_COMPATIBLE));

        Object assistant = chat(Json.parseObject(server.requests().get(1).body())).get(1);
        assertEquals("Читаю.", Json.stringAt(assistant, "reasoning"));
        assertNull(Json.at(assistant, "reasoning_content"));
    }

    /** MiniMax и Qwen без reasoning-parser: рассуждение в тегах think в тексте; модели возвращаем как было. */
    @Test
    void openAiReturnsThinkTagsWithToolCall() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("\"role\":\"tool\"")) {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"<thi\"}}]}\n\n",
                        "data: {\"choices\":[{\"delta\":{\"content\":\"nk>Нужен модуль.</think>\\n\\nСмотрю.\"}}]}\n\n",
                        toolCalls("{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read_module\","
                                + "\"arguments\":\"{}\"}}"),
                        "data: [DONE]\n\n"), 0);
            } else {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"<think>Ясно.</think>Готово.\"}}]}\n\n",
                        "data: [DONE]\n\n"), 0);
            }
        });

        ModelConnection connection = new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", "", 0, "",
                new TrustSettings(false, Optional.empty()));
        AtomicInteger reasoning = new AtomicInteger();
        String answer = new ModelChatProvider(() -> connection, new ModelClient(), problems -> List.of(READ), ToolApprover.ALLOW_ALL)
                .reply(HISTORY, parts::add, uses::add, usage -> { }, reasoning::incrementAndGet).get(15, TimeUnit.SECONDS);

        assertTrue(reasoning.get() >= 1, "часть внутри <think> — сигнал для «рассуждает…»");
        assertEquals("Смотрю.\n\nГотово.", answer);
        assertEquals(String.join("", parts), answer);
        Object assistant = chat(Json.parseObject(server.requests().get(1).body())).get(1);
        assertEquals("<think>Нужен модуль.</think>\n\nСмотрю.", Json.stringAt(assistant, "content"));
    }

    /** Без рассуждения в ответе — запрос прежний: лишних полей нет. */
    @Test
    void openAiSendsNoReasoningFieldsWithoutReasoning() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("\"role\":\"tool\"")) {
                response.sse(List.of(toolCalls("{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"read_module\","
                        + "\"arguments\":\"{}\"}}"), "data: [DONE]\n\n"), 0);
            } else {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"Готово.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
            }
        });

        assertEquals("Готово.", run(Protocol.OPENAI_COMPATIBLE));

        String second = server.requests().get(1).body();
        assertFalse(second.contains("reasoning"), second);
        assertFalse(second.contains("<think>"), second);
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
        // Каждый раз новые аргументы: это не повтор, а бесконечный поиск — его останавливает лимит шагов.
        AtomicInteger step = new AtomicInteger();
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                toolCalls("{\"index\":0,\"id\":\"x\",\"function\":{\"name\":\"read_module\",\"arguments\":\"{\\\"module\\\":\\\"А"
                        + step.getAndIncrement() + "\\\"}\"}}"),
                "data: [DONE]\n\n"), 0));

        String answer = run(Protocol.OPENAI_COMPATIBLE);

        assertEquals(AgentLoop.MAX_STEPS, server.requests().size());
        assertTrue(answer.startsWith("_Остановлено: модель вызвала инструменты " + AgentLoop.MAX_STEPS + " раз подряд."), answer);
        assertFalse(server.requests().get(AgentLoop.WRAP_UP_STEP - 1).body().contains("Служебная пометка"));
        assertTrue(server.requests().get(AgentLoop.WRAP_UP_STEP).body().contains("30-й шаг с инструментами"),
                "после 30-го шага — напоминание отвечать");
        assertTrue(server.requests().get(AgentLoop.MAX_STEPS - 1).body().contains("это был последний шаг с инструментами"),
                "перед лимитом — просьба дать итог");
    }

    /** Модель послушалась пометки о последнем шаге: вместо «Остановлено» — её итог. */
    @Test
    void lastStepNoteLetsModelAnswer() throws Exception {
        AtomicInteger step = new AtomicInteger();
        server = FakeModelServer.http().handler((request, response) -> {
            if (request.body().contains("это был последний шаг")) {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"Итог: нашёл А.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
            } else {
                response.sse(List.of(toolCalls("{\"index\":0,\"id\":\"x\",\"function\":{\"name\":\"read_module\","
                        + "\"arguments\":\"{\\\"module\\\":\\\"А" + step.getAndIncrement() + "\\\"}\"}}"), "data: [DONE]\n\n"), 0);
            }
        });

        assertEquals("Итог: нашёл А.", run(Protocol.OPENAI_COMPATIBLE));
        assertEquals(AgentLoop.MAX_STEPS, server.requests().size());
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

        assertEquals(List.of(new TokenUsage(150, 20, 170, false), new TokenUsage(350, 30, 210, false)), steps());
        TokenUsage beforeSecond = usages.get(2);
        assertEquals(TokenUsage.Source.ESTIMATE, beforeSecond.contextSource(), "перед запросом — оценка его размера");
        assertEquals(new TokenUsage(150, 20, beforeSecond.context(), TokenUsage.Source.SERVER, TokenUsage.Source.SERVER,
                TokenUsage.Source.ESTIMATE), beforeSecond, "расход прежних шагов — тот же, окно — нового запроса");
        assertTrue(beforeSecond.context() > 0);
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

        assertEquals(List.of(new TokenUsage(12, 3, 15, false)), steps());
        assertEquals(TokenUsage.Source.UNKNOWN, usages.get(0).inputSource(), "до первого ответа расход неизвестен");
    }

    @Test
    void partialUsageKeepsServerZeroAndEstimatesOnlyMissingField() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Ответ длиннее четырёх символов\"}}]}\n\n",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":0}}\n\n",
                "data: [DONE]\n\n"), 0));
        runWithUsage(Protocol.OPENAI_COMPATIBLE);
        TokenUsage usage = steps().get(0);
        assertEquals(0, usage.input());
        assertEquals(TokenUsage.Source.SERVER, usage.inputSource());
        assertTrue(usage.output() > 0);
        assertEquals(TokenUsage.Source.ESTIMATE, usage.outputSource());
        assertEquals(TokenUsage.Source.MIXED, usage.contextSource());
    }

    @Test
    void usageWithoutServerDataIsEstimated() throws Exception {
        server = FakeModelServer.http().handler(this::oneToolCallThenText);

        runWithUsage(Protocol.OPENAI_COMPATIBLE);

        assertEquals(4, usages.size(), "перед каждым из двух шагов — оценка запроса");
        TokenUsage last = steps().get(1);
        assertTrue(last.estimated());
        assertTrue(last.input() > steps().get(0).input(), "второй шаг добавляет запрос целиком: " + usages);
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
            public boolean repeatableRead() {
                return tool.repeatableRead();
            }

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
        assertEquals("Ошибка: " + AgentLoop.REPEATED + "\n\n" + AgentLoop.STUCK,
                Json.stringAt(messages.get(messages.size() - 1), "content"), "шаг из одних повторов — с предупреждением");
        assertEquals("system", Json.stringAt(messages.get(0), "role"));
        assertTrue(Json.stringAt(messages.get(0), "content").contains("Правила работы с инструментами"));
    }

    @Test
    void endlessRepeatsTurnToolsOffAndAnswerWithFoundData() throws Exception {
        AtomicInteger step = new AtomicInteger();
        server = FakeModelServer.http().handler((request, response) -> {
            int id = step.getAndIncrement();
            response.sse(List.of(request.body().contains("\"tools\"")
                    ? toolCalls("{\"index\":0,\"id\":\"call_" + id + "\",\"function\":{\"name\":\"read_module\","
                            + "\"arguments\":\"{\\\"module\\\":\\\"Общий\\\"}\"}}")
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"Итог по найденному.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
        });

        assertEquals("Итог по найденному.", run(Protocol.OPENAI_COMPATIBLE));

        assertEquals(4, server.requests().size(), "чтение, повтор с предупреждением, повтор — и ответ без инструментов");
        String last = server.requests().get(3).body();
        assertFalse(last.contains("\"tools\""));
        assertTrue(last.contains("инструменты отключены"));
        assertTrue(uses.stream().anyMatch(use -> use.id().equals("loop") && use.status() == ToolUse.Status.FAILED));
    }

    @Test
    void stepsKeepToWorkingPartOfLargeWindow() throws Exception {
        Tool big = new Tool() {
            @Override public boolean repeatableRead() { return true; }
            @Override public String name() { return "read_module"; }
            @Override public String description() { return "Читает модуль"; }
            @Override public Map<String, Object> inputSchema() { return Json.object("type", "object"); }
            @Override public String summary(Map<String, Object> input) { return "Читает " + input.get("module"); }
            @Override public Result call(Map<String, Object> input) {
                return new Result(input.get("module") + ":" + "ж".repeat(19_000), "строки");
            }
        };
        AtomicInteger step = new AtomicInteger();
        server = FakeModelServer.http().handler((request, response) -> {
            int id = step.getAndIncrement();
            response.sse(List.of(id < 14
                    ? toolCalls("{\"index\":0,\"id\":\"c" + id + "\",\"function\":{\"name\":\"read_module\","
                            + "\"arguments\":\"{\\\"module\\\":\\\"М" + id + "\\\"}\"}}")
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"Готово.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
        });
        ModelConnection connection = new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", "", 0, "",
                new TrustSettings(false, Optional.empty()), 120_000);
        ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient(), problems -> List.of(big),
                ToolApprover.ALLOW_ALL);
        assertEquals("Готово.", provider.reply(HISTORY, parts::add, uses::add).get(15, TimeUnit.SECONDS));

        String last = server.requests().get(server.requests().size() - 1).body();
        int working = RequestBudget.working(RequestBudget.chars(connection));
        assertTrue(last.contains(AgentLoop.OMITTED), "старые результаты убраны раньше края окна");
        assertTrue(last.length() < working + 25_000, "запрос держится около рабочей части: " + last.length());
        assertTrue(last.contains("М13:"), "последний результат передан");
        // Убирается пачкой: после первого убирания следующий шаг идёт с тем же началом запроса.
        List<Integer> omitted = server.requests().stream()
                .map(request -> request.body().split(java.util.regex.Pattern.quote(AgentLoop.OMITTED), -1).length - 1).toList();
        int first = 0;
        while (omitted.get(first) == 0) first++;
        assertEquals(omitted.get(first), omitted.get(first + 1), "пачкой, не по шагу: " + omitted);
    }

    /** Инструмент изменения для тестов: считает вызовы. */
    private static Tool writer(AtomicInteger writes) {
        return new Tool() {
            @Override public boolean modifies() { return true; }
            @Override public String name() { return "edit_module"; }
            @Override public String description() { return "Правит модуль"; }
            @Override public Map<String, Object> inputSchema() { return Json.object("type", "object"); }
            @Override public String summary(Map<String, Object> input) { return "Правит модуль"; }
            @Override public Result call(Map<String, Object> input) {
                writes.incrementAndGet();
                return new Result("Изменено", "строки 1–2");
            }
        };
    }

    @Test
    void claimedChangesWithoutEditToolAreSentBackOnce() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        server = FakeModelServer.http().handler((request, response) -> {
            String body = request.body();
            if (!body.contains("Служебная пометка EDT AI: в этом ответе")) {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"Готово. Добавил обработчик кнопки.\"}}]}\n\n",
                        "data: [DONE]\n\n"), 0);
            } else if (!body.contains("\"role\":\"tool\"")) {
                response.sse(List.of(toolCalls("{\"index\":0,\"id\":\"e1\",\"function\":{\"name\":\"edit_module\",\"arguments\":\"{}\"}}"),
                        "data: [DONE]\n\n"), 0);
            } else {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"Теперь правка внесена.\"}}]}\n\n",
                        "data: [DONE]\n\n"), 0);
            }
        });

        String answer = run(Protocol.OPENAI_COMPATIBLE, problems -> List.of(READ, writer(writes)), ToolApprover.ALLOW_ALL);

        assertEquals(1, writes.get(), "после пометки модель внесла правку инструментом");
        assertEquals(3, server.requests().size());
        assertTrue(answer.endsWith("Теперь правка внесена."), answer);
        assertTrue(uses.stream().anyMatch(use -> use.id().equals("claim")));
        List<Object> messages = Json.asList(Json.parseObject(server.requests().get(1).body()).get("messages"));
        assertEquals("assistant", Json.stringAt(messages.get(messages.size() - 2), "role"));
        assertEquals(AgentLoop.UNMADE_CHANGES, Json.stringAt(messages.get(messages.size() - 1), "content"));
    }

    @Test
    void honestAnswersAndReadOnlyToolsAreNotChecked() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Я ничего не менял: правка не нужна.\"}}]}\n\n", "data: [DONE]\n\n"), 0));
        run(Protocol.OPENAI_COMPATIBLE, problems -> List.of(READ, writer(new AtomicInteger())), ToolApprover.ALLOW_ALL);
        assertEquals(1, server.requests().size(), "отрицание — не заявление о правке");

        server.close();
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Готово. Добавил бы обработчик так.\"}}]}\n\n", "data: [DONE]\n\n"), 0));
        run(Protocol.OPENAI_COMPATIBLE, problems -> List.of(READ), ToolApprover.ALLOW_ALL);
        assertEquals(1, server.requests().size(), "без инструментов изменения (План) проверки нет");
        assertTrue(ChangeClaims.claims("Изменения внесены в модуль"));
        assertFalse(ChangeClaims.claims("Внесу правку после ответа"));
        assertFalse(ChangeClaims.claims("Я не добавил новую процедуру"));
    }

    @Test
    void explorationAtStepLimitStillReturnsReport() throws Exception {
        AtomicInteger step = new AtomicInteger();
        server = FakeModelServer.http().handler((request, response) -> {
            int id = step.getAndIncrement();
            response.sse(List.of(request.body().contains("\"tools\"")
                    ? toolCalls("{\"index\":0,\"id\":\"c" + id + "\",\"function\":{\"name\":\"read_module\","
                            + "\"arguments\":\"{\\\"module\\\":\\\"М" + id + "\\\"}\"}}")
                    : "data: {\"choices\":[{\"delta\":{\"content\":\"Отчёт: модули М0 и М1.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
        });
        AgentLoop child = new AgentLoop(new ModelClient(), connectionOpenAi(), HISTORY, problems -> List.of(READ),
                ToolApprover.ALLOW_ALL, parts::add, uses::add, usages::add, 2, true);

        assertEquals("Отчёт: модули М0 и М1.", child.start().get(15, TimeUnit.SECONDS));
        assertTrue(child.incomplete(), "отчёт по неполному поиску");
        assertEquals(3, server.requests().size(), "два шага и последний запрос без инструментов");
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
        // Малое окно: старые страницы заменяются ссылками, последний результат ограничен страницей.
        ModelConnection connection = new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", "", 0, "",
                new TrustSettings(false, Optional.empty()), 2400);
        ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient(), problems -> List.of(big),
                ToolApprover.ALLOW_ALL);

        assertEquals("Готово.", provider.reply(HISTORY, parts::add, uses::add).get(15, TimeUnit.SECONDS));

        List<Object> messages = chat(Json.parseObject(server.requests().get(2).body()));
        assertTrue(Json.stringAt(messages.get(2), "content").contains("result_id"));
        assertFalse(Json.stringAt(messages.get(2), "content").contains("яя"));
        assertTrue(Json.stringAt(messages.get(4), "content").contains("next_offset"));
        assertFalse(Json.stringAt(messages.get(4), "content").contains("я".repeat(1500)));
    }

    /** Вызовы без токенов шага: для сравнения с ожидаемыми. */
    /** Итоги шагов: перед каждым — оценка размера отправленного запроса. */
    private List<TokenUsage> steps() {
        List<TokenUsage> steps = new java.util.ArrayList<>();
        for (int i = 1; i < usages.size(); i += 2) {
            steps.add(usages.get(i));
        }
        return steps;
    }

    private List<ToolUse> withoutUsage() {
        return uses.stream().map(use -> use.withUsage(null)).toList();
    }

    private String run(Protocol protocol) throws Exception {
        return run(protocol, problems -> List.of(READ), ToolApprover.ALLOW_ALL);
    }

    @Test
    void openAiDiscoversMcpWithoutActivatingItInsideBatch() throws Exception {
        discoveryRoundTrip(Protocol.OPENAI_COMPATIBLE);
    }

    @Test
    void anthropicDiscoversMcpWithoutActivatingItInsideBatch() throws Exception {
        discoveryRoundTrip(Protocol.ANTHROPIC);
    }

    @Test
    void selectedMcpStillHonoursDeniedApproval() throws Exception {
        discoveryRoundTrip(Protocol.OPENAI_COMPATIBLE, false);
    }

    private void discoveryRoundTrip(Protocol protocol) throws Exception {
        discoveryRoundTrip(protocol, true);
    }

    private void discoveryRoundTrip(Protocol protocol, boolean allow) throws Exception {
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger step = new AtomicInteger();
        Tool remote = new Tool() {
            @Override public String name() { return "update_remote"; }
            @Override public String description() { return "Меняет тестовый объект"; }
            @Override public boolean mcp() { return true; }
            @Override public boolean modifies() { return true; }
            @Override public boolean needsApproval() { return true; }
            @Override public Map<String, Object> inputSchema() {
                return Json.object("type", "object", "properties",
                        Json.object("remote_schema_marker", Json.object("type", "string")));
            }
            @Override public String summary(Map<String, Object> input) { return "Тестовая правка"; }
            @Override public Result call(Map<String, Object> input) {
                executed.incrementAndGet();
                return new Result("Изменено", "Изменено");
            }
        };
        server = FakeModelServer.http().handler((request, response) -> {
            int currentStep = step.getAndIncrement();
            List<String> events = new java.util.ArrayList<>();
            if (currentStep == 0) {
                events.add(discoveryCall(protocol, 0, "list", "discover_mcp_tools", Json.object("action", "list", "offset", 0)));
            } else if (currentStep == 1) {
                events.add(discoveryCall(protocol, 0, "select", "discover_mcp_tools",
                        Json.object("action", "select", "names", List.of(remote.name()))));
                events.add(discoveryCall(protocol, 1, "too_early", remote.name(), Map.of()));
            } else if (currentStep == 2) {
                events.add(discoveryCall(protocol, 0, "execute", remote.name(), Map.of()));
            } else {
                events.add(protocol == Protocol.ANTHROPIC
                        ? "event: content_block_delta\ndata: " + Json.write(Json.object("type", "content_block_delta", "index", 0,
                                "delta", Json.object("type", "text_delta", "text", "Готово."))) + "\n\n"
                        : "data: " + Json.write(Json.object("choices", List.of(Json.object("delta", Json.object("content", "Готово."))))) + "\n\n");
            }
            events.add(protocol == Protocol.ANTHROPIC
                    ? "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n" : "data: [DONE]\n\n");
            response.sse(events, 0);
        });

        ToolApprover approver = (use, arguments) -> {
            approvals.incrementAndGet();
            assertEquals(remote.name(), use.name());
            return CompletableFuture.completedFuture(allow ? ToolApprover.Decision.ALLOW : ToolApprover.Decision.DENY);
        };
        assertEquals("Готово.", run(protocol, problems -> List.of(READ, remote), approver));
        assertEquals(allow ? 1 : 0, executed.get(), "выбор не разрешает выполнение в том же пакете и не заменяет подтверждение");
        assertEquals(1, approvals.get(), "управление каталогом не спрашивает разрешение, MCP-правка спрашивает");
        assertTrue(uses.stream().anyMatch(use -> use.id().equals("too_early") && use.status() == ToolUse.Status.FAILED));
        String initial = server.requests().get(0).body();
        assertTrue(initial.contains("discover_mcp_tools"));
        assertTrue(initial.contains("read_module"), "встроенный набор остаётся доступным");
        assertFalse(initial.contains("remote_schema_marker"), "начальный запрос не содержит полную схему MCP");
        assertFalse(server.requests().get(1).body().contains("remote_schema_marker"), "list не активирует схему");
        assertTrue(server.requests().get(2).body().contains("remote_schema_marker"), "select действует со следующего запроса");

        assertEquals("Готово.", run(protocol, problems -> List.of(READ, remote), approver));
        assertFalse(server.requests().get(4).body().contains("remote_schema_marker"), "новый ответ не наследует активный набор");
        assertFalse(READ.mcp(), "встроенные инструменты по умолчанию не MCP");
    }

    private static String discoveryCall(Protocol protocol, int index, String id, String name, Map<String, Object> input) {
        if (protocol == Protocol.ANTHROPIC) {
            return "event: content_block_start\ndata: " + Json.write(Json.object("type", "content_block_start", "index", index,
                    "content_block", Json.object("type", "tool_use", "id", id, "name", name, "input", Map.of()))) + "\n\n"
                    + "event: content_block_delta\ndata: " + Json.write(Json.object("type", "content_block_delta", "index", index,
                            "delta", Json.object("type", "input_json_delta", "partial_json", Json.write(input)))) + "\n\n";
        }
        return toolCalls(Json.write(Json.object("index", index, "id", id, "type", "function", "function",
                Json.object("name", name, "arguments", Json.write(input)))));
    }

    @Test
    void repeatedCatalogSelectionsReplaceSchemasWithoutLedgerRejection() throws Exception {
        Tool first = catalogTool("remote_a", "schema_a", 0);
        Tool second = catalogTool("remote_b", "schema_b", 0);
        AtomicInteger step = new AtomicInteger();
        server = FakeModelServer.http().handler((request, response) -> {
            int index = step.getAndIncrement();
            if (index < 3) {
                response.sse(List.of(discoveryCall(Protocol.OPENAI_COMPATIBLE, 0, "select_" + index,
                        "discover_mcp_tools", Json.object("action", "select", "names",
                                List.of(index == 1 ? second.name() : first.name()))), "data: [DONE]\n\n"), 0);
            } else {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"Готово.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
            }
        });

        assertEquals("Готово.", run(Protocol.OPENAI_COMPATIBLE, problems -> List.of(READ, first, second), ToolApprover.ALLOW_ALL));
        List<String> bodies = server.requests().stream().map(FakeModelServer.Request::body).toList();
        assertFalse(bodies.get(0).contains("schema_a"));
        assertTrue(bodies.get(1).contains("schema_a"));
        assertFalse(bodies.get(1).contains("schema_b"));
        assertFalse(bodies.get(2).contains("schema_a"));
        assertTrue(bodies.get(2).contains("schema_b"));
        assertTrue(bodies.get(3).contains("schema_a"));
        assertFalse(bodies.get(3).contains("schema_b"));
        assertEquals(3, uses.stream().filter(use -> use.status() == ToolUse.Status.DONE).count());
        assertTrue(uses.stream().noneMatch(use -> use.problem() == ToolUse.Problem.REPEATED));
    }

    @Test
    void oversizedMcpSelectionIsRejectedBeforeRequestAndKeepsPreviousSet() throws Exception {
        Tool small = catalogTool("remote_small", "schema_small", 0);
        Tool huge = catalogTool("remote_huge", "schema_huge", 80_000);
        AtomicInteger step = new AtomicInteger();
        server = FakeModelServer.http().handler((request, response) -> {
            int index = step.getAndIncrement();
            if (index < 2) {
                response.sse(List.of(discoveryCall(Protocol.OPENAI_COMPATIBLE, 0, "select_" + index,
                        "discover_mcp_tools", Json.object("action", "select", "names",
                                List.of(index == 0 ? small.name() : huge.name()))), "data: [DONE]\n\n"), 0);
            } else {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"Готово.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
            }
        });
        ModelConnection connection = new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", "", 0, "",
                new TrustSettings(false, Optional.empty()), 12_000);
        ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient(), problems -> List.of(small, huge),
                ToolApprover.ALLOW_ALL);

        assertEquals("Готово.", provider.reply(HISTORY, parts::add, uses::add).get(15, TimeUnit.SECONDS));
        assertEquals(3, server.requests().size(), "неподходящая схема не обрывает ответ до следующего запроса");
        assertTrue(server.requests().stream().noneMatch(request -> request.body().contains("schema_huge")));
        assertTrue(server.requests().get(2).body().contains("schema_small"), "неудачный select атомарен");
        assertTrue(uses.stream().anyMatch(use -> use.id().equals("select_1") && use.problem() == ToolUse.Problem.BAD_ARGUMENTS));
    }

    private static Tool catalogTool(String name, String schemaMarker, int padding) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Тестовый MCP"; }
            @Override public boolean mcp() { return true; }
            @Override public Map<String, Object> inputSchema() {
                return Json.object("type", "object", "properties", Json.object(schemaMarker,
                        Json.object("type", "string", "description", "x".repeat(padding))));
            }
            @Override public String summary(Map<String, Object> input) { return name; }
            @Override public Result call(Map<String, Object> input) { throw new AssertionError("Схему только выбирают"); }
        };
    }

    @Test
    void selectedMcpSchemaRejectionFallsBackWithoutRestoringCatalog() throws Exception {
        Tool remote = catalogTool("remote_test", "remote_schema_marker", 0);
        AtomicInteger step = new AtomicInteger();
        server = FakeModelServer.http().handler((request, response) -> {
            int current = step.getAndIncrement();
            if (current == 0) {
                response.sse(List.of(discoveryCall(Protocol.OPENAI_COMPATIBLE, 0, "select", "discover_mcp_tools",
                        Json.object("action", "select", "names", List.of(remote.name()))), "data: [DONE]\n\n"), 0);
            } else if (current == 1) {
                response.send(400, "application/json", Json.write(Json.object("error", Json.object("message",
                        "tool choice requires --enable-auto-tool-choice and --tool-call-parser to be set"))));
            } else {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"Без инструментов.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
            }
        });

        assertEquals("Без инструментов.", run(Protocol.OPENAI_COMPATIBLE, problems -> List.of(READ, remote), ToolApprover.ALLOW_ALL));
        assertEquals(3, server.requests().size());
        assertTrue(server.requests().get(1).body().contains("remote_schema_marker"));
        assertNull(Json.parseObject(server.requests().get(2).body()).get("tools"), "повтор без инструментов и каталога");
        assertTrue(uses.stream().anyMatch(use -> use.outcome().startsWith(AgentLoop.TOOLS_UNSUPPORTED)));
    }

    @Test
    void pendingAssistantTranscriptCannotPushSelectedSchemasOverBudget() throws Exception {
        Tool small = catalogTool("remote_small", "schema_small", 0);
        Tool large = catalogTool("remote_large", "schema_large", 18_000);
        AtomicInteger step = new AtomicInteger();
        server = FakeModelServer.http().handler((request, response) -> {
            int index = step.getAndIncrement();
            if (index < 2) {
                List<String> events = new java.util.ArrayList<>();
                if (index == 1) {
                    events.add("data: " + Json.write(Json.object("choices", List.of(Json.object("delta",
                            Json.object("content", "```text\n" + "x".repeat(13_000) + "\n```"))))) + "\n\n");
                }
                events.add(discoveryCall(Protocol.OPENAI_COMPATIBLE, 0, "select_" + index, "discover_mcp_tools",
                        Json.object("action", "select", "names", List.of(index == 0 ? small.name() : large.name()))));
                events.add("data: [DONE]\n\n");
                response.sse(events, 0);
            } else {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"content\":\"Готово.\"}}]}\n\n", "data: [DONE]\n\n"), 0);
            }
        });
        ModelConnection connection = new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", "", 0, "",
                new TrustSettings(false, Optional.empty()), 12_000);
        ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient(), problems -> List.of(small, large),
                ToolApprover.ALLOW_ALL);

        assertTrue(provider.reply(HISTORY, parts::add, uses::add).get(15, TimeUnit.SECONDS).endsWith("Готово."));
        assertEquals(3, server.requests().size());
        assertTrue(server.requests().get(2).body().contains("schema_small"), "прежний выбор сохранён");
        assertFalse(server.requests().get(2).body().contains("schema_large"));
        assertTrue(uses.stream().anyMatch(use -> use.id().equals("select_1")
                && use.problem() == ToolUse.Problem.BAD_ARGUMENTS && use.outcome().contains("результатами пакета")),
                "отказ вызван проверкой полного пакета, а не размером схемы отдельно");
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
