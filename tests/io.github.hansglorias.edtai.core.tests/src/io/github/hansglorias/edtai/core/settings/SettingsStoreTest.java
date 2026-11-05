package io.github.hansglorias.edtai.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.hansglorias.edtai.core.chat.ChatModel;
import io.github.hansglorias.edtai.core.llm.ModelChatProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.spec.PBEKeySpec;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.provider.IProviderHints;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsStoreTest {

    private static final String TOKEN = "sk-test-0123456789-not-a-real-token";

    @TempDir
    Path temp;

    private IEclipsePreferences preferences;
    private ISecurePreferences secure;
    private Path secureFile;
    private SettingsStore store;

    @BeforeEach
    void setUp() throws Exception {
        preferences = (IEclipsePreferences) InstanceScope.INSTANCE.getNode("edtai.tests").node(UUID.randomUUID().toString());
        secureFile = temp.resolve("secure_storage");
        Map<String, Object> options = new HashMap<>();
        // Пароль задан явно, чтобы Secure Storage не спрашивал его в окне и не лез в связку ключей ОС.
        options.put(IProviderHints.DEFAULT_PASSWORD, new PBEKeySpec("test-password".toCharArray()));
        options.put(IProviderHints.PROMPT_USER, Boolean.FALSE);
        secure = SecurePreferencesFactory.open(secureFile.toUri().toURL(), options);
        store = new SettingsStore(preferences, secure);
    }

    @AfterEach
    void tearDown() throws Exception {
        preferences.removeNode();
    }

    @Test
    void loadsDefaultsFromEmptyPreferences() {
        assertEquals(AiSettings.defaults(), store.load());
    }

    @Test
    void mcpIntervalRoundTripsAndModelSwitchPreservesIt() throws Exception {
        assertEquals(5, store.load().mcpCheckMinutes());
        for (int minutes : List.of(0, 3, 5, 10)) {
            AiSettings value = new AiSettings(null, "", "", 0, "", 0, true, false, "", List.of(), 0, minutes);
            store.save(value);
            assertEquals(minutes, new SettingsStore(preferences, secure).load().mcpCheckMinutes());
            assertEquals(minutes, value.withModel("other").mcpCheckMinutes());
        }
    }

    @Test
    void savesAndLoadsSettings() throws Exception {
        AiSettings settings = new AiSettings(Protocol.ANTHROPIC, "https://llm.example.invalid", "test-model", 2048, "", 12_000, true, false,
                "/etc/ssl/corp-ca.pem", List.of(
                        new McpServer("fs", McpTransport.STDIO, "server --stdio", true),
                        new McpServer("web", McpTransport.HTTP, "http://localhost:8080/mcp", false)));

        store.save(settings);

        assertEquals(settings, new SettingsStore(preferences, secure).load());
    }

    @Test
    void emptyValuesAreRemovedFromPreferences() throws Exception {
        String prompt = AiSettings.DEFAULT_SYSTEM_PROMPT;
        store.save(new AiSettings(Protocol.ANTHROPIC, "https://llm.example.invalid", "m", 100, "свой", 0, true, true, "", List.of()));
        store.save(new AiSettings(Protocol.ANTHROPIC, "", "", 0, prompt, 0, true, true, "", List.of()));

        assertEquals(List.of(SettingsStore.KEY_PROTOCOL, SettingsStore.KEY_USE_SYSTEM_TRUST_STORE),
                List.of(preferences.keys()).stream().sorted().toList());
    }

    @Test
    void defaultSystemPromptIsNotStoredButEmptyOneIs() throws Exception {
        assertEquals(AiSettings.DEFAULT_SYSTEM_PROMPT, store.load().systemPrompt());

        store.save(new AiSettings(null, "", "", 0, "", 0, true, true, "", List.of()));
        assertEquals("", preferences.get(SettingsStore.KEY_SYSTEM_PROMPT, null));
        assertEquals("", store.load().systemPrompt());

        store.save(new AiSettings(null, "", "", 0, "Отвечай кратко.\nБез воды.", 0, true, true, "", List.of()));
        assertEquals("Отвечай кратко.\nБез воды.", store.load().systemPrompt());

        store.save(AiSettings.defaults());
        assertEquals(null, preferences.get(SettingsStore.KEY_SYSTEM_PROMPT, null));
        assertEquals(AiSettings.DEFAULT_SYSTEM_PROMPT, store.load().systemPrompt());
    }

    @Test
    void modelWindowIsStoredOnlyWhenNotDefault() throws Exception {
        assertEquals(AiSettings.AUTO_MODEL_WINDOW, store.load().modelWindow());

        store.save(new AiSettings(null, "", "", 0, "", 0, true, true, "", List.of(), 131_072));
        assertEquals(131_072, preferences.getInt(SettingsStore.KEY_MODEL_WINDOW, 0));
        assertEquals(131_072, store.load().modelWindow());

        store.save(AiSettings.defaults());
        assertEquals(null, preferences.get(SettingsStore.KEY_MODEL_WINDOW, null));
    }

    @Test
    void defaultContextLimitIsNotStored() throws Exception {
        assertEquals(AiSettings.DEFAULT_CONTEXT_LIMIT, store.load().contextLimit());

        store.save(new AiSettings(null, "", "", 0, "", 5_000, true, true, "", List.of()));
        assertEquals(5_000, preferences.getInt(SettingsStore.KEY_CONTEXT_LIMIT, 0));
        assertEquals(5_000, store.load().contextLimit());

        store.save(new AiSettings(null, "", "", 0, "", 0, true, true, "", List.of()));
        assertEquals(null, preferences.get(SettingsStore.KEY_CONTEXT_LIMIT, null));
        assertEquals(AiSettings.DEFAULT_CONTEXT_LIMIT, store.load().contextLimit());
    }

    @Test
    void toolsAreOnByDefaultAndOffIsStored() throws Exception {
        assertTrue(store.load().toolsEnabled());

        store.save(new AiSettings(null, "", "", 0, "", 0, false, true, "", List.of()));
        assertEquals("false", preferences.get(SettingsStore.KEY_TOOLS_ENABLED, null));
        assertFalse(store.load().toolsEnabled());

        store.save(AiSettings.defaults());
        assertEquals(null, preferences.get(SettingsStore.KEY_TOOLS_ENABLED, null));
    }

    @Test
    void unknownProtocolFallsBackToDefault() {
        preferences.put(SettingsStore.KEY_PROTOCOL, "SOMETHING_ELSE");

        assertEquals(AiSettings.DEFAULT_PROTOCOL, store.load().protocol());
    }

    @Test
    void corruptedMcpListIsIgnored() {
        preferences.put(SettingsStore.KEY_MCP_SERVERS, "garbage");

        assertTrue(store.load().mcpServers().isEmpty());
    }

    @Test
    void tokenIsStoredOnlyInSecureStorage() throws Exception {
        assertFalse(store.hasToken());

        store.saveToken(TOKEN);
        store.save(AiSettings.defaults());

        assertTrue(store.hasToken());
        assertEquals(TOKEN, store.loadToken());
        assertTrue(secure.node(SettingsStore.SECURE_NODE).isEncrypted(SettingsStore.SECURE_KEY_TOKEN));
        for (String key : preferences.keys()) {
            assertFalse(preferences.get(key, "").contains(TOKEN), key);
        }
        String file = Files.readString(secureFile, StandardCharsets.ISO_8859_1);
        assertFalse(file.contains(TOKEN), "токен записан в Secure Storage открытым текстом");
    }

    @Test
    void emptyTokenRemovesSavedOne() throws Exception {
        store.saveToken(TOKEN);
        store.saveToken("");

        assertFalse(store.hasToken());
        assertEquals("", store.loadToken());
    }
    @Test
    void providersMigrateLegacyWithoutReadingTokenAndKeepSecretsSeparate() throws Exception {
        AiSettings original = new AiSettings(Protocol.OPENAI_COMPATIBLE, "http://localhost:8080", "small", 100,
                "общий промпт", 7000, true, false, "", List.of(), 8192);
        store.save(original);
        store.saveToken(TOKEN);
        ApiProvider first = store.providers().get(0);
        ApiProvider second = new ApiProvider("other", "Второй", Protocol.ANTHROPIC, "http://localhost:8081", "large",
                200, 32768, true, "");
        store.saveProviders(List.of(first, second), second.id());
        store.saveToken(second.id(), "test-other-secret");

        assertEquals(second.apply(original), store.load());
        assertEquals(TOKEN, store.loadToken(first.id()));
        assertEquals("test-other-secret", store.loadToken());
        assertEquals(TOKEN, secure.node(SettingsStore.SECURE_NODE).get(SettingsStore.SECURE_KEY_TOKEN, ""));
        assertTrue(secure.node(SettingsStore.SECURE_NODE + "/providers/other").isEncrypted(SettingsStore.SECURE_KEY_TOKEN));
        for (String key : preferences.keys()) {
            assertFalse(preferences.get(key, "").contains(TOKEN));
            assertFalse(preferences.get(key, "").contains("test-other-secret"));
        }
        ChatModel oldChat = new ChatModel("small-custom", original.baseUrl());
        assertEquals("small-custom", store.forChat(oldChat).model());
        assertEquals(first.id(), store.forChat(oldChat).providerId(), "старый чат остаётся на прежнем подключении");
        var connection = ModelChatProvider.connection(store, ignored -> store.forChat(oldChat));
        assertEquals(original.baseUrl(), connection.baseUrl());
        assertEquals(TOKEN, connection.token());
        assertEquals(Protocol.OPENAI_COMPATIBLE, connection.protocol());
        assertEquals(8192, connection.window());

        store.save(store.load().withModel("large-custom"));
        assertEquals("small", store.provider(first.id()).model());
        assertEquals("large-custom", store.provider(second.id()).model());
        assertEquals(second.id(), store.load().providerId());
    }

    @Test
    void profileIdentitySeparatesSameEndpointAndDeletionDoesNotRerouteChat() throws Exception {
        ApiProvider first = new ApiProvider("first", "Первый", Protocol.OPENAI_COMPATIBLE, "http://localhost:8080", "m",
                0, 8192, false, "");
        ApiProvider second = new ApiProvider("second", "Второй", Protocol.OPENAI_COMPATIBLE, first.baseUrl(), "m",
                0, 16384, true, "");
        store.saveProviders(List.of(first, second), second.id());
        store.saveToken(first.id(), "first-secret");
        store.saveToken(second.id(), "second-secret");
        ChatModel bound = ChatModel.of(first.apply(AiSettings.defaults()));
        assertEquals("first-secret", ModelChatProvider.connection(store, ignored -> store.forChat(bound)).token());
        assertEquals(8192, ModelChatProvider.connection(store, ignored -> store.forChat(bound)).window());
        assertFalse(bound.appliesTo(store.load()));
        store.saveProviders(List.of(second), second.id());
        assertFalse(store.forChat(bound).isConfigured(), "удаление не отправляет историю другому провайдеру");
        assertThrows(Exception.class, () -> ModelChatProvider.connection(store, ignored -> store.forChat(bound)));
        assertThrows(IllegalArgumentException.class, () -> store.saveProviders(List.of(second, second), second.id()));
        assertThrows(IllegalArgumentException.class, () -> store.saveToken("../escape", "secret"));
    }

}
