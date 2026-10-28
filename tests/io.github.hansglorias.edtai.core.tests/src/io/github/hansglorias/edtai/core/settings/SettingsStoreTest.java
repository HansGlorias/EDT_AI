package io.github.hansglorias.edtai.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void savesAndLoadsSettings() throws Exception {
        AiSettings settings = new AiSettings(Protocol.ANTHROPIC, "https://llm.example.invalid", "test-model", false,
                "/etc/ssl/corp-ca.pem", List.of(
                        new McpServer("fs", McpTransport.STDIO, "server --stdio", true),
                        new McpServer("web", McpTransport.HTTP, "http://localhost:8080/mcp", false)));

        store.save(settings);

        assertEquals(settings, new SettingsStore(preferences, secure).load());
    }

    @Test
    void emptyValuesAreRemovedFromPreferences() throws Exception {
        store.save(new AiSettings(Protocol.ANTHROPIC, "https://llm.example.invalid", "m", true, "", List.of()));
        store.save(new AiSettings(Protocol.ANTHROPIC, "", "", true, "", List.of()));

        assertEquals(List.of(SettingsStore.KEY_PROTOCOL, SettingsStore.KEY_USE_SYSTEM_TRUST_STORE),
                List.of(preferences.keys()).stream().sorted().toList());
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
}
