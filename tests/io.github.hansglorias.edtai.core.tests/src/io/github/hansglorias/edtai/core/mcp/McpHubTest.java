package io.github.hansglorias.edtai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.llm.FakeModelServer;
import io.github.hansglorias.edtai.core.settings.McpServer;
import io.github.hansglorias.edtai.core.settings.McpTransport;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolException;

class McpHubTest {

    private static final TrustSettings TRUST = new TrustSettings(false, Optional.empty());

    private final List<ToolUse> problems = new CopyOnWriteArrayList<>();
    private final McpHub hub = new McpHub();
    private FakeMcpServer server;
    private int limit = 10_000;

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeMcpServer();
        server.tool("get_edt_version", "Версия EDT", arguments -> FakeMcpServer.resource("## EDT\n2026.1.0"));
        server.tool("read_module_source", "Текст модуля", arguments -> Json.object("content",
                List.of(Json.object("type", "text", "text", "Модуль " + arguments.get("name")))));
        server.tool("broken", "Всегда ошибка", arguments -> Json.object("isError", true, "content",
                List.of(Json.object("type", "text", "text", "{\"success\": false, \"error\": \"нет проекта\"}"))));
    }

    @AfterEach
    void stop() throws IOException {
        hub.close();
        server.close();
    }

    @Test
    void connectsListsAndCallsTools() throws Exception {
        Map<String, Tool> tools = tools(edt(""));

        assertEquals(Set.of("get_edt_version", "read_module_source", "broken"), tools.keySet());
        assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/list"), server.methods);
        Tool read = tools.get("read_module_source");
        assertTrue(read.needsApproval());
        assertEquals("Текст модуля\n(инструмент MCP-сервера «EDT»)", read.description());
        assertEquals("object", read.inputSchema().get("type"));
        assertEquals("MCP «EDT»: read_module_source (ОбщийМодуль.Х)", read.summary(Map.of("name", "ОбщийМодуль.Х")));

        assertEquals(new Tool.Result("Модуль ОбщийМодуль.Х", "Модуль ОбщийМодуль.Х"), read.call(Map.of("name", "ОбщийМодуль.Х")));
        assertEquals(new Tool.Result("## EDT\n2026.1.0", "EDT"), tools.get("get_edt_version").call(Map.of()));
        FakeModelServer.Request call = server.http.requests().get(server.http.requests().size() - 1);
        assertEquals(FakeMcpServer.SESSION + "1", call.header("Mcp-Session-Id"));
        assertEquals("2025-06-18", call.header("MCP-Protocol-Version"));
        assertTrue(call.header("Accept").contains("text/event-stream"));
        ToolException error = assertThrows(ToolException.class, () -> tools.get("broken").call(Map.of()));
        assertEquals("{\"success\": false, \"error\": \"нет проекта\"}", error.getMessage());
        assertEquals(List.of(), problems);

        // Второй ответ модели — без нового подключения.
        tools(edt(""));
        assertEquals(1, server.methods.stream().filter("initialize"::equals).count());
    }

    @Test
    void streamedResponsesAndExpiredSession() throws Exception {
        server.sse = true;
        Map<String, Tool> tools = tools(edt(""));
        assertEquals(3, tools.size());

        server.expireSession = true;
        assertEquals("2026.1.0", tools.get("get_edt_version").call(Map.of()).text().lines().skip(1).findFirst().orElseThrow());
        assertEquals(2, server.methods.stream().filter("initialize"::equals).count(), "переподключились после 404");
    }

    @Test
    void filterAndNameCollisions() {
        Map<String, Tool> tools = tools(edt("read_*, get_edt_version"));
        assertEquals(Set.of("get_edt_version", "read_module_source"), tools.keySet());

        List<Tool> second = hub.tools(List.of(edt("get_*"), new McpServer("Второй EDT", McpTransport.HTTP, server.url(), true, "get_*")),
                TRUST, Set.of("read_module"), () -> limit, problems::add);
        assertEquals(List.of("get_edt_version", "EDT__get_edt_version"), second.stream().map(Tool::name).toList());
        assertEquals("mcp__x", McpHub.exposedName(new McpServer("Рабочий", McpTransport.HTTP, "", true), "x", Set.of("x")));
        assertEquals("mcp__x_2", McpHub.exposedName(new McpServer("Рабочий", McpTransport.HTTP, "", true), "x", Set.of("x", "mcp__x")));
        assertEquals("get_module", McpHub.exposedName(edt(""), "get.module", Set.of()));
    }

    @Test
    void longResultIsCut() throws Exception {
        server.tool("big", "Много текста", arguments -> Json.object("content", List.of(Json.object("type", "text", "text", "а".repeat(50)))));
        limit = 10;
        Tool.Result result = tools(edt("")).get("big").call(Map.of());
        assertEquals("а".repeat(10) + "\n(результат обрезан по лимиту контекста: 10 из 50 символов)", result.text());
    }

    @Test
    void unreachableAndUnsupportedServersAreReportedNotThrown() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        List<Tool> tools = hub.tools(List.of(
                new McpServer("Нет", McpTransport.HTTP, "http://localhost:" + port + "/mcp", true),
                new McpServer("Выкл", McpTransport.HTTP, "http://localhost:" + port + "/mcp", false),
                new McpServer("Стдио", McpTransport.STDIO, "python3 server.py", true),
                edt("get_*")), TRUST, Set.of(), () -> limit, problems::add);

        assertEquals(List.of("get_edt_version"), tools.stream().map(Tool::name).toList());
        assertEquals(2, problems.size());
        assertEquals(ToolUse.Status.FAILED, problems.get(0).status());
        assertTrue(problems.get(0).label().startsWith("MCP «Нет» — ошибка: не подключился: Не удалось подключиться к MCP-серверу"),
                problems.get(0).label());
        assertTrue(problems.get(1).outcome().contains("stdio пока не поддерживается"));
    }

    @Test
    void checkDescribesServer() throws Exception {
        assertEquals("Подключено: Fake MCP 1.0. Инструментов: 3.", hub.check(edt(""), TRUST));
        assertEquals("Подключено: Fake MCP 1.0. Инструментов: 3, модели доступно по фильтру: 1.", hub.check(edt("broken"), TRUST));
        for (int i = 0; i < McpHub.MANY_TOOLS; i++) {
            server.tool("tool_" + i, "описание", arguments -> FakeMcpServer.resource("ok"));
        }
        assertTrue(hub.check(edt(""), TRUST).contains("лучше перечислить нужные инструменты"));

        McpException wrongPath = assertThrows(McpException.class,
                () -> hub.check(new McpServer("EDT", McpTransport.HTTP, server.http.url() + "/other", true), TRUST));
        assertTrue(wrongPath.getMessage().contains("ответил HTTP 4"), wrongPath.getMessage());
    }

    @Test
    void callResultParts() {
        McpCallResult result = McpCallResult.of(Json.object("content", List.of(
                Json.object("type", "text", "text", "текст"),
                Json.object("type", "image", "mimeType", "image/png", "data", "AAAA"),
                Json.object("type", "resource", "resource", Json.object("uri", "file:///a.bin", "mimeType", "application/zip", "blob", "AA")),
                Json.object("type", "resource_link", "uri", "edt://x"))));

        assertEquals("текст\n\n[image image/png — не передаётся]\n\n[двоичный ресурс file:///a.bin, application/zip — не передаётся]"
                + "\n\n[ресурс edt://x]", result.text());
        assertFalse(result.error());
        assertEquals("{\"ok\":true}", McpCallResult.of(Json.object("content", List.of(), "structuredContent", Json.object("ok", true))).text());
    }

    @Test
    void loopbackDetection() {
        assertTrue(McpHub.isLoopback("localhost"));
        assertTrue(McpHub.isLoopback("127.0.0.1"));
        assertTrue(McpHub.isLoopback("[::1]"));
        assertFalse(McpHub.isLoopback("mcp.example.invalid"));
    }

    private McpServer edt(String filter) {
        return new McpServer("EDT", McpTransport.HTTP, server.url(), true, filter);
    }

    private Map<String, Tool> tools(McpServer config) {
        return hub.tools(List.of(config), TRUST, Set.of(), () -> limit, problems::add).stream()
                .collect(Collectors.toMap(Tool::name, tool -> tool));
    }
}
