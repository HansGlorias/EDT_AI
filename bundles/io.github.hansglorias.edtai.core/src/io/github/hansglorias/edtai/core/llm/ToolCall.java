package io.github.hansglorias.edtai.core.llm;

import java.util.Map;
import java.util.Objects;

import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.json.JsonException;

/**
 * Вызов инструмента из ответа модели.
 *
 * @param arguments аргументы — JSON-объект текстом, как его прислала модель
 */
public record ToolCall(String id, String name, String arguments) {

    public ToolCall {
        id = Objects.requireNonNullElse(id, "");
        name = Objects.requireNonNullElse(name, "");
        arguments = Objects.requireNonNullElse(arguments, "").isBlank() ? "{}" : arguments;
    }

    /**
     * @throws JsonException если модель прислала не JSON-объект
     */
    public Map<String, Object> input() {
        return Json.parseObject(arguments);
    }
}
