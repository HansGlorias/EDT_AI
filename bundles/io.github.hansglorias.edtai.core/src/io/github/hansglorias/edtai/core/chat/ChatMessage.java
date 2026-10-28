package io.github.hansglorias.edtai.core.chat;

import java.time.Instant;
import java.util.Objects;

public record ChatMessage(ChatRole role, String text, Instant time) {

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(time, "time");
    }

    public static ChatMessage of(ChatRole role, String text) {
        return new ChatMessage(role, text, Instant.now());
    }
}
