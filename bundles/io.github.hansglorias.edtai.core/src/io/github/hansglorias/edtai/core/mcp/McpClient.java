package io.github.hansglorias.edtai.core.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.json.JsonException;
import io.github.hansglorias.edtai.core.llm.SseEvent;
import io.github.hansglorias.edtai.core.llm.SseParser;

/**
 * Клиент MCP по Streamable HTTP: JSON-RPC 2.0 запросами POST, ответ —
 * JSON или поток SSE. Сессия — заголовок {@code Mcp-Session-Id}: если сервер
 * её забыл (HTTP 404), клиент подключается заново и повторяет запрос.
 * Вызовы последовательные: один запрос к серверу за раз; {@link #close()}
 * не ждёт текущего вызова.
 */
public final class McpClient implements AutoCloseable {

    /** Версия протокола, которую предлагаем; сервер может ответить своей. */
    public static final String PROTOCOL_VERSION = "2025-06-18";
    static final String CLIENT_NAME = "edt-ai";
    private static final int MAX_ERROR_TEXT = 300;

    private final URI uri;
    private final HttpClient http;
    private final Duration timeout;
    private final long deadline;
    private final ScheduledFuture<?> deadlineTask;
    private volatile InputStream activeBody;
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();

    private long nextId = 1;
    private volatile String sessionId;
    private volatile String protocolVersion;
    private String serverName = "";
    private String serverTitle = "";
    private volatile boolean initialized;

    /** @param timeout сколько ждать ответа на один запрос: вызов инструмента может работать долго */
    public McpClient(URI uri, HttpClient http, Duration timeout) {
        this(uri, http, timeout, null);
    }

