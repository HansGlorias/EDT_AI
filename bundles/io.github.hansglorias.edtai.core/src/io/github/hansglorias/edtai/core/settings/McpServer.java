package io.github.hansglorias.edtai.core.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Описание MCP-сервера из настроек.
 *
 * @param name      имя для показа и ссылок
 * @param transport способ подключения
 * @param target    командная строка для {@link McpTransport#STDIO} или URL для {@link McpTransport#HTTP}
 * @param enabled   использовать ли сервер
 * @param tools     какие инструменты сервера давать модели: имена через запятую или пробел, {@code *} в конце —
 *                  по началу имени; пусто — все
 */
public record McpServer(String name, McpTransport transport, String target, boolean enabled, String tools) {

    public McpServer {
        name = Objects.requireNonNullElse(name, "").strip();
        Objects.requireNonNull(transport, "transport");
        target = Objects.requireNonNullElse(target, "").strip();
        tools = String.join(", ", patterns(Objects.requireNonNullElse(tools, "")));
    }

    public McpServer(String name, McpTransport transport, String target, boolean enabled) {
        this(name, transport, target, enabled, "");
    }

    public McpServer withEnabled(boolean value) {
        return new McpServer(name, transport, target, value, tools);
    }

    /** Давать ли модели инструмент с этим именем. */
    public boolean allowsTool(String tool) {
        List<String> patterns = patterns(tools);
        if (patterns.isEmpty()) {
            return true;
        }
        String name = tool.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            String value = pattern.toLowerCase(Locale.ROOT);
            if (value.endsWith("*") ? name.startsWith(value.substring(0, value.length() - 1)) : name.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> patterns(String value) {
        List<String> patterns = new ArrayList<>();
        for (String part : value.split("[,;\\s]+")) {
            if (!part.isEmpty()) {
                patterns.add(part);
            }
        }
        return patterns;
    }
}
