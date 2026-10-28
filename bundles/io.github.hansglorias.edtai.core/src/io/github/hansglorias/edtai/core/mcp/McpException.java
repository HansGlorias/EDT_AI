package io.github.hansglorias.edtai.core.mcp;

/** Ошибка обращения к MCP-серверу. Сообщение — для пользователя и модели, по-русски. */
public class McpException extends Exception {

    private static final long serialVersionUID = 1L;

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
