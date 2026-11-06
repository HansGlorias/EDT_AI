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
    private final RepetitionGuard repetitions = new RepetitionGuard();
    private final Map<Integer, Builder> calls = new TreeMap<>();
    private final Consumer<String> onText;
    private final Runnable onReasoning;
    private final ThinkFilter thinking = new ThinkFilter();
    private final StringBuilder reasoning = new StringBuilder();
    private String reasoningField = "";
    private boolean truncated;
    private long inputTokens = -1;
    private long outputTokens = -1;

    StreamState(Consumer<String> onText) {
        this(onText, () -> { });
    }

    /** @param onReasoning пришла часть рассуждения: в отдельном поле или внутри {@code <think>} */
    StreamState(Consumer<String> onText, Runnable onReasoning) {
        this.onText = onText;
        this.onReasoning = onReasoning;
    }

    /** Часть текста; рассуждения {@code <think>…</think>} не показываются и не сохраняются. */
    void text(String part) {
        if (part != null && !part.isEmpty()) {
            int thought = thinking.thoughtLength();
            String shown = thinking.feed(part);
            if (thinking.inside() || thinking.thoughtLength() > thought) {
                onReasoning.run();
            }
            visible(shown);
        }
    }

    /**
     * Часть рассуждения из отдельного поля дельты ({@code reasoning_content} или {@code reasoning}).
     * Не показывается; запоминается поле, в котором сервер прислал рассуждение первым.
     */
    void reasoning(String field, String part) {
        if (part != null && !part.isEmpty()) {
            if (reasoningField.isEmpty()) {
                reasoningField = field;
            }
            if (reasoningField.equals(field)) {
                reasoning.append(part);
            }
            onReasoning.run();
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
            int repeated = repetitions.append(part);
            if (repeated >= 0) {
                throw new GenerationStoppedException(ThinkFilter.strip(text.substring(0, repeated)).stripTrailing());
            }
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
                : TokenUsage.serverStep(inputTokens, outputTokens);
        ModelReply.Reasoning thought = !reasoningField.isEmpty()
                ? new ModelReply.Reasoning(reasoningField, reasoning.toString())
                : new ModelReply.Reasoning(ModelReply.Reasoning.TAGS, thinking.thought());
        return new ModelReply(ThinkFilter.strip(text.toString()), result, truncated, usage, thought);
    }

    private static final class Builder {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
