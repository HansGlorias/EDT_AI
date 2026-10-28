package io.github.hansglorias.edtai.core.settings;

import java.util.Objects;

/**
 * Описание MCP-сервера из настроек.
 *
 * @param name      имя для показа и ссылок
 * @param transport способ подключения
 * @param target    командная строка для {@link McpTransport#STDIO} или URL для {@link McpTransport#HTTP}
 * @param enabled   использовать ли сервер
 */
public record McpServer(String name, McpTransport transport, String target, boolean enabled) {

    public McpServer {
        name = Objects.requireNonNullElse(name, "").strip();
        Objects.requireNonNull(transport, "transport");
        target = Objects.requireNonNullElse(target, "").strip();
    }

    public McpServer withEnabled(boolean value) {
        return new McpServer(name, transport, target, value);
    }
}
