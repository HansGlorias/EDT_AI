package io.github.hansglorias.edtai.core.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import io.github.hansglorias.edtai.core.chat.TokenUsage;

/**
 * Что накоплено из потока ответа: текст и вызовы инструментов. Аргументы
 * вызова приходят кусками JSON, их склеиваем по номеру вызова.
 */
final class StreamState {

    private final StringBuilder text = new StringBuilder();
    private final Map<Integer, Builder> calls = new TreeMap<>();
    private final Consumer<String> onText;
    private final ThinkFilter thinking = new ThinkFilter();
    private boolean truncated;
    private long inputTokens = -1;
    private long outputTokens = -1;

    StreamState(Consumer<String> onText) {
        this.onText = onText;
    }

    /** Часть текста; рассуждения {@code <think>…</think>} не показываются и не сохраняются. */
    void text(String part) {
        if (part != null && !part.isEmpty()) {
            visible(thinking.feed(part));
        }
    }

    /** Ответ оборвался по лимиту длины (max_tokens). */
    void truncated() {
        truncated = true;
    }

    /** Токены из usage; -1 — в этом событии нет. Приходят частями: вход в начале, выход в конце. */
    void usage(long input, long output) {
        if (input >= 0) {
            inputTokens = input;
        }
        if (output >= 0) {
            outputTokens = output;
        }
    }

    private void visible(String part) {
        if (!part.isEmpty()) {
            text.append(part);
            onText.accept(part);
        }
    }

    /** Начало или продолжение вызова с номером {@code index}; {@code null} — не менять. */
    void call(int index, String id, String name, String arguments) {
        Builder call = calls.computeIfAbsent(index, key -> new Builder());
        if (id != null && !id.isEmpty()) {
            call.id = id;
        }
        if (name != null && !name.isEmpty()) {
            call.name = name;
        }
        if (arguments != null) {
            call.arguments.append(arguments);
        }
    }

    ModelReply reply() {
        visible(thinking.finish());
        List<ToolCall> result = new ArrayList<>();
        for (Map.Entry<Integer, Builder> entry : calls.entrySet()) {
            Builder call = entry.getValue();
            if (call.name != null) {
                result.add(new ToolCall(call.id != null ? call.id : "call_" + entry.getKey(), call.name, call.arguments.toString()));
            }
        }
        TokenUsage usage = inputTokens < 0 && outputTokens < 0 ? null
                : TokenUsage.step(Math.max(0, inputTokens), Math.max(0, outputTokens), false);
        return new ModelReply(ThinkFilter.strip(text.toString()), result, truncated, usage);
    }

    private static final class Builder {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
