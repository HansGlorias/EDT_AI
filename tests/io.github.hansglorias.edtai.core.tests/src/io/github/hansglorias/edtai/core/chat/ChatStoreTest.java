package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatStoreTest {

    @TempDir
    Path temp;

    @Test
    void chatsAreListedByLastChange() throws Exception {
        ChatStore store = new ChatStore(temp.resolve("chats"));
        assertEquals(List.of(), store.list());
        assertEquals(Optional.empty(), store.latest());

        String first = store.newId();
        store.file(first).save(List.of(ChatMessage.of(ChatRole.USER, "  Как   получить\nтекущую дату?  "),
                ChatMessage.of(ChatRole.ASSISTANT, "ТекущаяДатаСеанса()")));
        Files.setLastModifiedTime(store.file(first).path(), FileTime.from(Instant.parse("2026-10-01T10:00:00Z")));
        String second = store.newId();
        assertNotEquals(first, second);
        store.file(second).save(List.of(ChatMessage.of(ChatRole.USER, "а".repeat(100))));

        List<ChatStore.Entry> entries = store.list();
        assertEquals(List.of(second, first), entries.stream().map(ChatStore.Entry::id).toList());
        assertEquals("а".repeat(60) + "…", entries.get(0).title());
        assertEquals("Как получить текущую дату?", entries.get(1).title());
        assertEquals(Optional.of(second), store.latest());

        assertEquals(Optional.of(second), store.current(), "без запомненного — последний изменённый");
        store.setCurrent(first);
        assertEquals(Optional.of(first), store.current());
        assertEquals(2, store.list().size(), "служебный файл в списке не виден");

        store.delete(second);
        assertEquals(List.of(first), store.list().stream().map(ChatStore.Entry::id).toList());
    }

    @Test
    void legacyChatIsMigratedAndBrokenFilesSkipped() throws Exception {
        Path legacy = temp.resolve("chat.json");
        new ChatHistoryFile(legacy).save(List.of(ChatMessage.of(ChatRole.USER, "старый диалог")));
        ChatStore store = new ChatStore(temp.resolve("chats"));

        store.migrate(legacy);
        assertFalse(Files.exists(legacy));
        Files.writeString(temp.resolve("chats/broken.json"), "{не json");

        List<ChatStore.Entry> entries = store.list();
        assertEquals(1, entries.size());
        assertEquals("старый диалог", entries.get(0).title());
        assertTrue(store.file(entries.get(0).id()).load().get(0).text().equals("старый диалог"));
    }

    @Test
    void oldChatsArePruned() throws Exception {
        ChatStore store = new ChatStore(temp.resolve("chats"));
        for (int i = 0; i < ChatStore.MAX_CHATS + 3; i++) {
            String id = "chat-" + String.format("%03d", i);
            store.file(id).save(List.of(ChatMessage.of(ChatRole.USER, "вопрос " + i)));
            Files.setLastModifiedTime(store.file(id).path(), FileTime.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i)));
        }
        store.prune();
        List<ChatStore.Entry> entries = store.list();
        assertEquals(ChatStore.MAX_CHATS, entries.size());
        assertEquals("вопрос 3", entries.get(entries.size() - 1).title());
    }
}
