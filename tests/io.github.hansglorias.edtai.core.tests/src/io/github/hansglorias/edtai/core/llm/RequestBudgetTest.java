package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatMessage.Status;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
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
    void imagesGoAsBlocksInBothProtocolsAndCostFixed() {
        Attachment shot = Attachment.png("shot.png", new byte[30_000]);
        List<ChatMessage> history = List.of(new ChatMessage(ChatRole.USER, "Что за ошибка?", java.time.Instant.EPOCH,
                ChatMessage.Status.DONE, List.of(), List.of(), List.of(shot)));

        Object anthropic = new AnthropicAdapter().messages(history).get(0);
        assertEquals("image", Json.stringAt(anthropic, "content", 0, "type"));
        assertEquals("image/png", Json.stringAt(anthropic, "content", 0, "source", "media_type"));
        assertEquals(shot.data(), Json.stringAt(anthropic, "content", 0, "source", "data"));
        assertEquals("[Приложено изображение: shot.png]\n\nЧто за ошибка?", Json.stringAt(anthropic, "content", 1, "text"));
        Object openAi = new OpenAiAdapter().messages(history).get(0);
        assertEquals("image_url", Json.stringAt(openAi, "content", 0, "type"));
        assertTrue(Json.stringAt(openAi, "content", 0, "image_url", "url").startsWith("data:image/png;base64,AAAA"));
        assertTrue(Json.at(new OpenAiAdapter().messages(List.of(user("Что за ошибка?", code(0)))).get(0), "content") instanceof String,
                "без картинок — строка, как раньше");

        ModelConnection connection = connection(0, 100_000);
        int withImage = RequestBudget.size(connection, history, List.of(), List.of());
        int withoutImage = RequestBudget.size(connection, List.of(history.get(0).withAttachments(List.of())), List.of(), List.of());
        assertTrue(withImage - withoutImage < RequestBudget.IMAGE_CHARS + 500, "картинка — по фиксированной цене, не по base64");
        assertTrue(withImage - withoutImage > RequestBudget.IMAGE_CHARS - 500);
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
        assertEquals("Диалог длиннее окна модели — модель не видит самые старые сообщения (4) и прикреплённые к старым сообщениям"
                + " код и файлы (2). Начните новый чат или увеличьте «Окно модели» в настройках", dropped.notice().label());
    }

    @Test
    void compactionStartsWhenHistoryTakesHalfOfBudget() {
        // Окно 2048 токенов — бюджет 4608 символов; сжимать, когда история больше половины.
        ModelConnection small = connection(0, 2048);
        ModelChatProvider provider = new ModelChatProvider(() -> small, new ModelClient());
        List<ChatMessage> history = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            history.add(i % 2 == 0 ? user("вопрос " + i + " " + "а".repeat(600), code(0)) : assistant("ответ " + "б".repeat(600)));
        }
        int count = provider.compactCount(history);
        assertTrue(count >= 2 && count <= 8, String.valueOf(count));
        assertEquals(ChatRole.USER, history.get(count).role(), "сжимаются целые обмены");
        assertEquals(0, provider.compactCount(history.subList(6, 9)), "короткая история не сжимается");

        String transcript = ModelChatProvider.transcript(history, 1500);
        assertTrue(transcript.startsWith("Пользователь: вопрос 8") || transcript.startsWith("Ассистент: "), transcript);
        assertTrue(transcript.length() <= 1500);
    }

    @Test
    void titleIsCleanedFromAnswer() {
        assertEquals("Дата сеанса в общем модуле", ModelChatProvider.cleanTitle("\n**«Дата сеанса в общем модуле».**\nПояснение"));
        assertEquals("Ревью проведения", ModelChatProvider.cleanTitle("Название: \"Ревью проведения\""));
        assertEquals("Ошибки проекта", ModelChatProvider.cleanTitle("# Ошибки проекта"));
        assertEquals("", ModelChatProvider.cleanTitle("  \n "));
        assertEquals(80, ModelChatProvider.cleanTitle("а".repeat(200)).length());
    }

    @Test
    void answerCarriesTraceOfTools() {
        ChatMessage answer = assistant("В модуле одна процедура.").withTools(List.of(
                new ToolUse("call_1", "read_module", "Читает модуль Х", "строки 1–3 из 3", ToolUse.Status.DONE),
                new ToolUse("context", "context", "Окно модели", "старые сообщения не переданы — 2", ToolUse.Status.DONE)));
        assertEquals("[Вызванные инструменты: Читает модуль Х — строки 1–3 из 3]\n\nВ модуле одна процедура.", answer.modelText());
        // Модель повторила след сама — в ответ он не попадает; вложенные скобки аргументов не мешают.
        assertEquals("Готово.", ChatMessage.stripTrace("[Вызванные инструменты: MCP «EDT»: modify ([{name: \"x\"}]) — OK]\n\nГотово."));
        assertEquals("[Ссылка] текст", ChatMessage.stripTrace("[Ссылка] текст"));
        assertEquals("[Вызванные инструменты: оборвано", ChatMessage.stripTrace("[Вызванные инструменты: оборвано"));
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
