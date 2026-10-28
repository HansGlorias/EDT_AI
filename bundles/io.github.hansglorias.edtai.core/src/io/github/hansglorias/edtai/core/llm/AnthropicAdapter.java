package io.github.hansglorias.edtai.core.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.json.JsonException;
import io.github.hansglorias.edtai.core.tools.Tool;

/** Anthropic Messages API {@code /v1/messages}. */
final class AnthropicAdapter extends ProtocolAdapter {

    static final String API_VERSION = "2023-06-01";

    @Override
    Map<String, String> headers(ModelConnection connection) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!connection.token().isEmpty()) {
            headers.put("x-api-key", connection.token());
        }
        headers.put("anthropic-version", API_VERSION);
        return headers;
    }

    @Override
    Map<String, Object> body(ModelConnection connection, List<ChatMessage> history, List<Object> extra, List<Tool> tools) {
        int maxTokens = connection.maxTokens() > 0 ? connection.maxTokens() : ModelConnection.ANTHROPIC_DEFAULT_MAX_TOKENS;
        Map<String, Object> body = mutable("model", connection.model(), "max_tokens", maxTokens);
        if (!connection.systemPrompt().isEmpty()) {
            body.put("system", connection.systemPrompt());
        }
        List<Object> messages = new ArrayList<>(messages(history));
        messages.addAll(extra);
        body.put("messages", messages);
        if (!tools.isEmpty()) {
            List<Object> definitions = new ArrayList<>();
            for (Tool tool : tools) {
                definitions.add(Json.object("name", tool.name(), "description", tool.description(), "input_schema", tool.inputSchema()));
            }
            body.put("tools", definitions);
        }
        body.put("stream", true);
        return body;
    }

    @Override
    boolean onEvent(SseEvent event, StreamState state) throws ModelException {
        Object json = Json.parse(event.data());
        String type = Json.stringAt(json, "type");
        if (type == null) {
            type = event.event();
        }
        int index = Json.at(json, "index") instanceof Number number ? number.intValue() : 0;
        switch (type) {
            case "content_block_start" -> {
                Object block = Json.at(json, "content_block");
                if ("tool_use".equals(Json.stringAt(block, "type"))) {
                    state.call(index, Json.stringAt(block, "id"), Json.stringAt(block, "name"), null);
                } else {
                    state.text(Json.stringAt(block, "text"));
                }
                return false;
            }
            case "content_block_delta" -> {
                String deltaType = Json.stringAt(json, "delta", "type");
                if ("text_delta".equals(deltaType)) {
                    state.text(Json.stringAt(json, "delta", "text"));
                } else if ("input_json_delta".equals(deltaType)) {
                    state.call(index, null, null, Json.stringAt(json, "delta", "partial_json"));
                }
                return false;
            }
            case "message_stop" -> {
                return true;
            }
            case "error" -> {
                String error = errorText(json);
                throw new ModelException("Сервер прервал ответ с ошибкой: " + (error != null ? error : event.data()));
            }
            default -> {
                // message_start, content_block_stop, message_delta, ping
                return false;
            }
        }
    }

    @Override
    ModelReply fullResponse(Object json) throws ModelException {
        if (Json.at(json, "content") == null) {
            String error = errorText(json);
            throw new ModelException(error != null
                    ? "Сервер вернул ошибку: " + error
                    : "В ответе сервера нет текста (content). Проверьте протокол в настройках.");
        }
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (Object block : Json.asList(Json.at(json, "content"))) {
            String type = Json.stringAt(block, "type");
            if ("text".equals(type) && Json.stringAt(block, "text") != null) {
                text.append(Json.stringAt(block, "text"));
            } else if ("tool_use".equals(type)) {
                Object input = Json.at(block, "input");
                calls.add(new ToolCall(Json.stringAt(block, "id"), Json.stringAt(block, "name"), input == null ? "{}" : Json.write(input)));
            }
        }
        return new ModelReply(text.toString(), calls);
    }

    @Override
    Object assistantMessage(ModelReply reply) {
        List<Object> content = new ArrayList<>();
        if (!reply.text().isEmpty()) {
            content.add(Json.object("type", "text", "text", reply.text()));
        }
        for (ToolCall call : reply.calls()) {
            Object input;
            try {
                input = call.input();
            } catch (JsonException e) {
                // Модель прислала не JSON: возвращаем пустой объект, ошибка уйдёт в результате вызова.
                input = Json.object();
            }
            content.add(Json.object("type", "tool_use", "id", call.id(), "name", call.name(), "input", input));
        }
        return Json.object("role", "assistant", "content", content);
    }

    @Override
    List<Object> toolResults(List<ToolResult> results) {
        List<Object> content = new ArrayList<>();
        for (ToolResult result : results) {
            Map<String, Object> block = mutable("type", "tool_result", "tool_use_id", result.call().id(), "content", result.text());
            if (result.error()) {
                block.put("is_error", true);
            }
            content.add(block);
        }
        return List.of(Json.object("role", "user", "content", content));
    }
}
