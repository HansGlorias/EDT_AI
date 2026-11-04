package io.github.hansglorias.edtai.core.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.TokenUsage;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.tools.Tool;

/** OpenAI-совместимый {@code /v1/chat/completions}: OpenAI, vLLM, Ollama, LM Studio, шлюзы. */
final class OpenAiAdapter extends ProtocolAdapter {

    @Override
    Map<String, String> headers(ModelConnection connection) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!connection.token().isEmpty()) {
            headers.put("Authorization", "Bearer " + connection.token());
        }
        return headers;
    }

    @Override
    Map<String, Object> body(ModelConnection connection, List<ChatMessage> history, List<Object> extra, List<Tool> tools) {
        List<Object> messages = new ArrayList<>();
        if (!connection.systemPrompt().isEmpty()) {
            messages.add(Json.object("role", "system", "content", connection.systemPrompt()));
        }
        messages.addAll(messages(history));
        messages.addAll(extra);
        Map<String, Object> body = mutable("model", connection.model(), "messages", messages, "stream", true);
        // Без явного ограничения не передаём: часть моделей OpenAI не принимает max_tokens.
        OutputPolicy.maxTokens(connection).ifPresent(value -> body.put("max_tokens", value));
        if (!tools.isEmpty()) {
            List<Object> definitions = new ArrayList<>();
            for (Tool tool : tools) {
                definitions.add(Json.object("type", "function", "function",
                        Json.object("name", tool.name(), "description", tool.description(), "parameters", tool.inputSchema())));
            }
            body.put("tools", definitions);
        }
        return body;
    }

    @Override
    boolean onEvent(SseEvent event, StreamState state) throws ModelException {
        String data = event.data().strip();
        if (data.equals("[DONE]")) {
            return true;
        }
        Object json = Json.parse(data);
        if (Json.at(json, "error") != null) {
            String error = errorText(json);
            throw new ModelException("Сервер прервал ответ с ошибкой: " + (error != null ? error : data));
        }
        if ("length".equals(Json.stringAt(json, "choices", 0, "finish_reason"))) {
            state.truncated();
        }
        // usage приходит последней частью, если сервер его шлёт в потоке (vLLM, LM Studio, шлюзы); не шлёт — оценим сами.
        Object usage = Json.at(json, "usage");
        state.usage(tokens(usage, new Object[] { "prompt_tokens" }), tokens(usage, new Object[] { "completion_tokens" }));
        Object delta = Json.at(json, "choices", 0, "delta");
        reasoning(delta, state::reasoning);
        state.text(Json.stringAt(delta, "content"));
        List<Object> calls = Json.asList(Json.at(delta, "tool_calls"));
        for (int i = 0; i < calls.size(); i++) {
            Object call = calls.get(i);
            // Ollama и часть шлюзов не присылают index: тогда номер — позиция в массиве.
            int index = Json.at(call, "index") instanceof Number number ? number.intValue() : i;
            state.call(index, Json.stringAt(call, "id"), Json.stringAt(call, "function", "name"), arguments(call));
        }
        return false;
    }

    @Override
    ModelReply fullResponse(Object json) throws ModelException {
        Object message = Json.at(json, "choices", 0, "message");
        String text = Json.stringAt(message, "content");
        List<Object> calls = Json.asList(Json.at(message, "tool_calls"));
        if (text == null && calls.isEmpty()) {
            String error = errorText(json);
            throw new ModelException(error != null
                    ? "Сервер вернул ошибку: " + error
                    : "В ответе сервера нет текста (choices[0].message.content). Проверьте протокол в настройках.");
        }
        List<ToolCall> result = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            Object call = calls.get(i);
            String id = Json.stringAt(call, "id");
            result.add(new ToolCall(id != null ? id : "call_" + i, Json.stringAt(call, "function", "name"), arguments(call)));
        }
        Object usage = Json.at(json, "usage");
        long input = tokens(usage, new Object[] { "prompt_tokens" });
        long output = tokens(usage, new Object[] { "completion_tokens" });
        String[] thought = { "", "" };
        reasoning(message, (field, part) -> {
            thought[0] = field;
            thought[1] = part;
        });
        if (thought[0].isEmpty() && text != null) {
            ThinkFilter tags = new ThinkFilter();
            tags.feed(text);
            tags.finish();
            thought[0] = ModelReply.Reasoning.TAGS;
            thought[1] = tags.thought();
        }
        return new ModelReply(ThinkFilter.strip(text != null ? text : ""), result,
                "length".equals(Json.stringAt(json, "choices", 0, "finish_reason")),
                input < 0 && output < 0 ? null : TokenUsage.serverStep(input, output),
                new ModelReply.Reasoning(thought[0], thought[1]));
    }

    @Override
    Object assistantMessage(ModelReply reply) {
        List<Object> calls = new ArrayList<>();
        for (ToolCall call : reply.calls()) {
            calls.add(Json.object("id", call.id(), "type", "function", "function",
                    Json.object("name", call.name(), "arguments", call.arguments())));
        }
        Map<String, Object> message = mutable("role", "assistant");
        ModelReply.Reasoning reasoning = reply.reasoning();
        String content = reply.text();
        if (!reasoning.isEmpty()) {
            // DeepSeek и Kimi в режиме рассуждений отвечают 400, если в шаге с вызовами инструментов
            // нет reasoning_content; MiniMax и Qwen хуже продолжают без своего <think>. Возвращаем
            // рассуждение тем же способом, каким его прислал сервер, — значит, сервер его понимает.
            if (ModelReply.Reasoning.TAGS.equals(reasoning.field())) {
                content = ThinkFilter.OPEN + reasoning.text() + ThinkFilter.CLOSE + (content.isEmpty() ? "" : "\n\n" + content);
            } else {
                message.put(reasoning.field(), reasoning.text());
            }
        }
        if (!content.isEmpty()) {
            message.put("content", content);
        }
        message.put("tool_calls", calls);
        return message;
    }

    /** Рассуждение в отдельном поле: {@code reasoning_content} (DeepSeek, Kimi, Qwen) или {@code reasoning} (vLLM, OpenRouter). */
    private static void reasoning(Object delta, BiConsumer<String, String> sink) {
        String content = Json.stringAt(delta, ModelReply.Reasoning.CONTENT_FIELD);
        if (content != null && !content.isEmpty()) {
            sink.accept(ModelReply.Reasoning.CONTENT_FIELD, content);
            return;
        }
        String reasoning = Json.stringAt(delta, ModelReply.Reasoning.FIELD);
        if (reasoning != null && !reasoning.isEmpty()) {
            sink.accept(ModelReply.Reasoning.FIELD, reasoning);
        }
    }

    @Override
    List<Object> toolResults(List<ToolResult> results) {
        List<Object> messages = new ArrayList<>();
        for (ToolResult result : results) {
            messages.add(Json.object("role", "tool", "tool_call_id", result.call().id(), "content",
                    result.error() ? "Ошибка: " + result.text() : result.text()));
        }
        return messages;
    }

    @Override
    List<Object> textExchange(String assistant, String user) {
        return List.of(Json.object("role", "assistant", "content", assistant), Json.object("role", "user", "content", user));
    }

    /** Аргументы — строка JSON; некоторые серверы присылают сразу объект. */
    private static String arguments(Object call) {
        Object arguments = Json.at(call, "function", "arguments");
        return arguments == null ? null : arguments instanceof String text ? text : Json.write(arguments);
    }

    @Override
    Object image(Attachment image) {
        return Json.object("type", "image_url", "image_url", Json.object("url", "data:" + image.mediaType() + ";base64,"
                + image.data()));
    }
}
