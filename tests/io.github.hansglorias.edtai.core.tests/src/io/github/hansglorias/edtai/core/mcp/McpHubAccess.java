package io.github.hansglorias.edtai.core.mcp;

import io.github.hansglorias.edtai.core.chat.ToolUse;

/** Доступ тестов других пакетов к {@link McpHub#problem}. */
public final class McpHubAccess {

    private McpHubAccess() {
    }

    public static ToolUse.Problem problem(Throwable cause) {
        return McpHub.problem(cause == null ? new McpException("ошибка") : new McpException("ошибка", cause));
    }
}
