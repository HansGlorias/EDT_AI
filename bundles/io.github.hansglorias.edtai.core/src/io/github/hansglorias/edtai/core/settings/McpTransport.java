package io.github.hansglorias.edtai.core.settings;

/**
 * Способ подключения к MCP-серверу.
 */
public enum McpTransport {

    /** Локальный процесс, обмен через stdin/stdout. Цель — командная строка. */
    STDIO("stdio"),

    /** Удалённый сервер по HTTP. Цель — URL. */
    HTTP("http");

    private final String label;

    McpTransport(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static McpTransport parse(String value) {
        for (McpTransport transport : values()) {
            if (transport.name().equals(value) || transport.label.equals(value)) {
                return transport;
            }
        }
        throw new IllegalArgumentException("Неизвестный транспорт MCP: " + value);
    }
}
