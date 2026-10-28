package io.github.hansglorias.edtai.core.chat;

import java.time.Instant;
import java.util.Objects;

/**
 * Сообщение диалога.
 *
 * @param status для ответа ассистента: идёт генерация, готов или остановлен
 */
public record ChatMessage(ChatRole role, String text, Instant time, Status status) {

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
    }

    public static ChatMessage of(ChatRole role, String text) {
        return new ChatMessage(role, text, Instant.now(), Status.DONE);
    }

    public ChatMessage with(String newText, Status newStatus) {
        return new ChatMessage(role, newText, time, newStatus);
    }
}
