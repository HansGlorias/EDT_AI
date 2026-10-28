package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class ModelErrorsTest {

    private static final URI URI_ = URI.create("https://llm.example.invalid:8443/v1/messages?key=secret");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Test
    void contextOverflowIsExplained() {
        for (String body : List.of(
                "{\"error\":{\"message\":\"This model's maximum context length is 32768 tokens. However, you requested 40000 tokens"
                        + " (36000 in the messages, 4000 in the completion).\",\"code\":\"context_length_exceeded\"}}",
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"prompt is too long: 210000 tokens\"}}",
                "{\"error\":{\"message\":\"the request exceeds the available context size, try increasing it\","
                        + "\"type\":\"exceed_context_size_error\",\"tools\":1}}")) {
            ModelException error = ModelErrors.http(400, body, URI_);
            assertTrue(error.getMessage().startsWith("Диалог не помещается в окно модели (HTTP 400)."), error.getMessage());
            assertFalse(error.rejectsTools(), "переполнение — не отказ от инструментов");
        }
    }

    @Test
    void unknownHost() {
        ConnectException error = new ConnectException();
        error.initCause(new UnresolvedAddressException());

        assertEquals("Не найден сервер llm.example.invalid. Проверьте адрес в настройках EDT AI.", of(error).getMessage());
    }

    @Test
    void connectTimeout() {
        String message = of(new HttpConnectTimeoutException("HTTP connect timed out")).getMessage();

        assertTrue(message.startsWith("Не удалось подключиться к llm.example.invalid:8443: нет ответа за 20 с"), message);
    }

    @Test
    void connectionDropped() {
        String message = of(new IOException("Connection reset")).getMessage();

        assertEquals("Соединение с сервером llm.example.invalid:8443 прервано: Connection reset", message);
    }

    @Test
    void modelExceptionIsKept() {
        ModelException error = new ModelException("готовое сообщение");

        assertSame(error, of(error));
    }

    @Test
    void addressInMessagesHasNoQuery() {
        assertEquals("https://llm.example.invalid:8443/v1/messages", ModelErrors.shown(URI_));
        assertTrue(ModelErrors.http(404, "", URI_).getMessage().contains("https://llm.example.invalid:8443/v1/messages."));
    }

    @Test
    void serverTextFromDifferentFormats() {
        assertEquals("bad key", ModelErrors.serverText("{\"error\":{\"message\":\"bad key\",\"type\":\"x\"}}"));
        assertEquals("plain error", ModelErrors.serverText("{\"error\":\"plain error\"}"));
        assertEquals("msg", ModelErrors.serverText("{\"message\":\"msg\"}"));
        assertEquals("field required", ModelErrors.serverText("{\"detail\":[{\"loc\":[\"body\"],\"msg\":\"field required\"}]}"));
        assertEquals("502 Bad Gateway", ModelErrors.serverText("<html><title>502 Bad Gateway</title></html>"));
        assertEquals("one line", ModelErrors.serverText("  one\n  line "));
        assertNull(ModelErrors.serverText("<html><body>без заголовка</body></html>"));
        assertNull(ModelErrors.serverText("{\"other\":1}"));
        assertNull(ModelErrors.serverText(""));
        assertEquals(301, ModelErrors.serverText("x".repeat(1000)).length());
    }

    @Test
    void redactsToken() {
        assertEquals("key *** is wrong", ModelErrors.redact("key sk-abcdef is wrong", "sk-abcdef"));
        assertEquals("text", ModelErrors.redact("text", ""));
    }

    private static ModelException of(Throwable error) {
        return ModelErrors.of(error, URI_, TIMEOUT, TIMEOUT, null);
    }
}
