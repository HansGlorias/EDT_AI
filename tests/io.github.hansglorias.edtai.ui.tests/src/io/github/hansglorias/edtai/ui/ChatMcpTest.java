package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.mcp.McpHub;
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
    /** Что делает инструмент на сервере, кроме ответа: например, правит файл, как EDT-MCP. */
    private volatile ThrowingRunnable onCall = () -> { };

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

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
        assertFalse(view.approvalBar().argumentsShown(), "пустые аргументы не показываем");
        assertTrue(view.status().getText().endsWith("ждёт вашего решения"), view.status().getText());
        waitUntil(() -> view.history().getText().contains(MessageRenderer.TOOL + "MCP «EDT»: get_edt_version — ждёт разрешения"),
                () -> view.history().getText());
        Screenshots.capture(view.control(), "mcp-approval");

        click(view.approvalBar().allowButton());
        waitUntil(() -> !view.session().isBusy() && view.session().messages().size() == 2);
        view.refresh();

        assertEquals(List.of("get_edt_version"), calls);
        assertFalse(view.approvalBar().isShown());
        String transcript = withoutTokens(view.history().getText());
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
        assertTrue(withoutTokens(view.history().getText()).contains("get_edt_version — отклонено\nНе дали посмотреть."),
                view.history().getText());
    }

    @Test
    void escapeInInputDeniesCall() throws Exception {
        ChatView view = configured(mcp.url() + "/mcp");

        view.input().setText("Какая версия EDT?");
        view.send();
        waitUntil(() -> view.approvalBar().isShown());
        Event escape = new Event();
        escape.keyCode = SWT.ESC;
        view.onInputKey(escape);
        waitUntil(() -> !view.session().isBusy() && view.session().messages().size() == 2);
        view.refresh();

        assertEquals(List.of(), calls);
        assertTrue(view.history().getText().contains("Не дали посмотреть."), "ответ не остановлен, модель узнала об отказе");
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
    void autoModeStopsAsking() throws Exception {
        ChatView view = configured(mcp.url() + "/mcp");

        view.input().setText("Какая версия?");
        view.send();
        waitUntil(() -> view.approvalBar().isShown());
        click(view.approvalBar().alwaysButton());
        waitUntil(() -> !view.session().isBusy() && view.session().messages().size() == 2);
        waitUntil(() -> view.status().getText().startsWith("Авторежим включён"), () -> view.status().getText());

        view.newChatForTest();
        ask(view, "А ещё раз?");
        assertEquals(List.of("get_edt_version", "get_edt_version"), calls, "в новом чате тоже без вопроса");
        assertFalse(view.approvalBar().isShown());

        view.setAutoApprove(false);
        view.input().setText("И снова?");
        view.send();
        waitUntil(() -> view.approvalBar().isShown());
        click(view.approvalBar().denyButton());
        waitUntil(() -> !view.session().isBusy());
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
        assertTrue(withoutTokens(view.history().getText()).contains("get_edt_version — прервано"), view.history().getText());
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
        assertFalse(transcript.contains("MCP «EDT»"), "в ленте строки о сервере нет: " + transcript);
        assertTrue(transcript.contains("Отвечаю без MCP."), transcript);
        // Сервер не подключился — красный значок на кнопке MCP, подробности в подсказке.
        waitUntil(() -> view.mcpButton().getData() == McpHub.State.FAILED, () -> String.valueOf(view.mcpButton().getData()));
        assertTrue(view.mcpButton().getToolTipText().startsWith("EDT: Не подключён: Не удалось подключиться"),
                view.mcpButton().getToolTipText());
        Screenshots.capture(view.control(), "mcp-indicator");
    }

    @Test
    void mcpEditsAreShownAndRevertedAgainstGit() throws Exception {
        Path directory = Files.createTempDirectory("edtai-mcp-git");
        Path form = directory.resolve("src/Catalogs/Товары/Forms/ФормаСписка/Form.form");
        Files.createDirectories(form.getParent());
        Files.writeString(form, "<form>\n<field>Скачать</field>\n</form>\n");
        try (Git git = Git.init().setDirectory(directory.toFile()).call()) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("gpg", null, "format", "openpgp");
            config.setBoolean("commit", null, "gpgsign", false);
            config.setBoolean("core", null, "autocrlf", false);
            config.save();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("форма").setAuthor("Тестов", "test@example.invalid")
                    .setCommitter("Тестов", "test@example.invalid").setSign(false).call();
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("McpGit");
        IProjectDescription description = ResourcesPlugin.getWorkspace().newProjectDescription("McpGit");
        description.setLocation(new org.eclipse.core.runtime.Path(directory.toString()));
        project.create(description, null);
        project.open(null);
        try {
            IFile file = project.getFile("src/Catalogs/Товары/Forms/ФормаСписка/Form.form");
            onCall = () -> file.setContents(new ByteArrayInputStream("<form>\n</form>\n".getBytes(StandardCharsets.UTF_8)),
                    true, false, null);
            ChatView view = configured(mcp.url() + "/mcp");
            view.setAutoApprove(true);

            ask(view, "Удали колонку");

            waitUntil(() -> view.linkAction(MessageRenderer.REVERT) != null, () -> view.history().getText());
            view.linkAction(MessageRenderer.CHANGES).run();
            waitUntil(() -> Arrays.stream(page.getEditorReferences()).anyMatch(editor -> editor.getTitle().equals("Правки модели: Form.form")),
                    () -> "окно сравнения не открылось");
            page.closeAllEditors(false);

            List<String> questions = new ArrayList<>();
            view.confirmRevert = question -> questions.add(question);
            view.linkAction(MessageRenderer.REVERT).run();

            assertEquals("<form>\n<field>Скачать</field>\n</form>\n", Files.readString(form));
            assertTrue(questions.get(0).contains("инструменты MCP (1)"), questions.toString());
            view.refresh();
            assertEquals(null, view.linkAction(MessageRenderer.REVERT), "откатывать больше нечего");
        } finally {
            project.delete(true, true, null);
        }
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
                onCall.run();
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
