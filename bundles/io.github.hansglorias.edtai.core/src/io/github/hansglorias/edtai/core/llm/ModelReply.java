package io.github.hansglorias.edtai.core.llm;

import java.util.List;
import java.util.Objects;

import io.github.hansglorias.edtai.core.chat.TokenUsage;

/**
 * Один ответ модели: текст и вызовы инструментов, если модель их запросила.
 *
 * @param usage     токены по данным сервера; {@code null} — сервер их не прислал
 * @param reasoning рассуждение модели в этом ответе; в ленту не попадает, но возвращается
 *                  модели в следующем шаге цикла инструментов
 */
public record ModelReply(String text, List<ToolCall> calls, boolean truncated, TokenUsage usage, Reasoning reasoning) {

    /**
     * Рассуждение и способ, которым его прислал сервер: поле дельты
     * ({@value #CONTENT_FIELD} — DeepSeek, Kimi, Qwen; {@value #FIELD} — vLLM, OpenRouter)
     * или теги {@code <think>} в тексте ({@value #TAGS} — MiniMax, Qwen без reasoning-parser).
     * Отправляется обратно тем же способом.
     */
    public record Reasoning(String field, String text) {

        static final String CONTENT_FIELD = "reasoning_content";
        static final String FIELD = "reasoning";
        static final String TAGS = "<think>";

        public static final Reasoning NONE = new Reasoning("", "");

        public Reasoning {
            field = Objects.requireNonNullElse(field, "");
            text = Objects.requireNonNullElse(text, "");
        }

        public boolean isEmpty() {
            return field.isEmpty() || text.isBlank();
        }

        /** Без текста рассуждения: только для служебного вывода. */
        @Override
        public String toString() {
            return "Reasoning[field=" + field + ", chars=" + text.length() + "]";
        }
    }

    public ModelReply {
        calls = List.copyOf(calls);
        reasoning = Objects.requireNonNullElse(reasoning, Reasoning.NONE);
    }

    public ModelReply(String text, List<ToolCall> calls, boolean truncated, TokenUsage usage) {
        this(text, calls, truncated, usage, Reasoning.NONE);
    }

    public ModelReply(String text, List<ToolCall> calls, boolean truncated) {
        this(text, calls, truncated, null);
    }

    public ModelReply(String text, List<ToolCall> calls) {
        this(text, calls, false);
    }

    public ModelReply withUsage(TokenUsage newUsage) {
        return new ModelReply(text, calls, truncated, newUsage, reasoning);
    }

    public static ModelReply text(String text) {
        return new ModelReply(text, List.of());
    }
}
