package io.github.hansglorias.edtai.core.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.json.Json;

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
    Map<String, Object> body(ModelConnection connection, List<ChatMessage> history) {
        int maxTokens = connection.maxTokens() > 0 ? connection.maxTokens() : ModelConnection.ANTHROPIC_DEFAULT_MAX_TOKENS;
        return mutable("model", connection.model(), "max_tokens", maxTokens, "messages", messages(history), "stream", true);
    }

    @Override
    boolean onEvent(SseEvent event, Consumer<String> onText) throws ModelException {
        Object json = Json.parse(event.data());
        String type = Json.stringAt(json, "type");
        if (type == null) {
            type = event.event();
        }
        switch (type) {
            case "content_block_delta" -> {
                if ("text_delta".equals(Json.stringAt(json, "delta", "type"))) {
                    String text = Json.stringAt(json, "delta", "text");
                    if (text != null && !text.isEmpty()) {
                        onText.accept(text);
                    }
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
                // message_start, content_block_start/stop, message_delta, ping
                return false;
            }
        }
    }

    @Override
    String fullResponse(Object json) throws ModelException {
        List<Object> content = Json.asList(Json.at(json, "content"));
        if (Json.at(json, "content") != null) {
            StringBuilder text = new StringBuilder();
            for (Object block : content) {
                if ("text".equals(Json.stringAt(block, "type")) && Json.stringAt(block, "text") != null) {
                    text.append(Json.stringAt(block, "text"));
                }
            }
            return text.toString();
        }
        String error = errorText(json);
        throw new ModelException(error != null
                ? "Сервер вернул ошибку: " + error
                : "В ответе сервера нет текста (content). Проверьте протокол в настройках.");
    }
}
