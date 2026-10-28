package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatMessage.Status;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.context.ContextItem;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;

class RequestBudgetTest {

    @Test
    void budgetLeavesRoomForAnswer() {
        assertEquals((32_768 - 4096) * 3, RequestBudget.chars(connection(0, 32_768)));
        assertEquals((32_768 - 1000) * 3, RequestBudget.chars(connection(1000, 32_768)));
        assertEquals(3000 * 3, RequestBudget.chars(connection(0, 4000)), "на ответ — не больше четверти окна");
        assertEquals(1000 * 3, RequestBudget.chars(connection(100_000, 4000)), "запросу — хотя бы четверть окна");
    }

    @Test
    void windowIsGuessedByModelName() {
        assertEquals(200_000, ModelConnection.guessWindow(Protocol.ANTHROPIC, "claude-sonnet-5-5"));
        assertEquals(200_000, ModelConnection.guessWindow(Protocol.OPENAI_COMPATIBLE, "anthropic/claude-opus"));
        assertEquals(128_000, ModelConnection.guessWindow(Protocol.OPENAI_COMPATIBLE, "gpt-4o-mini"));
        assertEquals(128_000, ModelConnection.guessWindow(Protocol.OPENAI_COMPATIBLE, "o3-mini"));
        assertEquals(64_000, ModelConnection.guessWindow(Protocol.OPENAI_COMPATIBLE, "deepseek-coder"));
        assertEquals(32_768, ModelConnection.guessWindow(Protocol.OPENAI_COMPATIBLE, "qwen3-32b"));
        assertEquals(32_768, ModelConnection.guessWindow(Protocol.OPENAI_COMPATIBLE, "llama-3-8b-instruct"));
        assertEquals(50_000, new ModelConnection(Protocol.ANTHROPIC, "http://localhost", "claude", "", 0, "",
                new TrustSettings(false, Optional.empty()), 50_000).window(), "явное значение из настроек важнее");
    }

    @Test
    void fittingHistoryIsUntouched() {
        List<ChatMessage> history = List.of(user("Вопрос", code(100)), assistant("Ответ"));
        RequestBudget.Fitted fitted = RequestBudget.fit(history, RequestBudgetTest::size, 1000);
        assertSame(history, fitted.history());
        assertFalse(fitted.trimmed());
    }

    @Test
    void oldCodeGoesFirstThenOldExchanges() {
        List<ChatMessage> history = List.of(user("Первый", code(500)), assistant("Ответ 1"), user("Второй", code(500)),
                assistant("Ответ 2"), user("Третий", code(300)));

        RequestBudget.Fitted withoutCode = RequestBudget.fit(history, RequestBudgetTest::size, 400);
        assertEquals(2, withoutCode.withoutCode());
        assertEquals(0, withoutCode.dropped());
        assertEquals(5, withoutCode.history().size());
        assertTrue(withoutCode.history().get(0).modelText().contains("не поместилось"), withoutCode.history().get(0).modelText());
        assertEquals(300, withoutCode.history().get(4).context().get(0).text().length(), "код последнего вопроса остаётся");

        RequestBudget.Fitted dropped = RequestBudget.fit(history, RequestBudgetTest::size, 310);
        assertEquals(4, dropped.dropped());
        assertEquals(List.of("Третий"), dropped.history().stream().map(ChatMessage::text).toList());
        assertEquals("Диалог длиннее окна модели — модель не видит самые старые сообщения (4) и прикреплённый к старым сообщениям"
                + " код (2). Начните новый чат или увеличьте «Окно модели» в настройках", dropped.notice().label());
    }

    @Test
    void answerCarriesTraceOfTools() {
        ChatMessage answer = assistant("В модуле одна процедура.").withTools(List.of(
                new ToolUse("call_1", "read_module", "Читает модуль Х", "строки 1–3 из 3", ToolUse.Status.DONE),
                new ToolUse("context", "context", "Окно модели", "старые сообщения не переданы — 2", ToolUse.Status.DONE)));
        assertEquals("[Вызванные инструменты: Читает модуль Х — строки 1–3 из 3]\n\nВ модуле одна процедура.", answer.modelText());
        assertEquals("Без инструментов.", assistant("Без инструментов.").modelText());
    }

    /** Размер — длина текстов для модели: так проще считать ожидания. */
    private static int size(List<ChatMessage> history) {
        return history.stream().mapToInt(message -> message.text().length()
                + message.context().stream().mapToInt(item -> item.text().length()).sum()).sum();
    }

    private static ChatMessage user(String text, ContextItem code) {
        return new ChatMessage(ChatRole.USER, text, Instant.now(), Status.DONE, List.of(code));
    }

    private static ChatMessage assistant(String text) {
        return ChatMessage.of(ChatRole.ASSISTANT, text);
    }

    private static ContextItem code(int length) {
        String text = "а".repeat(length);
        return new ContextItem("Демо", "src/CommonModules/Х/Module.bsl", "ОбщийМодуль.Х", 1, 1, 1, text, 0);
    }

    private static ModelConnection connection(int maxTokens, int window) {
        return new ModelConnection(Protocol.OPENAI_COMPATIBLE, "http://localhost", "m", "", maxTokens, "",
                new TrustSettings(false, Optional.empty()), window);
    }
}
