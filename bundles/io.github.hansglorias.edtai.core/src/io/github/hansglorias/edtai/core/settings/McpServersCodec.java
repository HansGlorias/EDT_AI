package io.github.hansglorias.edtai.core.settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Сериализация списка MCP-серверов в одну строку preferences.
 *
 * <p>Одна строка на сервер, поля через табуляцию:
 * {@code enabled<TAB>transport<TAB>name<TAB>target<TAB>tools}; записи без
 * {@code tools} (до этапа 6) читаются. Обратная косая черта,
 * табуляция и переводы строк в значениях экранируются.
 */
public final class McpServersCodec {

    private static final char SEPARATOR = '\t';

    private McpServersCodec() {
    }

    public static String encode(List<McpServer> servers) {
        StringBuilder out = new StringBuilder();
        for (McpServer server : servers) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(server.enabled() ? '1' : '0').append(SEPARATOR)
                    .append(server.transport().name()).append(SEPARATOR)
                    .append(escape(server.name())).append(SEPARATOR)
                    .append(escape(server.target())).append(SEPARATOR)
                    .append(escape(server.tools()));
        }
        return out.toString();
    }

    /**
     * @throws IllegalArgumentException если строка повреждена
     */
    public static List<McpServer> decode(String value) {
        List<McpServer> servers = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return servers;
        }
        for (String line : value.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            String[] fields = line.split(String.valueOf(SEPARATOR), -1);
            if (fields.length != 4 && fields.length != 5) {
                throw new IllegalArgumentException("Повреждена запись MCP-сервера: ожидалось 5 полей, получено " + fields.length);
            }
            boolean enabled = switch (fields[0]) {
                case "1" -> true;
                case "0" -> false;
                default -> throw new IllegalArgumentException("Повреждена запись MCP-сервера: флаг «" + fields[0] + "»");
            };
            servers.add(new McpServer(unescape(fields[2]), McpTransport.parse(fields[1]), unescape(fields[3]), enabled,
                    fields.length == 5 ? unescape(fields[4]) : ""));
        }
        return servers;
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i == value.length()) {
                throw new IllegalArgumentException("Повреждена запись MCP-сервера: обрыв экранирования");
            }
            char next = value.charAt(i);
            switch (next) {
                case '\\' -> out.append('\\');
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                default -> throw new IllegalArgumentException("Повреждена запись MCP-сервера: «\\" + next + "»");
            }
        }
        return out.toString();
    }
}
