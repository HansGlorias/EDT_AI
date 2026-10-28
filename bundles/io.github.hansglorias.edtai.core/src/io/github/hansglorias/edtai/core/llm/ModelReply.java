package io.github.hansglorias.edtai.core.llm;

import java.util.List;

import io.github.hansglorias.edtai.core.chat.TokenUsage;

/**
 * Один ответ модели: текст и вызовы инструментов, если модель их запросила.
 *
 * @param usage токены по данным сервера; {@code null} — сервер их не прислал
 */
public record ModelReply(String text, List<ToolCall> calls, boolean truncated, TokenUsage usage) {

    public ModelReply {
        calls = List.copyOf(calls);
    }

    public ModelReply(String text, List<ToolCall> calls, boolean truncated) {
        this(text, calls, truncated, null);
    }

    public ModelReply(String text, List<ToolCall> calls) {
        this(text, calls, false);
    }

    public ModelReply withUsage(TokenUsage newUsage) {
        return new ModelReply(text, calls, truncated, newUsage);
    }

    public static ModelReply text(String text) {
        return new ModelReply(text, List.of());
    }
}
