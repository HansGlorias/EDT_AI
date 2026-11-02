package io.github.hansglorias.edtai.core.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.OptionalInt;

/**
 * Описание MCP-сервера из настроек.
 *
 * @param name      имя для показа и ссылок
 * @param transport способ подключения
 * @param target    командная строка для {@link McpTransport#STDIO} или URL для {@link McpTransport#HTTP}
 * @param enabled   использовать ли сервер
 * @param tools     какие инструменты сервера давать модели: имена через запятую или пробел, {@code *} в конце —
 *                  по началу имени; пусто, {@code *}, «все» или «all» — все
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

    /** Простой локальный адрес, который можно безопасно редактировать одним портом. */
    public static OptionalInt localPort(String target) {
        try {
            URI uri = new URI(target);
            String host = Objects.requireNonNullElse(uri.getHost(), "").toLowerCase(Locale.ROOT);
            boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
            if (local && "http".equalsIgnoreCase(uri.getScheme()) && "/mcp".equals(uri.getPath())
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getPort() > 0 && uri.getPort() <= 65535) {
                return OptionalInt.of(uri.getPort());
            }
        } catch (URISyntaxException e) {
            // Полный URL остаётся доступен в обычном режиме.
        }
        return OptionalInt.empty();
    }

    public static String localTarget(String port) {
        int value;
        try {
            value = Integer.parseInt(port.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Порт должен быть числом от 1 до 65535");
        }
        if (value < 1 || value > 65535) throw new IllegalArgumentException("Порт должен быть числом от 1 до 65535");
        return "http://localhost:" + value + "/mcp";
    }

    /** Имя сервера или понятное запасное имя, с учётом уже занятых имён. */
    public static String suggestedName(String reported, String target, List<String> taken) {
        String base = Objects.requireNonNullElse(reported, "").strip();
        if (base.isEmpty()) {
            try {
                URI uri = new URI(target);
                base = uri.getHost() == null ? "MCP" : "MCP " + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
            } catch (URISyntaxException e) {
                base = "MCP";
            }
        }
        String candidate = base;
        int suffix = 2;
        while (containsName(taken, candidate)) candidate = base + " (" + suffix++ + ")";
        return candidate;
    }

    private static boolean containsName(List<String> names, String name) {
        return names.stream().anyMatch(value -> value.equalsIgnoreCase(name));
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
            // Таблица настроек пишет «все» у пустого фильтра — это слово и вводят.
            if (part.equals("*") || part.equalsIgnoreCase("все") || part.equalsIgnoreCase("all")) {
                return List.of();
            }
            if (!part.isEmpty()) {
                patterns.add(part);
            }
        }
        return patterns;
    }
}
