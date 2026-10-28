package io.github.hansglorias.edtai.core.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.llm.FakeModelServer;

/**
 * MCP-сервер для тестов поверх {@link FakeModelServer}: Streamable HTTP,
 * {@code initialize} с {@code Mcp-Session-Id}, {@code tools/list} двумя
 * страницами, {@code tools/call} по таблице ответов. Отвечает JSON или
 * потоком SSE.
 */
final class FakeMcpServer implements AutoCloseable {

    static final String SESSION = "session-1";

    final FakeModelServer http;
    /** Методы JSON-RPC по порядку прихода. */
    final List<String> methods = new CopyOnWriteArrayList<>();
    final List<Map<String, Object>> tools = new ArrayList<>();
    /** Ответ {@code tools/call} по имени инструмента: аргументы → result. */
    final Map<String, Function<Map<String, Object>, Object>> results = new ConcurrentHashMap<>();
    volatile boolean sse;
    /** Следующий запрос в сессии получит 404, как будто сервер перезапустили. */
    volatile boolean expireSession;
    private volatile int sessions;

    FakeMcpServer() throws IOException {
        http = FakeModelServer.http().handler(this::handle);
    }

    String url() {
        return http.url() + "/mcp";
    }

    void tool(String name, String description, Function<Map<String, Object>, Object> result) {
        tools.add(Json.object("name", name, "description", description, "inputSchema",
                Json.object("type", "object", "properties", Json.object("name", Json.object("type", "string")))));
        results.put(name, result);
    }

    /** Результат вызова: текст Markdown во встроенном ресурсе, как у EDT-MCP. */
    static Object resource(String markdown) {
        return Json.object("content", List.of(Json.object("type", "resource", "resource",
                Json.object("uri", "edt://result", "mimeType", "text/markdown", "text", markdown))));
    }

    private void handle(FakeModelServer.Request request, FakeModelServer.Response response) throws Exception {
        if (!request.path().equals("/mcp")) {
            response.send(404, "text/plain", "Not Found");
            return;
        }
        if (request.method().equals("DELETE")) {
            response.send(200, "text/plain", "");
            return;
        }
        Map<String, Object> message = Json.parseObject(request.body());
        String method = Json.stringAt(message, "method");
        methods.add(method);
        Object id = message.get("id");
        if (method.startsWith("notifications/")) {
            response.send(202, "text/plain", "");
            return;
        }
        if (method.equals("initialize")) {
            sessions++;
            reply(response, id, Json.object("protocolVersion", "2025-06-18", "capabilities", Json.object("tools", Json.object()),
                    "serverInfo", Json.object("name", "Fake MCP", "version", "1.0")), Map.of("Mcp-Session-Id", SESSION + sessions));
            return;
        }
        String session = request.header("Mcp-Session-Id");
        if (session == null) {
            response.send(400, "text/plain", "Mcp-Session-Id required");
            return;
        }
        if (expireSession || !session.equals(SESSION + sessions)) {
            expireSession = false;
            response.send(404, "text/plain", "unknown session");
            return;
        }
        switch (method) {
            case "tools/list" -> {
                Object cursor = Json.at(message, "params", "cursor");
                int half = (tools.size() + 1) / 2;
                reply(response, id, cursor == null
                        ? Json.object("tools", tools.subList(0, half), "nextCursor", "page2")
                        : Json.object("tools", tools.subList(half, tools.size())), Map.of());
            }
            case "tools/call" -> {
                String name = Json.stringAt(message, "params", "name");
                Function<Map<String, Object>, Object> result = results.get(name);
                if (result == null) {
                    send(response, Json.object("jsonrpc", "2.0", "id", id, "error",
                            Json.object("code", -32602, "message", "Unknown tool: " + name)), Map.of());
                } else {
                    reply(response, id, result.apply(Json.asObject(Json.at(message, "params", "arguments"))), Map.of());
                }
            }
            default -> send(response, Json.object("jsonrpc", "2.0", "id", id, "error",
                    Json.object("code", -32601, "message", "Method not found")), Map.of());
        }
    }

    private void reply(FakeModelServer.Response response, Object id, Object result, Map<String, String> headers) throws IOException {
        send(response, Json.object("jsonrpc", "2.0", "id", id, "result", result), headers);
    }

    private void send(FakeModelServer.Response response, Object message, Map<String, String> headers) throws IOException {
        if (sse) {
            response.start(200, "text/event-stream", headers);
            // Уведомление сервера перед ответом — клиент должен его пропустить.
            response.write("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n");
            response.write("event: message\ndata: " + Json.write(message) + "\n\n");
            response.end();
        } else {
            response.start(200, "application/json", headers);
            response.write(Json.write(message));
            response.end();
        }
    }

    @Override
    public void close() throws IOException {
        http.close();
    }
}
