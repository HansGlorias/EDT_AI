package io.github.hansglorias.edtai.core.mcp;

/** Ошибка обращения к MCP-серверу. Сообщение — для пользователя и модели, по-русски. */
public class McpException extends Exception {

    private static final long serialVersionUID = 1L;
    private final Integer rpcCode;

    public McpException(String message) {
        this(message, null, null);
    }

    public McpException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public McpException(String message, Throwable cause, Integer rpcCode) {
        super(message, cause);
        this.rpcCode = rpcCode;
    }

    public boolean methodNotFound() { return Integer.valueOf(-32601).equals(rpcCode); }
    public boolean remoteError() { return rpcCode != null; }
}
