package io.github.hansglorias.edtai.core.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Что накоплено из потока ответа: текст и вызовы инструментов. Аргументы
 * вызова приходят кусками JSON, их склеиваем по номеру вызова.
 */
final class StreamState {

    private final StringBuilder text = new StringBuilder();
    private final Map<Integer, Builder> calls = new TreeMap<>();
    private final Consumer<String> onText;

    StreamState(Consumer<String> onText) {
        this.onText = onText;
    }

    void text(String part) {
        if (part != null && !part.isEmpty()) {
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
        List<ToolCall> result = new ArrayList<>();
        for (Map.Entry<Integer, Builder> entry : calls.entrySet()) {
            Builder call = entry.getValue();
            if (call.name != null) {
                result.add(new ToolCall(call.id != null ? call.id : "call_" + entry.getKey(), call.name, call.arguments.toString()));
            }
        }
        return new ModelReply(text.toString(), result);
    }

    private static final class Builder {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
