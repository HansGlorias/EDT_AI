package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.Protocol;

class ChatModelTest {

    private static final AiSettings SETTINGS = new AiSettings(Protocol.OPENAI_COMPATIBLE, "http://localhost:8080/v1/", "qwen-flash", 0,
            "", 0, true, false, "", new ArrayList<>());

    @TempDir
    Path temp;

    @Test
    void chatModelReplacesSettingsModelOnSameServerOnly() {
        ChatModel deepseek = new ChatModel(" deepseek-flash ", "HTTP://localhost:8080/v1");
        assertEquals("deepseek-flash", deepseek.model());
        assertTrue(deepseek.appliesTo(SETTINGS), "тот же сервер: регистр и косая черта в конце не важны");
        AiSettings applied = deepseek.apply(SETTINGS);
        assertEquals("deepseek-flash", applied.model());
        assertEquals(SETTINGS.withModel("deepseek-flash"), applied, "остальные настройки — из общих");

        ChatModel elsewhere = new ChatModel("deepseek-flash", "http://example.invalid/v1");
        assertFalse(elsewhere.appliesTo(SETTINGS));
        assertSame(SETTINGS, elsewhere.apply(SETTINGS), "другой сервер — модель из настроек");
        assertSame(SETTINGS, ChatModel.NONE.apply(SETTINGS));
        assertEquals(new ChatModel("qwen-flash", "http://localhost:8080/v1/"), ChatModel.of(SETTINGS));
    }

    @Test
    void storeKeepsModelPerChatOutsideChatList() throws Exception {
        ChatStore store = new ChatStore(temp.resolve("chats"));
        String first = store.newId();
        store.file(first).save(List.of(ChatMessage.of(ChatRole.USER, "вопрос")));
        String second = first + "-2";
        store.file(second).save(List.of(ChatMessage.of(ChatRole.USER, "другой")));
        assertEquals(ChatModel.NONE, store.model(first));

        store.setModel(first, new ChatModel("deepseek-flash", "http://localhost:8080/v1"));
        store.setModel(second, new ChatModel("qwen-flash", "http://localhost:8080/v1"));
        ChatStore reopened = new ChatStore(temp.resolve("chats"));
        assertEquals(new ChatModel("deepseek-flash", "http://localhost:8080/v1"), reopened.model(first));
        assertEquals("qwen-flash", reopened.model(second).model());
        assertEquals(2, reopened.list().size(), "models.json — не диалог");

        reopened.delete(first);
        assertEquals(ChatModel.NONE, reopened.model(first), "удалённый чат забывает модель");
        assertEquals("qwen-flash", reopened.model(second).model());

        reopened.setModel(second, ChatModel.NONE);
        assertEquals(ChatModel.NONE, reopened.model(second));

        reopened.setContext(second, 45_000);
        assertEquals(45_000, new ChatStore(temp.resolve("chats")).context(second), "окно последнего запроса переживает перезапуск");
        assertEquals(-1, reopened.context(first));
        assertEquals(1, reopened.list().size(), "contexts.json — не диалог");
        reopened.delete(second);
        assertEquals(-1, reopened.context(second), "удалённый чат забывает окно");

        Files.writeString(temp.resolve("chats/models.json"), "{не json");
        assertEquals(ChatModel.NONE, new ChatStore(temp.resolve("chats")).model(second), "повреждённый файл — модель из настроек");
    }
    @Test
    void providerIdentitySurvivesRestartAndLegacyBindingStillLoads() throws Exception {
        ChatStore store = new ChatStore(temp.resolve("chats"));
        store.setModel("one", new ChatModel("model", "http://localhost:8080", "first"));
        assertEquals("first", new ChatStore(temp.resolve("chats")).model("one").providerId());
        Files.writeString(temp.resolve("chats/models.json"),
                "{\"one\":{\"model\":\"old\",\"baseUrl\":\"http://localhost:8080\"}}");
        assertEquals(new ChatModel("old", "http://localhost:8080"), store.model("one"));
    }

}
