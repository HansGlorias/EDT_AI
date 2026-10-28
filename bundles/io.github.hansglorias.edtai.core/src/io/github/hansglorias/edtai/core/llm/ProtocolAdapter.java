package io.github.hansglorias.edtai.core.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.Protocol;

/**
 * Отличия протоколов: заголовки, тело запроса, разбор потока и ответа без
 * стриминга.
 */
abstract class ProtocolAdapter {

    static ProtocolAdapter of(Protocol protocol) {
        return protocol == Protocol.ANTHROPIC ? new AnthropicAdapter() : new OpenAiAdapter();
    }

    /** Заголовки авторизации и версии API. */
    abstract Map<String, String> headers(ModelConnection connection);

    /** Тело запроса со стримингом. */
    abstract Map<String, Object> body(ModelConnection connection, List<ChatMessage> history);

    /**
     * Обрабатывает событие потока: части текста передаёт в {@code onText}.
     *
     * @return {@code true}, если ответ завершён
     * @throws ModelException если сервер прислал ошибку в потоке
     */
    abstract boolean onEvent(SseEvent event, Consumer<String> onText) throws ModelException;

    /**
     * Ответ целиком, если сервер не стал стримить.
     *
     * @throws ModelException если в ответе ошибка или нет текста
     */
    abstract String fullResponse(Object json) throws ModelException;

    /**
     * Сообщения в формате {@code [{role, content}]}. Ошибки и пустые
     * сообщения не передаются, подряд идущие сообщения одной роли
     * склеиваются: не все серверы принимают их по отдельности.
     */
    static List<Object> messages(List<ChatMessage> history) {
        List<Object> result = new ArrayList<>();
        String role = null;
        StringBuilder content = new StringBuilder();
        for (ChatMessage message : history) {
            if (message.role() == ChatRole.ERROR || message.text().isEmpty()) {
                continue;
            }
            String next = message.role() == ChatRole.USER ? "user" : "assistant";
            if (next.equals(role)) {
                content.append("\n\n").append(message.text());
                continue;
            }
            if (role != null) {
                result.add(Json.object("role", role, "content", content.toString()));
            }
            role = next;
            content.setLength(0);
            content.append(message.text());
        }
        if (role != null) {
            result.add(Json.object("role", role, "content", content.toString()));
        }
        return result;
    }

    /** Текст ошибки из JSON-ответа сервера: разные серверы кладут его в разные поля. */
    static String errorText(Object json) {
        Object error = Json.at(json, "error");
        String text = Json.stringAt(error, "message");
        if (text == null) {
            text = Json.asString(error);
        }
        if (text == null) {
            text = Json.stringAt(json, "message");
        }
        if (text == null) {
            text = Json.stringAt(json, "detail");
        }
        if (text == null) {
            text = Json.stringAt(json, "detail", 0, "msg");
        }
        return text;
    }

    static Map<String, Object> mutable(Object... keysAndValues) {
        return new LinkedHashMap<>(Json.object(keysAndValues));
    }
}
