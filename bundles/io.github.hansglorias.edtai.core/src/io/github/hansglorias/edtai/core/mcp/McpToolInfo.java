package io.github.hansglorias.edtai.core.mcp;

import java.util.Map;
import java.util.Objects;

/** Инструмент MCP-сервера из {@code tools/list}. */
public record McpToolInfo(String name, String description, Map<String, Object> inputSchema) {

    public McpToolInfo {
        Objects.requireNonNull(name, "name");
        description = Objects.requireNonNullElse(description, "");
        inputSchema = Objects.requireNonNullElse(inputSchema, Map.of("type", "object"));
    }
}
