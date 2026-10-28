package io.github.hansglorias.edtai.core.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tools.Tool;

/**
 * Отличия протоколов: заголовки, тело запроса, разбор потока и ответа без
 * стриминга, вызовы инструментов и их результаты.
 */
abstract class ProtocolAdapter {

    static ProtocolAdapter of(Protocol protocol) {
        return protocol == Protocol.ANTHROPIC ? new AnthropicAdapter() : new OpenAiAdapter();
    }

    /** Заголовки авторизации и версии API. */
    abstract Map<String, String> headers(ModelConnection connection);

    /**
     * Тело запроса со стримингом.
     *
     * @param extra сообщения после истории в формате протокола: вызовы инструментов и их результаты
     * @param tools инструменты, которые можно вызвать; пустой список — без инструментов
     */
    abstract Map<String, Object> body(ModelConnection connection, List<ChatMessage> history, List<Object> extra, List<Tool> tools);

    /**
     * Обрабатывает событие потока: текст и вызовы инструментов копит в {@code state}.
     *
     * @return {@code true}, если ответ завершён
     * @throws ModelException если сервер прислал ошибку в потоке
     */
    abstract boolean onEvent(SseEvent event, StreamState state) throws ModelException;

    /**
     * Ответ целиком, если сервер не стал стримить.
     *
     * @throws ModelException если в ответе ошибка или нет ни текста, ни вызовов
     */
    abstract ModelReply fullResponse(Object json) throws ModelException;

    /** Ответ модели с вызовами инструментов — чтобы вернуть его в следующем запросе. */
    abstract Object assistantMessage(ModelReply reply);

    /** Результаты вызовов для следующего запроса, в порядке вызовов. */
    abstract List<Object> toolResults(List<ToolResult> results);

    /**
     * Результат вызова инструмента.
     *
     * @param error {@code true} — текст описывает ошибку
     */
    record ToolResult(ToolCall call, String text, boolean error) {
    }

    /**
     * Сообщения в формате {@code [{role, content}]}; у сообщений
     * пользователя фрагменты кода идут перед текстом. Ошибки и пустые
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
                content.append("\n\n").append(message.modelText());
                continue;
            }
            if (role != null) {
                result.add(Json.object("role", role, "content", content.toString()));
            }
            role = next;
            content.setLength(0);
            content.append(message.modelText());
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
