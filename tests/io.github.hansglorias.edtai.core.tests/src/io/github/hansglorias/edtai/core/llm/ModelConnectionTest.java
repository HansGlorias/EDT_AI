package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;

class ModelConnectionTest {

    private static final String TOKEN = "sk-test-0123456789-not-a-real-token";

    @Test
    void buildsOpenAiEndpointFromAnyFormOfAddress() throws Exception {
        for (String base : List.of("https://llm.example.invalid", "https://llm.example.invalid/", "https://llm.example.invalid/v1",
                "https://llm.example.invalid/v1/", "https://llm.example.invalid/v1/chat/completions")) {
            assertEquals("https://llm.example.invalid/v1/chat/completions", endpoint(Protocol.OPENAI_COMPATIBLE, base), base);
        }
        assertEquals("http://localhost:11434/v1/chat/completions", endpoint(Protocol.OPENAI_COMPATIBLE, "http://localhost:11434"));
        assertEquals("https://gw.example.invalid/api/openai/v1/chat/completions",
                endpoint(Protocol.OPENAI_COMPATIBLE, "https://gw.example.invalid/api/openai/v1"));
        assertEquals("https://gw.example.invalid/llm/v1/chat/completions", endpoint(Protocol.OPENAI_COMPATIBLE, "https://gw.example.invalid/llm"));
        assertEquals("https://gw.example.invalid/v1beta/openai/v4/chat/completions",
                endpoint(Protocol.OPENAI_COMPATIBLE, "https://gw.example.invalid/v1beta/openai/v4"));
        assertEquals("https://gw.example.invalid/v1/chat/completions?tenant=a",
                endpoint(Protocol.OPENAI_COMPATIBLE, "https://gw.example.invalid?tenant=a"));
    }

    @Test
    void buildsAnthropicEndpoint() throws Exception {
        assertEquals("https://llm.example.invalid/v1/messages", endpoint(Protocol.ANTHROPIC, "https://llm.example.invalid"));
        assertEquals("https://llm.example.invalid/v1/messages", endpoint(Protocol.ANTHROPIC, "https://llm.example.invalid/v1"));
        assertEquals("https://llm.example.invalid/v1/messages", endpoint(Protocol.ANTHROPIC, "https://llm.example.invalid/v1/messages"));
    }

    @Test
    void rejectsBadAddress() {
        assertThrows(ModelException.class, () -> endpoint(Protocol.ANTHROPIC, ""));
        assertThrows(ModelException.class, () -> endpoint(Protocol.ANTHROPIC, "llm.example.invalid"));
        assertThrows(ModelException.class, () -> endpoint(Protocol.ANTHROPIC, "ftp://llm.example.invalid"));
        assertThrows(ModelException.class, () -> endpoint(Protocol.ANTHROPIC, "https://bad host"));
    }

    @Test
    void tokenIsNotInToString() {
        ModelConnection connection = new ModelConnection(Protocol.ANTHROPIC, "https://llm.example.invalid", "m", TOKEN, 0, "",
                new TrustSettings(true, Optional.empty()));

        assertFalse(connection.toString().contains(TOKEN), connection.toString());
        assertTrue(connection.toString().contains("***"));
    }

    @Test
    void takesValuesFromSettings() {
        AiSettings settings = new AiSettings(Protocol.ANTHROPIC, " https://llm.example.invalid ", "m", 100, "", 0, true, false, "", List.of());

        ModelConnection connection = ModelConnection.of(settings, " t ");

        assertEquals(new ModelConnection(Protocol.ANTHROPIC, "https://llm.example.invalid", "m", "t", 100, "",
                new TrustSettings(false, Optional.empty())), connection);
        assertEquals(16, connection.withMaxTokens(16).maxTokens());
    }

    private static String endpoint(Protocol protocol, String base) throws ModelException {
        return new ModelConnection(protocol, base, "m", "", 0, "", new TrustSettings(true, Optional.empty())).endpoint().toString();
    }
}
