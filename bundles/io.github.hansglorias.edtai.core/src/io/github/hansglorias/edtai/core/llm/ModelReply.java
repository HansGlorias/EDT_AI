package io.github.hansglorias.edtai.core.llm;

import java.util.List;

/** Один ответ модели: текст и вызовы инструментов, если модель их запросила. */
public record ModelReply(String text, List<ToolCall> calls, boolean truncated) {

    public ModelReply {
        calls = List.copyOf(calls);
    }

    public ModelReply(String text, List<ToolCall> calls) {
        this(text, calls, false);
    }

    public static ModelReply text(String text) {
        return new ModelReply(text, List.of());
    }
}
