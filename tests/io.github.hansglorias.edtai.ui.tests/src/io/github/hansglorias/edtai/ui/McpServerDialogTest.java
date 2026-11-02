package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.McpServer;
import io.github.hansglorias.edtai.core.settings.McpTransport;
import io.github.hansglorias.edtai.core.tls.TrustSettings;

class McpServerDialogTest extends AbstractChatTest {
    private McpServerDialog dialog;
    private volatile CountDownLatch initializeGate;

    @BeforeEach void setupMcp() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            if (request.method().equals("DELETE")) { response.send(200, "text/plain", ""); return; }
            var message = Json.parseObject(request.body());
            String method = Json.stringAt(message, "method");
            if (method.startsWith("notifications/")) { response.send(202, "text/plain", ""); return; }
            Object result;
            if (method.equals("initialize")) {
                if (initializeGate != null) initializeGate.await(3, TimeUnit.SECONDS);
                result = Json.object("protocolVersion", "2025-06-18", "serverInfo", Json.object("name", "Fake Setup", "version", "3.0"));
            } else result = Json.object("tools", List.of(Json.object("name", "get_test", "inputSchema", Json.object("type", "object"))));
            response.start(200, "application/json", Map.of("Mcp-Session-Id", "setup"));
            response.write(Json.write(Json.object("jsonrpc", "2.0", "id", message.get("id"), "result", result)));
            response.end();
        });
    }

    private void open(McpServer initial, List<String> names) {
        dialog = new McpServerDialog(page.getWorkbenchWindow().getShell(), initial, names, new TrustSettings(false, Optional.empty()));
        dialog.setBlockOnOpen(false);
        dialog.open();
    }

    @AfterEach void closeDialog() { if (initializeGate != null) initializeGate.countDown(); if (dialog != null) dialog.close(); }

    @Test void portOnlySetupDiscoversUniqueNameAndPreservesManualName() throws Exception {
        open(null, List.of("Fake Setup"));
        assertTrue(dialog.localhostButton().getSelection());
        assertFalse(dialog.targetField().getVisible());
        dialog.portField().setText(Integer.toString(URI.create(server.url()).getPort()));
        dialog.checkButton().notifyListeners(SWT.Selection, new Event());
        waitUntil(() -> dialog.checkResult().getText().startsWith("Подключено"), () -> dialog.checkResult().getText());
        assertEquals("Fake Setup (2)", dialog.nameField().getText());
        Screenshots.render(dialog.getShell().getChildren()[0], "mcp-localhost-setup");
        dialog.nameField().setText("Рабочий");
        dialog.checkButton().notifyListeners(SWT.Selection, new Event());
        waitUntil(() -> dialog.checkButton().isEnabled());
        assertEquals("Рабочий", dialog.nameField().getText());
        dialog.okPressed();
        assertEquals(server.url() + "/mcp", dialog.result().target());
        assertEquals("Рабочий", dialog.result().name());
    }

    @Test void advancedUrlSurvivesTogglingAndPortIsValidated() {
        String url = "https://mcp.example.invalid/custom?q=1";
        open(new McpServer("Remote", McpTransport.HTTP, url, true), List.of());
        assertFalse(dialog.localhostButton().getSelection());
        dialog.localhostButton().setSelection(true);
        dialog.localhostButton().notifyListeners(SWT.Selection, new Event());
        dialog.portField().setText("0");
        assertFalse(dialog.checkButton().isEnabled());
        dialog.portField().setText("1337");
        assertTrue(dialog.checkButton().isEnabled());
        dialog.localhostButton().setSelection(false);
        dialog.localhostButton().notifyListeners(SWT.Selection, new Event());
        assertEquals(url, dialog.targetField().getText());
        dialog.okPressed();
        assertEquals(url, dialog.result().target());
    }

    @Test void existingNameIsPreservedEvenWhenItLooksGenerated() {
        String url = server.url() + "/mcp";
        String name = McpServer.suggestedName("", url, List.of());
        open(new McpServer(name, McpTransport.HTTP, url, true), List.of());
        dialog.checkButton().notifyListeners(SWT.Selection, new Event());
        waitUntil(() -> dialog.checkResult().getText().startsWith("Подключено"));
        assertEquals(name, dialog.nameField().getText());
    }

    @Test void changedPortDoesNotAcceptStaleName() {
        initializeGate = new CountDownLatch(1);
        open(null, List.of());
        dialog.portField().setText(Integer.toString(URI.create(server.url()).getPort()));
        dialog.checkButton().notifyListeners(SWT.Selection, new Event());
        waitUntil(() -> !server.requests().isEmpty());
        dialog.portField().setText("1");
        initializeGate.countDown();
        waitUntil(() -> dialog.checkButton().isEnabled());
        assertTrue(dialog.nameField().getText().isEmpty());
        assertTrue(dialog.checkResult().getText().contains("Адрес изменился"));
        dialog.okPressed();
        assertEquals("http://localhost:1/mcp", dialog.result().target());
        assertEquals("MCP localhost:1", dialog.result().name());
    }
}
