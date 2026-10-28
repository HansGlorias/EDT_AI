package io.github.hansglorias.edtai.core.chat;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import io.github.hansglorias.edtai.core.context.ContextItem;
import io.github.hansglorias.edtai.core.context.ContextPrompt;

/**
 * Сообщение диалога.
 *
 * @param status  для ответа ассистента: идёт генерация, готов или остановлен
 * @param context фрагменты кода, прикреплённые к сообщению пользователя
 */
public record ChatMessage(ChatRole role, String text, Instant time, Status status, List<ContextItem> context) {

    public enum Status {
        /** Сообщение целиком. */
        DONE,
        /** Ответ ещё генерируется, текст будет расти. */
        STREAMING,
        /** Генерацию остановили или она оборвалась: текст неполный. */
        STOPPED
    }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(time, "time");
        status = Objects.requireNonNullElse(status, Status.DONE);
        context = List.copyOf(Objects.requireNonNullElse(context, List.of()));
    }

    public ChatMessage(ChatRole role, String text, Instant time, Status status) {
        this(role, text, time, status, List.of());
    }

    public static ChatMessage of(ChatRole role, String text) {
        return new ChatMessage(role, text, Instant.now(), Status.DONE);
    }

    public ChatMessage with(String newText, Status newStatus) {
        return new ChatMessage(role, newText, time, newStatus, context);
    }

    /** Текст для модели: фрагменты кода, затем сам текст. */
    public String modelText() {
        return ContextPrompt.compose(text, context);
    }
}
