package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;

class ModelClientTest {

    private static final String TOKEN = "sk-test-0123456789-not-a-real-token";
    private static final long TIMEOUT = 15;

    private static final List<ChatMessage> HISTORY = List.of(
            ChatMessage.of(ChatRole.USER, "первый"),
            ChatMessage.of(ChatRole.ERROR, "ошибка не уходит в модель"),
            ChatMessage.of(ChatRole.USER, "второй"),
            ChatMessage.of(ChatRole.ASSISTANT, "ответ"),
            ChatMessage.of(ChatRole.USER, "третий"));

    @TempDir
    Path temp;

    private final List<String> parts = new CopyOnWriteArrayList<>();
    private FakeModelServer server;
    private ModelClient client = new ModelClient();

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void openAiStreamsAnswerByParts() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"При\"}}]}\r\n\r\n",
                // событие разрезано между порциями
                "data: {\"choices\":[{\"del",
                "ta\":{\"content\":\"вет\"}}]}\n\n: комментарий\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"!\"},\"finish_reason\":\"stop\"}]}\n\n",
                "data: [DONE]\n\n"), 20));

        String answer = await(client.stream(connection(Protocol.OPENAI_COMPATIBLE, TOKEN), HISTORY, parts::add));

        assertEquals("Привет!", answer);
        assertEquals(List.of("При", "вет", "!"), parts);
        FakeModelServer.Request request = server.requests().get(0);
        assertEquals("POST", request.method());
        assertEquals("/v1/chat/completions", request.path());
        assertEquals("Bearer " + TOKEN, request.header("Authorization"));
        assertEquals("application/json", request.header("Content-Type"));
        Map<String, Object> body = Json.parseObject(request.body());
        assertEquals("test-model", body.get("model"));
        assertEquals(Boolean.TRUE, body.get("stream"));
        assertFalse(body.containsKey("max_tokens"));
        assertEquals(List.of(
                Map.of("role", "user", "content", "первый\n\nвторой"),
                Map.of("role", "assistant", "content", "ответ"),
                Map.of("role", "user", "content", "третий")), body.get("messages"));
    }

    @Test
    void openAiStreamWithoutDoneEndsWithConnection() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"b\"}}]}"), 0));

        assertEquals("ab", await(client.stream(connection(Protocol.OPENAI_COMPATIBLE, ""), HISTORY, parts::add)));
        assertNull(server.requests().get(0).header("Authorization"));
    }

    @Test
    void openAiPassesMaxTokensWhenSet() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of("data: [DONE]\n\n"), 0));

        await(client.stream(connection(Protocol.OPENAI_COMPATIBLE, "").withMaxTokens(500), HISTORY, parts::add));

        assertEquals(500L, Json.parseObject(server.requests().get(0).body()).get("max_tokens"));
    }

    @Test
    void anthropicStreamsAnswerByParts() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\"}}\n\n",
                "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n",
                "event: ping\ndata: {\"type\":\"ping\"}\n\n",
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Здрав\"}}\n\n",
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"ствуйте\"}}\n\n",
                "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
                "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n",
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"), 10));

        String answer = await(client.stream(connection(Protocol.ANTHROPIC, TOKEN), HISTORY, parts::add));

        assertEquals("Здравствуйте", answer);
        assertEquals(List.of("Здрав", "ствуйте"), parts);
        FakeModelServer.Request request = server.requests().get(0);
        assertEquals("/v1/messages", request.path());
        assertEquals(TOKEN, request.header("x-api-key"));
        assertEquals("2023-06-01", request.header("anthropic-version"));
        assertNull(request.header("Authorization"));
        Map<String, Object> body = Json.parseObject(request.body());
        assertEquals(8192L, body.get("max_tokens"));
        assertEquals(Boolean.TRUE, body.get("stream"));
        assertEquals(3, Json.asList(body.get("messages")).size());
    }

    @Test
    void anthropicWithoutTokenSendsNoKey() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"), 0));

        assertEquals("", await(client.stream(connection(Protocol.ANTHROPIC, ""), HISTORY, parts::add)));
        assertNull(server.requests().get(0).header("x-api-key"));
    }

    @Test
    void acceptsResponseWithoutStreaming() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.send(200, "application/json",
                request.path().endsWith("/messages")
                        ? "{\"content\":[{\"type\":\"text\",\"text\":\"раз \"},{\"type\":\"text\",\"text\":\"два\"}]}"
                        : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"целиком\"}}]}"));

        assertEquals("целиком", await(client.stream(connection(Protocol.OPENAI_COMPATIBLE, ""), HISTORY, parts::add)));
        assertEquals(List.of("целиком"), parts);
        assertEquals("раз два", await(client.stream(connection(Protocol.ANTHROPIC, ""), HISTORY, part -> { })));
    }

    @Test
    void errorInsideStreamFailsRequest() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(request.path().endsWith("/messages")
                ? List.of("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n")
                : List.of("data: {\"choices\":[{\"delta\":{\"content\":\"на\"}}]}\n\n",
                        "data: {\"error\":{\"message\":\"model crashed\"}}\n\n"), 0));

        ModelException openAi = failure(client.stream(connection(Protocol.OPENAI_COMPATIBLE, ""), HISTORY, parts::add));
        ModelException anthropic = failure(client.stream(connection(Protocol.ANTHROPIC, ""), HISTORY, part -> { }));

        assertTrue(openAi.getMessage().contains("model crashed"), openAi.getMessage());
        assertEquals(List.of("на"), parts);
        assertTrue(anthropic.getMessage().contains("Overloaded"), anthropic.getMessage());
    }

    @Test
    void httpErrorsAreExplainedWithoutToken() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            switch (request.header("Authorization").substring("Bearer ".length())) {
                case TOKEN -> response.send(401, "application/json",
                        "{\"error\":{\"message\":\"Incorrect API key provided: " + TOKEN + "\"}}");
                case "html" -> response.send(404, "text/html", "<html><head><title>404 Not Found</title></head><body>nginx</body></html>");
                case "text" -> response.send(503, "text/plain", "upstream unavailable");
                default -> response.send(429, "application/json", "{\"detail\":\"rate limited\"}");
            }
        });

        ModelException unauthorized = failure(client.stream(connection(Protocol.OPENAI_COMPATIBLE, TOKEN), HISTORY, parts::add));
        ModelException notFound = failure(client.stream(connection(Protocol.OPENAI_COMPATIBLE, "html"), HISTORY, parts::add));
        ModelException unavailable = failure(client.stream(connection(Protocol.OPENAI_COMPATIBLE, "text"), HISTORY, parts::add));
        ModelException limited = failure(client.stream(connection(Protocol.OPENAI_COMPATIBLE, "other"), HISTORY, parts::add));

        assertTrue(unauthorized.getMessage().contains("HTTP 401"), unauthorized.getMessage());
        assertTrue(unauthorized.getMessage().contains("токен"), unauthorized.getMessage());
        assertTrue(unauthorized.getMessage().contains("Incorrect API key provided: ***"), unauthorized.getMessage());
        assertFalse(unauthorized.getMessage().contains(TOKEN));
        assertTrue(notFound.getMessage().contains("HTTP 404"), notFound.getMessage());
        assertTrue(notFound.getMessage().contains(server.url() + "/v1/chat/completions"), notFound.getMessage());
        assertTrue(notFound.getMessage().contains("«404 Not Found»"), notFound.getMessage());
        assertTrue(unavailable.getMessage().contains("HTTP 503") && unavailable.getMessage().contains("upstream unavailable"),
                unavailable.getMessage());
        assertTrue(limited.getMessage().contains("HTTP 429") && limited.getMessage().contains("rate limited"), limited.getMessage());
        assertTrue(parts.isEmpty());
    }

    @Test
    void cancelStopsStreaming() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            response.start(200, "text/event-stream");
            try {
                for (int i = 0; i < 1000; i++) {
                    response.write("data: {\"choices\":[{\"delta\":{\"content\":\"" + i + " \"}}]}\n\n");
                    Thread.sleep(20);
                }
            } finally {
                closed.countDown();
            }
        });

        CompletableFuture<String> reply = client.stream(connection(Protocol.OPENAI_COMPATIBLE, ""), HISTORY, parts::add);
        waitFor(() -> parts.size() >= 3);
        reply.cancel(true);

        assertTrue(reply.isCancelled());
        // Сервер замечает, что клиент закрыл соединение, на очередной записи.
        assertTrue(closed.await(TIMEOUT, TimeUnit.SECONDS));
        int received = parts.size();
        Thread.sleep(200);
        assertEquals(received, parts.size());
    }

    @Test
    void cancelBeforeResponseHeaders() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            started.countDown();
            Thread.sleep(30_000);
        });

        CompletableFuture<String> reply = client.stream(connection(Protocol.ANTHROPIC, ""), HISTORY, parts::add);
        assertTrue(started.await(TIMEOUT, TimeUnit.SECONDS));
        reply.cancel(true);

        assertTrue(reply.isCancelled());
    }

    @Test
    void silentServerTimesOutWhileStreaming() throws Exception {
        client = new ModelClient(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(1));
        server = FakeModelServer.http().handler((request, response) -> {
            response.start(200, "text/event-stream");
            response.write("data: {\"choices\":[{\"delta\":{\"content\":\"начал\"}}]}\n\n");
            Thread.sleep(30_000);
        });

        ModelException error = failure(client.stream(connection(Protocol.OPENAI_COMPATIBLE, ""), HISTORY, parts::add));

        assertTrue(error.getMessage().contains("перестал присылать ответ"), error.getMessage());
        assertTrue(error.getMessage().contains("1 с"), error.getMessage());
        assertEquals(List.of("начал"), parts);
    }

    @Test
    void serverThatDoesNotAnswerTimesOut() throws Exception {
        client = new ModelClient(Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(5));
        server = FakeModelServer.http().handler((request, response) -> Thread.sleep(30_000));

        ModelException error = failure(client.stream(connection(Protocol.OPENAI_COMPATIBLE, ""), HISTORY, parts::add));

        assertTrue(error.getMessage().contains("не начал отвечать за 1 с"), error.getMessage());
    }

    @Test
    void refusedConnectionIsExplained() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }

        ModelException error = failure(client.stream(connection(Protocol.OPENAI_COMPATIBLE, "http://localhost:" + port, "",
                trust(false)), HISTORY, parts::add));

        assertTrue(error.getMessage().contains("соединение отклонено"), error.getMessage());
        assertTrue(error.getMessage().contains("localhost:" + port), error.getMessage());
    }

    @Test
    void httpsWithCorporateCaFromPemFile() throws Exception {
        server = FakeModelServer.https("localhost.p12").handler((request, response) -> response.sse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"tls ok\"}}]}\n\n", "data: [DONE]\n\n"), 0));

        String answer = await(client.stream(connection(Protocol.OPENAI_COMPATIBLE, server.url(), TOKEN, trust(true)), HISTORY, parts::add));

        assertEquals("tls ok", answer);
    }

    @Test
    void untrustedCertificateIsExplained() throws Exception {
        server = FakeModelServer.https("localhost.p12");

        ModelException error = failure(client.stream(connection(Protocol.OPENAI_COMPATIBLE, server.url(), TOKEN, trust(false)),
                HISTORY, parts::add));

        assertTrue(error.getMessage().contains("нет доверия"), error.getMessage());
        assertTrue(error.getMessage().contains("PEM"), error.getMessage());
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void certificateForAnotherHostIsExplained() throws Exception {
        server = FakeModelServer.https("wrong-host.p12");

        ModelException error = failure(client.stream(connection(Protocol.OPENAI_COMPATIBLE, server.url(), "", trust(true)),
                HISTORY, parts::add));

        assertTrue(error.getMessage().contains("выдан не на имя localhost"), error.getMessage());
    }

    @Test
    void expiredCertificateIsExplained() throws Exception {
        server = FakeModelServer.https("expired.p12");

        ModelException error = failure(client.stream(connection(Protocol.OPENAI_COMPATIBLE, server.url(), "", trust(true)),
                HISTORY, parts::add));

        assertTrue(error.getMessage().contains("просрочен"), error.getMessage());
    }

    @Test
    void httpsToPlainHttpServerIsExplained() throws Exception {
        server = FakeModelServer.http();

        ModelException error = failure(client.stream(connection(Protocol.OPENAI_COMPATIBLE,
                server.url().replace("http:", "https:"), "", trust(false)), HISTORY, parts::add));

        assertTrue(error.getMessage().contains("не отвечает по HTTPS"), error.getMessage());
    }

    @Test
    void unreadableCaFileIsExplained() throws Exception {
        ModelConnection connection = new ModelConnection(Protocol.OPENAI_COMPATIBLE, "https://localhost:1", "m", "", 0,
                new TrustSettings(false, Optional.of(temp.resolve("missing.pem"))));

        ModelException error = failure(client.stream(connection, HISTORY, parts::add));

        assertTrue(error.getMessage().contains("Не удалось подготовить сертификаты"), error.getMessage());
        assertTrue(error.getMessage().contains("missing.pem"), error.getMessage());
    }

    @Test
    void missingModelIsReportedBeforeRequest() throws Exception {
        server = FakeModelServer.http();
        ModelConnection connection = new ModelConnection(Protocol.ANTHROPIC, server.url(), "", "", 0, trust(false));

        ModelException error = failure(client.stream(connection, HISTORY, parts::add));

        assertTrue(error.getMessage().contains("Не задана модель"), error.getMessage());
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void checkSendsShortRequest() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Готов\"}}]}\n\n", "data: [DONE]\n\n"), 0));

        String result = client.check(connection(Protocol.OPENAI_COMPATIBLE, TOKEN)).get(TIMEOUT, TimeUnit.SECONDS);

        assertTrue(result.startsWith("Подключение работает: модель test-model ответила за"), result);
        assertTrue(result.endsWith("«Готов»."), result);
        assertEquals(16L, Json.parseObject(server.requests().get(0).body()).get("max_tokens"));
    }

    private ModelConnection connection(Protocol protocol, String token) {
        return connection(protocol, server.url(), token, new TrustSettings(false, Optional.empty()));
    }

    private static ModelConnection connection(Protocol protocol, String url, String token, TrustSettings trust) {
        return new ModelConnection(protocol, url, "test-model", token, 0, trust);
    }

    /** @param testCa доверять тестовому CA, которым выпущены сертификаты фейкового сервера */
    private TrustSettings trust(boolean testCa) throws IOException {
        if (!testCa) {
            return new TrustSettings(false, Optional.empty());
        }
        Path pem = temp.resolve("server-ca.pem");
        try (InputStream in = getClass().getResourceAsStream("server-ca.pem")) {
            Files.writeString(pem, new String(in.readAllBytes(), StandardCharsets.US_ASCII));
        }
        return new TrustSettings(false, Optional.of(pem));
    }

    private static String await(CompletableFuture<String> future) throws Exception {
        try {
            return future.get(TIMEOUT, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("Запрос упал: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private static ModelException failure(CompletableFuture<String> future) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(TIMEOUT, TimeUnit.SECONDS));
        return assertInstanceOf(ModelException.class, error.getCause());
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT * 1000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Не дождались условия");
            }
            Thread.sleep(10);
        }
    }
}
