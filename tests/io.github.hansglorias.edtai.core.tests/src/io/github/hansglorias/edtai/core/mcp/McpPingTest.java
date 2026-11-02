package io.github.hansglorias.edtai.core.mcp;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.llm.FakeModelServer;

class McpPingTest {
    @Test void pingUsesReadOnlyFallbackOnlyForMethodNotFound() throws Exception {
        for (Integer code : new Integer[] { null, -32601, -32602 }) {
            try (FakeMcpServer server = new FakeMcpServer();
                    McpClient client = new McpClient(URI.create(server.url()), HttpClient.newHttpClient(), Duration.ofSeconds(2))) {
                server.pingError = code;
                if (Integer.valueOf(-32602).equals(code)) assertThrows(McpException.class, client::ping);
                else client.ping();
                assertEquals(Integer.valueOf(-32601).equals(code), server.methods.contains("tools/list"));
                assertFalse(server.methods.contains("tools/call"));
            }
        }
    }

    @Test void timeoutDoesNotFallBackOrExtendDeadline() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (FakeMcpServer server = new FakeMcpServer();
                McpClient client = new McpClient(URI.create(server.url()), HttpClient.newHttpClient(),
                        Duration.ofMillis(400), Duration.ofSeconds(1))) {
            server.beforePing = () -> {
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            };
            McpException error = assertThrows(McpException.class, client::ping);
            assertTrue(error.getCause() instanceof java.net.http.HttpTimeoutException);
            assertFalse(server.methods.contains("tools/list"));
        } finally { release.countDown(); }
    }

    @Test void deadlineAlsoBoundsBodyAfterHeadersArrived() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
            String method = Json.stringAt(Json.parseObject(request.body()), "method");
            if (method.startsWith("notifications/")) { response.send(202, "text/plain", ""); return; }
            response.start(200, "application/json");
            response.write("{"); // Заголовки пришли, а JSON завис на середине.
            release.await(3, TimeUnit.SECONDS);
        }); McpClient client = new McpClient(URI.create(server.url()), HttpClient.newHttpClient(), Duration.ofSeconds(1), Duration.ofMillis(600))) {
            long started = System.nanoTime();
            McpException error = assertThrows(McpException.class, client::ping);
            assertTrue(error.getCause() instanceof java.net.http.HttpTimeoutException);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2500);
        } finally { release.countDown(); }
    }
}
