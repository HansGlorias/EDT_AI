package io.github.hansglorias.edtai.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsValidatorTest {

    @TempDir
    Path temp;

    @Test
    void defaultsAreValid() {
        assertTrue(SettingsValidator.validate(AiSettings.defaults()).isEmpty());
        assertTrue(!AiSettings.defaults().isConfigured());
    }

    @Test
    void acceptsHttpAndHttpsUrls() {
        assertNull(SettingsValidator.checkHttpUrl("https://llm.example.invalid"));
        assertNull(SettingsValidator.checkHttpUrl("http://localhost:11434/v1"));
        assertNull(SettingsValidator.checkHttpUrl("HTTPS://10.0.0.1:8443"));
    }

    @Test
    void rejectsBadUrls() {
        assertNotNull(SettingsValidator.checkHttpUrl("llm.example.invalid"));
        assertNotNull(SettingsValidator.checkHttpUrl("ftp://llm.example.invalid"));
        assertNotNull(SettingsValidator.checkHttpUrl("https://"));
        assertNotNull(SettingsValidator.checkHttpUrl("https://bad host"));
    }

    @Test
    void reportsBadBaseUrl() {
        AiSettings settings = new AiSettings(Protocol.ANTHROPIC, "llm", "model", 0, "", true, "", List.of());

        List<String> errors = SettingsValidator.validate(settings);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("Адрес сервера"), errors.get(0));
    }

    @Test
    void limitsMaxTokens() {
        AiSettings settings = new AiSettings(null, "", "", AiSettings.MAX_TOKENS_LIMIT + 1, "", true, "", List.of());

        List<String> errors = SettingsValidator.validate(settings);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).startsWith("Макс. токенов"), errors.get(0));
        assertTrue(SettingsValidator.validate(new AiSettings(null, "", "", 4096, "", true, "", List.of())).isEmpty());
    }

    @Test
    void warnsAboutPlainHttpToOtherHosts() {
        assertNotNull(SettingsValidator.plainHttpWarning("http://llm.example.invalid/v1"));
        assertNotNull(SettingsValidator.plainHttpWarning("HTTP://10.0.0.1:8000"));
        assertNull(SettingsValidator.plainHttpWarning("https://llm.example.invalid"));
        assertNull(SettingsValidator.plainHttpWarning("http://localhost:11434"));
        assertNull(SettingsValidator.plainHttpWarning("http://127.0.0.1:1234/v1"));
        assertNull(SettingsValidator.plainHttpWarning("http://[::1]:8080"));
        assertNull(SettingsValidator.plainHttpWarning(""));
        assertNull(SettingsValidator.plainHttpWarning("bad host"));
    }

    @Test
    void reportsMissingCertificateFile() {
        AiSettings settings = new AiSettings(null, "", "", 0, "", true, temp.resolve("missing.pem").toString(), List.of());

        List<String> errors = SettingsValidator.validate(settings);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("не найден"), errors.get(0));
    }

    @Test
    void acceptsExistingCertificateFile() throws IOException {
        Path file = Files.writeString(temp.resolve("ca.pem"), "-----BEGIN CERTIFICATE-----");
        AiSettings settings = new AiSettings(null, "", "", 0, "", true, file.toString(), List.of());

        assertTrue(SettingsValidator.validate(settings).isEmpty());
    }

    @Test
    void checksMcpServers() {
        assertTrue(SettingsValidator.validateMcpServer(new McpServer("fs", McpTransport.STDIO, "server --stdio", true)).isEmpty());
        assertTrue(SettingsValidator.validateMcpServer(new McpServer("web", McpTransport.HTTP, "http://localhost/mcp", true)).isEmpty());

        assertEquals(1, SettingsValidator.validateMcpServer(new McpServer("", McpTransport.STDIO, "cmd", true)).size());
        assertEquals(1, SettingsValidator.validateMcpServer(new McpServer("fs", McpTransport.STDIO, "", true)).size());
        assertEquals(1, SettingsValidator.validateMcpServer(new McpServer("web", McpTransport.HTTP, "localhost/mcp", true)).size());
    }

    @Test
    void rejectsDuplicateMcpNamesIgnoringCase() {
        List<String> errors = SettingsValidator.validateMcpServers(List.of(
                new McpServer("Files", McpTransport.STDIO, "a", true),
                new McpServer("files", McpTransport.STDIO, "b", false)));

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("дважды"), errors.get(0));
    }
}
