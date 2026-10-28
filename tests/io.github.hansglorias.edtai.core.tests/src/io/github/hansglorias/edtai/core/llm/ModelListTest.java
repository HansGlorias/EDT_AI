package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;

class ModelListTest {

    @Test
    void openAiModelsAreListedWithToken() throws Exception {
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> response.send(200, "application/json",
                "{\"object\":\"list\",\"data\":[{\"id\":\"qwen3-32b\"},{\"id\":\"Deepseek-r1\"},{\"id\":\"\"}]}"))) {
            List<String> models = new ModelClient().models(connection(Protocol.OPENAI_COMPATIBLE, server.url() + "/v1", "secret-1"))
                    .get(10, TimeUnit.SECONDS);

            assertEquals(List.of("Deepseek-r1", "qwen3-32b"), models);
            FakeModelServer.Request request = server.requests().get(0);
            assertEquals("GET", request.method());
            assertEquals("/v1/models", request.path());
            assertEquals("Bearer secret-1", request.header("Authorization"));
        }
    }

    @Test
    void anthropicHeadersAndErrors() throws Exception {
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> response.send(401, "application/json",
                "{\"error\":{\"message\":\"invalid x-api-key\"}}"))) {
            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> new ModelClient().models(connection(Protocol.ANTHROPIC, server.url(), "key-2")).get(10, TimeUnit.SECONDS));

            assertTrue(error.getCause().getMessage().startsWith("Сервер не принял токен (HTTP 401)."), error.getCause().getMessage());
            FakeModelServer.Request request = server.requests().get(0);
            assertEquals("/v1/models", request.path());
            assertEquals("key-2", request.header("x-api-key"));
            assertEquals(AnthropicAdapter.API_VERSION, request.header("anthropic-version"));
        }
    }

    @Test
    void invalidTokenDoesNotLeakIntoError() {
        ExecutionException error = assertThrows(ExecutionException.class, () -> new ModelClient()
                .models(connection(Protocol.OPENAI_COMPATIBLE, "http://localhost:1", "токен-кириллицей")).get(10, TimeUnit.SECONDS));
        assertFalse(error.getCause().getMessage().contains("токен-кириллицей"), error.getCause().getMessage());
    }

    private static ModelConnection connection(Protocol protocol, String url, String token) {
        return new ModelConnection(protocol, url, "m", token, 0, "", new TrustSettings(false, Optional.empty()));
    }
}
