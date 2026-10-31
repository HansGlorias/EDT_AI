package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.mcp.McpHubAccess;

/** Ошибки вызовов: вид коротко в строке ленты, подробности — в подсказке. */
class ToolUseTest {

    @Test
    void failedCallShowsKindAndShortReason() {
        assertEquals("Читает модуль Х — нет такого инструмента",
                ToolUse.failed("1", "read", "Читает модуль Х", ToolUse.Problem.NO_TOOL, "").label());
        assertEquals("Ищет «А» — неверные аргументы: ожидался объект",
                ToolUse.failed("1", "search", "Ищет «А»", ToolUse.Problem.BAD_ARGUMENTS, "ожидался объект").label());
        assertEquals("MCP «EDT»: build — нет ответа: MCP-сервер не ответил за 60 с.",
                ToolUse.failed("1", "build", "MCP «EDT»: build", ToolUse.Problem.TIMEOUT, "MCP-сервер не ответил за 60 с.").label());
        assertNull(ToolUse.failed("1", "build", "Сборка", ToolUse.Problem.TIMEOUT, "коротко").details(), "всё видно в строке");

        String stack = "Ошибка " + "x".repeat(200) + "\nстрока 2";
        ToolUse long_ = ToolUse.failed("1", "run", "Запуск", ToolUse.Problem.FAILED, stack);
        assertEquals("Запуск — ошибка инструмента: " + ("Ошибка " + "x".repeat(200)).substring(0, ToolUse.SHORT_OUTCOME - 1) + "…",
                long_.label());
        assertEquals(stack, long_.details());

        assertEquals("Старое — ошибка: текст", new ToolUse("1", "old", "Старое", "текст", ToolUse.Status.FAILED).label(),
                "диалоги до обновления — как раньше");
    }

    @Test
    void mcpProblemsAreTold() {
        assertEquals(ToolUse.Problem.SERVER, McpHubAccess.problem(new ConnectException("refused")));
        assertEquals(ToolUse.Problem.SERVER, McpHubAccess.problem(new HttpConnectTimeoutException("connect")));
        assertEquals(ToolUse.Problem.TIMEOUT, McpHubAccess.problem(new HttpTimeoutException("read")));
        assertEquals(ToolUse.Problem.FAILED, McpHubAccess.problem(null));
    }
}
