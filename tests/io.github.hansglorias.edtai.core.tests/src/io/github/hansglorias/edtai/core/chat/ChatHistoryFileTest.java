package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.chat.ChatMessage.Status;
import io.github.hansglorias.edtai.core.context.ContextItem;

class ChatHistoryFileTest {

    @TempDir
    Path temp;

    @Test
    void savesAndLoads() throws Exception {
        List<ChatMessage> messages = List.of(
                new ChatMessage(ChatRole.USER, "вопрос \"в кавычках\"\nи вторая строка", Instant.parse("2026-10-28T09:00:00Z"), Status.DONE),
                new ChatMessage(ChatRole.ASSISTANT, "```bsl\nА = 1;\n```", Instant.parse("2026-10-28T09:00:05.123Z"), Status.STOPPED),
                new ChatMessage(ChatRole.ERROR, "Сервер не ответил", Instant.parse("2026-10-28T09:01:00Z"), Status.DONE));
        ChatHistoryFile file = new ChatHistoryFile(temp.resolve("sub/chat.json"));

        file.save(messages);

        assertEquals(messages, new ChatHistoryFile(file.path()).load());
        assertFalse(Files.exists(temp.resolve("sub/chat.json.tmp")));
    }

    @Test
    void savesAndLoadsAttachments() throws Exception {
        List<ChatMessage> messages = List.of(new ChatMessage(ChatRole.USER, "что на скриншоте", Instant.parse("2026-10-28T09:00:00Z"),
                Status.DONE, List.of(), List.of(), List.of(Attachment.png("shot.png", new byte[] { 1, 2, 3 }),
                        Attachment.file("log.txt", "строка".getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
        ChatHistoryFile file = new ChatHistoryFile(temp.resolve("chat.json"));

        file.save(messages);

        assertEquals(messages, file.load());
        assertTrue(Files.readString(file.path()).contains("\"mediaType\":\"image/png\""));
    }

    @Test
    void savesAndLoadsContext() throws Exception {
        ContextItem lines = ContextItem.of("Демо", "src/CommonModules/Цены/Module.bsl", 10, 12, 40, "А = 1;\nБ = 2;\nВ = 3;")
                .truncate(13);
        ContextItem file = ContextItem.of("Демо", "notes.txt", 1, 1, 1, "заметка");
        List<ChatMessage> messages = List.of(
                new ChatMessage(ChatRole.USER, "что тут?", Instant.parse("2026-10-28T09:00:00Z"), Status.DONE, List.of(lines, file)),
                new ChatMessage(ChatRole.ASSISTANT, "ответ", Instant.parse("2026-10-28T09:00:05Z"), Status.DONE, List.of(),
                        List.of(new ToolUse("call_1", "read_module", "Читает модуль Х", "строки 1–5 из 5", ToolUse.Status.DONE)
                                .withUsage(TokenUsage.step(12_400, 310, false)),
                                new ToolUse("call_2", "search_code", "Ищет «А»", "нет доступа", ToolUse.Status.FAILED)
                                        .withUsage(TokenUsage.step(900, 40, true)))));
        ChatHistoryFile history = new ChatHistoryFile(temp.resolve("chat.json"));

        history.save(messages);

        List<ChatMessage> loaded = history.load();
        assertEquals(messages, loaded);
        assertEquals("ОбщийМодуль.Цены.Модуль: 10–12 (обрезано)", loaded.get(0).context().get(0).label());
        assertEquals("Читает модуль Х — строки 1–5 из 5 · ↑12k ↓310", loaded.get(1).tools().get(0).label());
        assertEquals("Ищет «А» — ошибка: нет доступа · ↑≈900 ↓≈40", loaded.get(1).tools().get(1).label());
        assertFalse(Files.readString(history.path()).contains("\"context\":[]"), "у сообщений без фрагментов поля нет");
    }

    @Test
    void readsFileWithoutContextFromStage3() throws Exception {
        Path path = Files.writeString(temp.resolve("old.json"), "{\"version\":1,\"messages\":[{\"role\":\"USER\",\"status\":\"DONE\","
                + "\"time\":\"2026-10-28T09:00:00Z\",\"text\":\"вопрос\"}]}");

        List<ChatMessage> loaded = new ChatHistoryFile(path).load();

        assertEquals(List.of(new ChatMessage(ChatRole.USER, "вопрос", Instant.parse("2026-10-28T09:00:00Z"), Status.DONE)), loaded);
    }

    @Test
    void missingFileIsEmptyHistory() throws Exception {
        assertEquals(List.of(), new ChatHistoryFile(temp.resolve("missing.json")).load());
    }

    @Test
    void saveOverwrites() throws Exception {
        ChatHistoryFile file = new ChatHistoryFile(temp.resolve("chat.json"));
        file.save(List.of(ChatMessage.of(ChatRole.USER, "раз")));

        file.save(List.of());

        assertTrue(file.load().isEmpty());
    }

    @Test
    void corruptedFileIsReported() throws Exception {
        for (String text : List.of("не json", "{\"messages\":[{\"role\":\"КТО\",\"text\":\"x\"}]}",
                "{\"messages\":[{\"role\":\"USER\"}]}", "{\"messages\":[{\"role\":\"USER\",\"text\":\"x\",\"time\":\"вчера\"}]}",
                "{\"messages\":[{\"role\":\"USER\",\"text\":\"x\",\"context\":[{\"project\":\"Демо\"}]}]}")) {
            Path path = Files.writeString(temp.resolve("bad.json"), text);
            assertThrows(IOException.class, () -> new ChatHistoryFile(path).load(), text);
        }
    }
}
