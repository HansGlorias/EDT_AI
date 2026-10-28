package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.McpServer;
import io.github.hansglorias.edtai.core.settings.McpTransport;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.settings.SettingsStore;

/**
 * MCP, этап 6 (MVP): модель вызывает инструмент MCP-сервера, пользователь
 * разрешает или отклоняет вызов на панели над полем ввода.
 */
class ChatMcpTest extends AbstractChatTest {

    private static final String SESSION = "ui-session";

    private FakeModelServer mcp;
    /** Вызванные на MCP-сервере инструменты. */
    private final List<String> calls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startMcp() throws Exception {
        mcp = FakeModelServer.http().handler(this::mcp);
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("\"role\":\"tool\"")) {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"function\":{\"name\":\"get_edt_version\",\"arguments\":\"{}\"}}]}}]}\n\n", "data: [DONE]\n\n"), 5);
            } else {
                List<Object> messages = Json.asList(Json.parseObject(request.body()).get("messages"));
                String result = Json.stringAt(messages.get(messages.size() - 1), "content");
                response.sse(List.of(openAiPart(result.startsWith("Ошибка") ? "Не дали посмотреть." : "У вас EDT 2026.1."),
                        "data: [DONE]\n\n"), 5);
            }
        });
    }

    @AfterEach
    void stopMcp() throws IOException {
        mcp.close();
    }

    @Test
    void allowedCallGoesToMcpServer() throws Exception {
        ChatView view = configured(mcp.url() + "/mcp");

        view.input().setText("Какая версия EDT?");
        view.send();
        waitUntil(() -> view.approvalBar().isShown(), () -> view.history().getText());

        assertTrue(view.approvalBar().messageText().contains("MCP «EDT»: get_edt_version"), view.approvalBar().messageText());
        assertEquals("{}", view.approvalBar().argumentsText());
        waitUntil(() -> view.history().getText().contains(MessageRenderer.TOOL + "MCP «EDT»: get_edt_version — ждёт разрешения"),
                () -> view.history().getText());
        Screenshots.capture(view.control(), "mcp-approval");

        click(view.approvalBar().allowButton());
        waitUntil(() -> !view.session().isBusy() && view.session().messages().size() == 2);
        view.refresh();

        assertEquals(List.of("get_edt_version"), calls);
        assertFalse(view.approvalBar().isShown());
        String transcript = view.history().getText();
        assertTrue(transcript.contains(MessageRenderer.TOOL + "MCP «EDT»: get_edt_version — Версия EDT\nУ вас EDT 2026.1."), transcript);
        List<Object> messages = Json.asList(Json.parseObject(server.requests().get(1).body()).get("messages"));
        assertEquals("## Версия EDT\n2026.1.0", Json.stringAt(messages.get(messages.size() - 1), "content"));
        Screenshots.capture(view.control(), "mcp-answer");
    }

    @Test
    void deniedCallIsNotMade() throws Exception {
        ChatView view = configured(mcp.url() + "/mcp");

        view.input().setText("Какая версия EDT?");
        view.send();
        waitUntil(() -> view.approvalBar().isShown());
        click(view.approvalBar().denyButton());
        waitUntil(() -> !view.session().isBusy() && view.session().messages().size() == 2);
        view.refresh();

        assertEquals(List.of(), calls);
        assertTrue(view.history().getText().contains("get_edt_version — ошибка: отклонено\nНе дали посмотреть."), view.history().getText());
    }

    @Test
    void allowInChatIsRememberedUntilNewChat() throws Exception {
        ChatView view = configured(mcp.url() + "/mcp");

        view.input().setText("Какая версия?");
        view.send();
        waitUntil(() -> view.approvalBar().isShown());
        click(view.approvalBar().allowInChatButton());
        waitUntil(() -> !view.session().isBusy() && view.session().messages().size() == 2);

        ask(view, "А ещё раз?");
        assertEquals(List.of("get_edt_version", "get_edt_version"), calls, "второй вызов — без вопроса");
        assertFalse(view.approvalBar().isShown());
    }

    @Test
    void stopWhileAskingDismissesQuestion() throws Exception {
        ChatView view = configured(mcp.url() + "/mcp");

        view.input().setText("Какая версия?");
        view.send();
        waitUntil(() -> view.approvalBar().isShown());
        view.session().cancel();
        waitUntil(() -> !view.session().isBusy());
        settle();

        assertFalse(view.approvalBar().isShown());
        assertEquals(List.of(), calls);
        view.refresh();
        assertTrue(view.history().getText().contains("get_edt_version — ошибка: прервано"), view.history().getText());
    }

    @Test
    void unreachableServerDoesNotBreakAnswer() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        answering(List.of("Отвечаю без MCP."));
        ChatView view = configured("http://localhost:" + port + "/mcp");

        ask(view, "Привет");

        String transcript = view.history().getText();
        assertTrue(transcript.contains(MessageRenderer.TOOL + "MCP «EDT» — ошибка: не подключился: Не удалось подключиться"), transcript);
        assertTrue(transcript.contains("Отвечаю без MCP."), transcript);
    }

    private ChatView configured(String mcpUrl) throws Exception {
        SettingsStore.workspace().save(new AiSettings(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", 0, "", 0, true, false, "",
                new ArrayList<>(List.of(new McpServer("EDT", McpTransport.HTTP, mcpUrl, true, "get_edt_version")))));
        return showView();
    }

    private static void click(Button button) {
        button.notifyListeners(SWT.Selection, new Event());
    }

    /** MCP-сервер: initialize, tools/list с одним инструментом, tools/call. */
    private void mcp(FakeModelServer.Request request, FakeModelServer.Response response) throws Exception {
        if (request.method().equals("DELETE")) {
            response.send(200, "text/plain", "");
            return;
        }
        Map<String, Object> message = Json.parseObject(request.body());
        String method = Json.stringAt(message, "method");
        Object id = message.get("id");
        Object result;
        switch (method) {
            case "initialize" -> {
                response.start(200, "application/json", Map.of("Mcp-Session-Id", SESSION));
                response.write(Json.write(Json.object("jsonrpc", "2.0", "id", id, "result", Json.object("protocolVersion", "2025-06-18",
                        "capabilities", Json.object(), "serverInfo", Json.object("name", "EDT MCP Server", "version", "1.0")))));
                response.end();
                return;
            }
            case "notifications/initialized" -> {
                response.send(202, "text/plain", "");
                return;
            }
            case "tools/list" -> result = Json.object("tools", List.of(
                    Json.object("name", "get_edt_version", "description", "Версия 1C:EDT", "inputSchema", Json.object("type", "object")),
                    Json.object("name", "delete_project", "description", "Удаляет проект", "inputSchema", Json.object("type", "object"))));
            case "tools/call" -> {
                calls.add(Json.stringAt(message, "params", "name"));
                result = Json.object("content", List.of(Json.object("type", "resource", "resource",
                        Json.object("uri", "edt://version", "mimeType", "text/markdown", "text", "## Версия EDT\n2026.1.0"))));
            }
            default -> result = Json.object();
        }
        if (!SESSION.equals(request.header("Mcp-Session-Id"))) {
            response.send(400, "text/plain", "no session");
            return;
        }
        response.send(200, "application/json", Json.write(Json.object("jsonrpc", "2.0", "id", id, "result", result)));
    }
}
