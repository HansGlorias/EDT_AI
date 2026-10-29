package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.context.ContextItem;

class ChatMarkdownTest {

    @Test
    void chatIsExportedInOrder() {
        Instant asked = Instant.parse("2026-10-28T11:05:00Z");
        ContextItem code = ContextItem.of("Demo", "src/CommonModules/Даты/Module.bsl", 3, 4, 10,
                "Возврат ТекущаяДата();\n// ```\n");
        String answer = "Сначала посмотрю модуль.\nВот исправление.";
        List<ChatMessage> messages = List.of(
                new ChatMessage(ChatRole.USER, "Что не так?", asked, ChatMessage.Status.DONE, List.of(code)),
                new ChatMessage(ChatRole.ASSISTANT, answer, asked.plusSeconds(60), ChatMessage.Status.STOPPED, List.of(),
                        List.of(new ToolUse("1", "read_module", "Читает модуль Даты", "строки 1–10 из 10", ToolUse.Status.DONE, 5),
                                new ToolUse("", "tools", "Инструменты не передаются", "", ToolUse.Status.DONE, 0))),
                new ChatMessage(ChatRole.ERROR, "Сервер недоступен", Instant.EPOCH, ChatMessage.Status.DONE));

        assertEquals("""
                # Даты

                ## Вопрос · 28.10.2026 14:05

                Что не так?

                Прикреплено: `ОбщийМодуль.Даты.Модуль: 3–4` (Demo/src/CommonModules/Даты/Module.bsl)

                ````bsl
                Возврат ТекущаяДата();
                // ```
                ````

                ## Ответ (остановлен) · 28.10.2026 14:06

                Сначала посмотрю модуль.

                - → Читает модуль Даты — строки 1–10 из 10

                Вот исправление.

                ## Ошибка

                Сервер недоступен
                """, ChatMarkdown.export(" Даты ", messages, ZoneOffset.ofHours(3)));
    }

    @Test
    void answerFilesAreListed() {
        ChatMessage answer = new ChatMessage(ChatRole.ASSISTANT, "Готово.", Instant.EPOCH, ChatMessage.Status.DONE, List.of(),
                List.of(), List.of(new Attachment("Отчёт.md", "text/markdown", "# Отчёт")));
        assertEquals("# Чат\n\n## Ответ\n\nГотово.\n\nФайл: `Отчёт.md`\n", ChatMarkdown.export("Чат", List.of(answer), ZoneOffset.UTC));
    }
}