    McpClient(URI uri, HttpClient http, Duration timeout, Duration totalTimeout) {
        this.uri = uri;
        this.http = http;
        this.timeout = timeout;
        deadline = totalTimeout == null ? Long.MAX_VALUE : System.nanoTime() + totalTimeout.toNanos();
        deadlineTask = totalTimeout == null ? null : DEADLINES.schedule(() -> {
            InputStream body = activeBody;
            if (body != null) try { body.close(); } catch (IOException ignored) { }
        }, totalTimeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    private static ScheduledThreadPoolExecutor deadlines() {
        var timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "EDT AI: таймаут чтения MCP"); thread.setDaemon(true); return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        timer.setKeepAliveTime(1, TimeUnit.SECONDS);
        timer.allowCoreThreadTimeOut(true);
        return timer;
    }

    /** Имя и версия сервера из ответа на {@code initialize}, например «EDT MCP Server 1.27.0». */
    public synchronized String serverName() {
        return serverName;
    }

    /** Имя из serverInfo без версии: для автоматического заполнения настроек. */
    public synchronized String serverTitle() {
        return serverTitle;
    }

    /** Подключается, если ещё не подключён. */
    public synchronized void initialize() throws McpException {
        if (initialized) {
            return;
        }
        sessionId = null;
        protocolVersion = null;
        Object result = send("initialize", Json.object("protocolVersion", PROTOCOL_VERSION, "capabilities", Json.object(),
                "clientInfo", Json.object("name", CLIENT_NAME, "version", "0.1.0")), false);
        protocolVersion = Json.stringAt(result, "protocolVersion");
        String name = Json.stringAt(result, "serverInfo", "name");
        serverTitle = name == null ? "" : name.strip();
        String version = Json.stringAt(result, "serverInfo", "version");
        serverName = name == null ? "" : version == null ? name : name + " " + version;
        initialized = true;
        notifyServer("notifications/initialized");
    }

    /** Все инструменты сервера, со всех страниц списка. */
    public synchronized List<McpToolInfo> listTools() throws McpException {
        List<McpToolInfo> tools = new ArrayList<>();
        String cursor = null;
        do {
            Object result = request("tools/list", cursor == null ? Json.object() : Json.object("cursor", cursor));
            for (Object tool : Json.asList(Json.at(result, "tools"))) {
                String name = Json.stringAt(tool, "name");
                if (name != null) {
                    tools.add(new McpToolInfo(name, Json.stringAt(tool, "description"), Json.asObject(Json.at(tool, "inputSchema")),
                            Json.at(tool, "annotations", "readOnlyHint") instanceof Boolean readOnly ? readOnly : null));
                }
            }
            cursor = Json.stringAt(result, "nextCursor");
        } while (cursor != null && !cursor.isEmpty());
        return tools;
    }

    /**
     * Вызывает инструмент.
     *
     * @return текст результата; {@link McpCallResult#error()} — инструмент сообщил об ошибке
     */
    public synchronized McpCallResult callTool(String name, Map<String, Object> arguments) throws McpException {
        return McpCallResult.of(request("tools/call", Json.object("name", name, "arguments", arguments)));
    }

    /** Только транспорт MCP. Запасной read-only запрос разрешён лишь при JSON-RPC -32601. */
    public synchronized void ping() throws McpException {
        try {
            request("ping", Json.object());
        } catch (McpException e) {
            if (!e.methodNotFound()) throw e;
            request("tools/list", Json.object()); // Одной страницы достаточно для проверки ответа MCP.
        }
    }

    /**
     * Завершает сессию на сервере, если она есть. Ошибки не важны. Не ждёт
     * вызова, который сейчас выполняется: его ответ сервер пришлёт уже в
     * закрытую сессию.
     */
    @Override
    public void close() {
        if (deadlineTask != null) deadlineTask.cancel(false);
        String session = sessionId;
        sessionId = null;
        initialized = false;
        if (session != null) {
            try {
                HttpRequest.Builder request = builder().timeout(Duration.ofSeconds(2)).header("Mcp-Session-Id", session).DELETE();
                http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            } catch (IOException | McpException e) {
                // Сервер мог уже закрыться.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- JSON-RPC ----

    /** Запрос в сессии; если сессия устарела — подключается заново и повторяет один раз. */
    private Object request(String method, Object params) throws McpException {
        initialize();
        try {
            return send(method, params, true);
        } catch (SessionExpired e) {
            initialized = false;
            initialize();
            return send(method, params, false);
        }
    }

    private Object send(String method, Object params, boolean mayExpire) throws McpException {
        long id = nextId++;
        HttpResponse<InputStream> response = post(Json.object("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 404 && mayExpire && sessionId != null) {
                throw new SessionExpired();
            }
            if (status / 100 != 2) {
                throw new McpException(httpError(status, new String(body.readNBytes(64 * 1024), StandardCharsets.UTF_8)));
            }
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> sessionId = value);
            String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            Object message = type.contains("text/event-stream") ? fromStream(body, id)
                    : find(Json.parse(new String(body.readAllBytes(), StandardCharsets.UTF_8)), id);
            if (message == null) {
                throw new McpException("Сервер не ответил на запрос " + method);
            }
            Object error = Json.at(message, "error");
            if (error != null) {
                String text = Json.stringAt(error, "message");
                Object code = Json.at(error, "code");
                throw new McpException("Сервер вернул ошибку на " + method + ": " + (text != null ? text : Json.write(error)),
                        null, code instanceof Number number ? number.intValue() : null);
            }
            return Json.at(message, "result");
        } catch (IOException e) {
            if (expired()) throw deadlineError();
            throw new McpException("Обрыв ответа MCP-сервера " + uri + ": " + e.getMessage(), e);
        } catch (JsonException e) {
            throw new McpException("MCP-сервер прислал не JSON: " + e.getMessage(), e);
        }
    }

    private void notifyServer(String method) throws McpException {
        HttpResponse<InputStream> response = post(Json.object("jsonrpc", "2.0", "method", method));
        try (InputStream body = response.body()) {
            body.readNBytes(64 * 1024);
        } catch (IOException e) {
            // На уведомление сервер отвечает 202 без тела; содержимое не важно.
        }
    }

    private HttpResponse<InputStream> post(Map<String, Object> message) throws McpException {
        HttpRequest request = builder()
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(message), StandardCharsets.UTF_8))
                .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            activeBody = response.body();
            if (expired()) {
                response.body().close();
                throw deadlineError();
            }
            return response;
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw new McpException("Не удалось подключиться к MCP-серверу " + uri + ": сервер не запущен или порт другой.", e);
        } catch (HttpTimeoutException e) {
            throw new McpException("MCP-сервер " + uri + " не ответил за " + timeout.toSeconds() + " с.", e);
        } catch (IOException e) {
            throw new McpException("Ошибка связи с MCP-сервером " + uri + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Запрос к MCP-серверу прерван", e);
        }
    }

    private boolean expired() { return deadline != Long.MAX_VALUE && System.nanoTime() >= deadline; }
    private McpException deadlineError() { return new McpException("Истекло время проверки MCP", new HttpTimeoutException("MCP deadline")); }

    private HttpRequest.Builder builder() throws McpException {
        long remaining = deadline == Long.MAX_VALUE ? timeout.toNanos() : deadline - System.nanoTime();
        if (remaining <= 0) throw new McpException("Истекло время проверки MCP", new HttpTimeoutException("MCP deadline"));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(Math.min(timeout.toNanos(), remaining)))
                .header("User-Agent", "EDT-AI");
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }
        return builder;
    }

    /** Ответ с нашим id из потока SSE; уведомления и запросы сервера пропускаем. */
    private static Object fromStream(InputStream body, long id) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        SseParser parser = new SseParser();
        String line;
        while ((line = reader.readLine()) != null) {
            SseEvent event = parser.line(line);
            if (event != null && !event.data().isBlank()) {
                Object found = find(Json.parse(event.data()), id);
                if (found != null) {
                    return found;
                }
            }
        }
        SseEvent last = parser.finish();
        return last == null || last.data().isBlank() ? null : find(Json.parse(last.data()), id);
    }

    /** Сообщение-ответ с этим id: сам объект или элемент пакета. */
    private static Object find(Object json, long id) {
        if (json instanceof List<?> batch) {
            for (Object item : batch) {
                Object found = find(item, id);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        Object value = Json.at(json, "id");
        boolean response = Json.at(json, "result") != null || Json.at(json, "error") != null;
        return response && value instanceof Number number && number.longValue() == id ? json : null;
    }

    private String httpError(int status, String body) {
        String text = body.strip().replaceAll("\\s+", " ");
        if (text.length() > MAX_ERROR_TEXT) {
            text = text.substring(0, MAX_ERROR_TEXT) + "…";
        }
        String hint = switch (status) {
            case 401, 403 -> " Сервер требует токен или не принимает этот.";
            case 404 -> " Проверьте адрес: обычно он заканчивается на /mcp.";
            default -> "";
        };
        return "MCP-сервер " + uri + " ответил HTTP " + status + "." + hint + (text.isEmpty() ? "" : " Ответ: «" + text + "»");
    }

    /** Сервер не знает нашу сессию. */
    private static final class SessionExpired extends McpException {
        private static final long serialVersionUID = 1L;

        SessionExpired() {
            super("Сессия MCP истекла");
        }
    }

    /** Для тестов: заголовки, с которыми уходит следующий запрос. */
    synchronized Map<String, String> sessionHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (sessionId != null) {
            headers.put("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null) {
            headers.put("MCP-Protocol-Version", protocolVersion);
        }
        return headers;
    }
}
