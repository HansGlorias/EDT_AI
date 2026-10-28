package io.github.hansglorias.edtai.core.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.json.Json;

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
    Map<String, Object> body(ModelConnection connection, List<ChatMessage> history) {
        List<Object> messages = new ArrayList<>();
        if (!connection.systemPrompt().isEmpty()) {
            messages.add(Json.object("role", "system", "content", connection.systemPrompt()));
        }
        messages.addAll(messages(history));
        Map<String, Object> body = mutable("model", connection.model(), "messages", messages, "stream", true);
        // Без явного ограничения не передаём: часть моделей OpenAI не принимает max_tokens.
        if (connection.maxTokens() > 0) {
            body.put("max_tokens", connection.maxTokens());
        }
        return body;
    }

    @Override
    boolean onEvent(SseEvent event, Consumer<String> onText) throws ModelException {
        String data = event.data().strip();
        if (data.equals("[DONE]")) {
            return true;
        }
        Object json = Json.parse(data);
        if (Json.at(json, "error") != null) {
            String error = errorText(json);
            throw new ModelException("Сервер прервал ответ с ошибкой: " + (error != null ? error : data));
        }
        String text = Json.stringAt(json, "choices", 0, "delta", "content");
        if (text != null && !text.isEmpty()) {
            onText.accept(text);
        }
        return false;
    }

    @Override
    String fullResponse(Object json) throws ModelException {
        String text = Json.stringAt(json, "choices", 0, "message", "content");
        if (text != null) {
            return text;
        }
        String error = errorText(json);
        throw new ModelException(error != null
                ? "Сервер вернул ошибку: " + error
                : "В ответе сервера нет текста (choices[0].message.content). Проверьте протокол в настройках.");
    }
}
