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

    @Test
    void modelTraceHasNoTokensAndNoChildSteps() {
        ToolUse read = new ToolUse("c1", "read_module", "Читает модуль Х", "строки 1–3", ToolUse.Status.DONE)
                .withUsage(TokenUsage.step(12_000, 300, false));
        ToolUse child = new ToolUse("c2/child/u/1", "delegate_task", "Ревью: Читает модуль Y", "строки 1–9", ToolUse.Status.DONE);
        ChatMessage answer = new ChatMessage(ChatRole.ASSISTANT, "Ответ", java.time.Instant.EPOCH, ChatMessage.Status.DONE,
                java.util.List.of(), java.util.List.of(read, child));
        assertEquals("[Вызванные инструменты: Читает модуль Х — строки 1–3]\n\nОтвет", answer.modelText());
        assertEquals("Читает модуль Х — строки 1–3 · ↑12k ↓300", read.label(), "в ленте токены остаются");
    }

    @Test
    void tracesWrittenByModelAnywhereAreRemoved() {
        String text = "Смотрю.\n\n[Вызванные инструменты: Ищет «А» — найдено 1 · ↑5k ↓32; Смотрит [список]]\n\nДальше."
                + " [Вызванные инструменты: Читает модуль Б]";
        assertEquals("Смотрю.\n\nДальше.", ChatMessage.stripTraces(text));
        assertEquals("Начало [Вызванные инструменты: оборвано", ChatMessage.stripTraces("Начало [Вызванные инструменты: оборвано"));
        assertEquals("[Ссылка](x) текст", ChatMessage.stripTraces("[Ссылка](x) текст"));
    }
}
