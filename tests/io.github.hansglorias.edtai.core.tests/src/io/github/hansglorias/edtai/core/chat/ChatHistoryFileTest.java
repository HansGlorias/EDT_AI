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
                "{\"messages\":[{\"role\":\"USER\"}]}", "{\"messages\":[{\"role\":\"USER\",\"text\":\"x\",\"time\":\"вчера\"}]}")) {
            Path path = Files.writeString(temp.resolve("bad.json"), text);
            assertThrows(IOException.class, () -> new ChatHistoryFile(path).load(), text);
        }
    }
}
