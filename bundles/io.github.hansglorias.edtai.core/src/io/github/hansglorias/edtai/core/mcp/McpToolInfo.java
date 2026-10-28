package io.github.hansglorias.edtai.core.mcp;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Инструмент MCP-сервера из {@code tools/list}.
 *
 * @param readOnly подсказка сервера {@code annotations.readOnlyHint}: только читает; null — сервер не сказал
 */
public record McpToolInfo(String name, String description, Map<String, Object> inputSchema, Boolean readOnly) {

    /** Глаголы в начале имени инструментов, которые только читают: {@code get_problems}, {@code listObjects}. */
    private static final Set<String> READING = Set.of("get", "list", "read", "search", "find", "describe", "show", "check",
            "validate", "query", "view", "count", "inspect", "lookup", "fetch", "explain", "go");

    public McpToolInfo {
        Objects.requireNonNull(name, "name");
        description = Objects.requireNonNullElse(description, "");
        inputSchema = Objects.requireNonNullElse(inputSchema, Map.of("type", "object"));
    }

    public McpToolInfo(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, inputSchema, null);
    }

    /**
     * Меняет ли инструмент проект: по подсказке сервера, а без неё — по
     * имени (читающий глагол в начале). Незнакомое имя считается правкой: в
     * режиме «только вопросы» лучше не дать модели лишний инструмент.
     */
    public boolean modifies() {
        if (readOnly != null) {
            return !readOnly;
        }
        String verb = name.split("[_.\\-]|(?<=[a-z])(?=[A-Z])", 2)[0].toLowerCase(Locale.ROOT);
        return !READING.contains(verb);
    }
}
