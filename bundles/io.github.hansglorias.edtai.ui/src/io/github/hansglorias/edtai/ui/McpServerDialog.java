package io.github.hansglorias.edtai.ui;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.layout.GridData;

import io.github.hansglorias.edtai.core.mcp.McpHub;
import io.github.hansglorias.edtai.core.mcp.McpException;
import io.github.hansglorias.edtai.core.settings.McpServer;
import io.github.hansglorias.edtai.core.settings.McpTransport;
import io.github.hansglorias.edtai.core.settings.SettingsValidator;
import io.github.hansglorias.edtai.core.tls.TrustSettings;

/**
 * Добавление и изменение MCP-сервера.
 */
class McpServerDialog extends TitleAreaDialog {

    private final McpServer initial;
    private final Set<String> takenNames;
    private final TrustSettings trust;

    private Text name;
    private Combo transport;
    private Label targetLabel;
    private Text target;
    private Text tools;
    private Button enabled;
    private Button localhost;
    private Label localhostLabel;
    private Label portLabel;
    private Text port;
    private Label address;
    private Button check;
    private Label checkResult;
    private boolean checking;
    private String customTarget = "";
    private String automaticName = "";

    private McpServer result;

    /**
     * @param initial    редактируемый сервер или {@code null} для нового
     * @param otherNames имена остальных серверов, чтобы не допустить дубль
     */
    McpServerDialog(Shell parent, McpServer initial, List<String> otherNames, TrustSettings trust) {
        super(parent);
        this.initial = initial;
        this.trust = trust;
        this.takenNames = otherNames.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(initial == null ? "Новый MCP-сервер" : "MCP-сервер");
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        setTitle("MCP-сервер");
        setMessage("Для сервера на этом компьютере достаточно порта. «Проверить» определит имя сервера; можно задать своё.");

        Composite body = new Composite(area, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(body);
        GridLayoutFactory.swtDefaults().numColumns(2).margins(10, 10).applyTo(body);

        new Label(body, SWT.NONE).setText("Имя:");
        name = new Text(body, SWT.BORDER);
        name.setMessage("определится при проверке; можно оставить пустым");
        GridDataFactory.fillDefaults().grab(true, false).hint(400, SWT.DEFAULT).applyTo(name);

        new Label(body, SWT.NONE).setText("Транспорт:");
        transport = new Combo(body, SWT.READ_ONLY);
        for (McpTransport value : McpTransport.values()) {
            transport.add(value.label());
        }

        localhostLabel = new Label(body, SWT.NONE);
        GridDataFactory.fillDefaults().applyTo(localhostLabel);
        localhost = new Button(body, SWT.CHECK);
        localhost.setText("На этом компьютере (localhost)");
        GridDataFactory.fillDefaults().applyTo(localhost);

        portLabel = new Label(body, SWT.NONE);
        portLabel.setText("Порт:");
        GridDataFactory.fillDefaults().applyTo(portLabel);
        port = new Text(body, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(port);

        targetLabel = new Label(body, SWT.NONE);
        GridDataFactory.fillDefaults().applyTo(targetLabel);
        target = new Text(body, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(target);

        address = new Label(body, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).applyTo(address);

        new Label(body, SWT.NONE).setText("Инструменты:");
        tools = new Text(body, SWT.BORDER);
        tools.setMessage("все; или через запятую: read_module_source, search_in_code, get_*");
        tools.setToolTipText("Какие инструменты сервера давать модели. Описание каждого уходит в каждый запрос: у EDT-MCP"
                + " их около сотни, лучше перечислить нужные. * в конце — по началу имени.");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(tools);

        new Label(body, SWT.NONE);
        enabled = new Button(body, SWT.CHECK);
        enabled.setText("Включён");

        new Label(body, SWT.NONE);
        check = new Button(body, SWT.PUSH);
        check.setText("Проверить");
        check.addListener(SWT.Selection, event -> checkConnection());
        checkResult = new Label(body, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(400, SWT.DEFAULT).applyTo(checkResult);

        McpServer start = initial != null ? initial : new McpServer("", McpTransport.HTTP, "", true);
        name.setText(start.name());
        transport.select(start.transport().ordinal());
        target.setText(start.target());
        tools.setText(start.tools());
        enabled.setSelection(start.enabled());
        var localPort = McpServer.localPort(start.target());
        localhost.setSelection(initial == null || start.transport() == McpTransport.HTTP && localPort.isPresent());
        port.setText(Integer.toString(localPort.orElse(8765)));

        name.addModifyListener(event -> validate());
        transport.addListener(SWT.Selection, event -> validate());
        target.addModifyListener(event -> validate());
        port.addModifyListener(event -> validate());
        localhost.addListener(SWT.Selection, event -> {
            if (localhost.getSelection()) {
                customTarget = McpServer.localPort(target.getText()).isPresent() ? "" : target.getText();
            } else {
                try {
                    target.setText(customTarget.isEmpty() ? McpServer.localTarget(port.getText()) : customTarget);
                } catch (IllegalArgumentException e) {
                    target.setText(customTarget);
                }
            }
            validate();
        });
        updateTargetLabel();
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        super.createButtonsForButtonBar(parent);
        validate();
    }

    private McpTransport selectedTransport() {
        return McpTransport.values()[Math.max(0, transport.getSelectionIndex())];
    }

    private void updateTargetLabel() {
        boolean http = selectedTransport() == McpTransport.HTTP;
        boolean local = http && localhost.getSelection();
        visible(localhost, http);
        visible(localhostLabel, http);
        visible(portLabel, local);
        visible(port, local);
        visible(targetLabel, !local);
        visible(target, !local);
        visible(address, local);
        try {
            address.setText(local ? McpServer.localTarget(port.getText()) : "");
        } catch (IllegalArgumentException e) {
            address.setText("Укажите порт от 1 до 65535");
        }
        targetLabel.setText(http ? "URL:" : "Команда:");
        target.setMessage(selectedTransport() == McpTransport.STDIO ? "python3 server.py (stdio пока не поддерживается)"
                : "http://localhost:8765/mcp");
        targetLabel.getParent().layout(true);
    }

    private McpServer current() {
        String url = selectedTransport() == McpTransport.HTTP && localhost.getSelection()
                ? McpServer.localTarget(port.getText()) : target.getText();
        String title = name.getText().isBlank() ? McpServer.suggestedName("", url, List.copyOf(takenNames)) : name.getText();
        return new McpServer(title, selectedTransport(), url, enabled.getSelection(), tools.getText());
    }

    private void validate() {
        updateTargetLabel();
        String error;
        try {
            McpServer server = current();
            List<String> errors = SettingsValidator.validateMcpServer(server);
            error = errors.isEmpty() ? null : errors.get(0);
            if (error == null && takenNames.contains(server.name().toLowerCase(Locale.ROOT))) {
                error = "Сервер с именем «" + server.name() + "» уже есть";
            }
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
        }
        setErrorMessage(error);
        check.setEnabled(!checking && error == null && selectedTransport() == McpTransport.HTTP);
        Button ok = getButton(IDialogConstants.OK_ID);
        if (ok != null) {
            ok.setEnabled(error == null);
        }
    }

    private static void visible(Control control, boolean value) {
        control.setVisible(value);
        ((GridData) control.getLayoutData()).exclude = !value;
    }

    private void checkConnection() {
        McpServer request = current();
        String previousName = name.getText();
        checking = true;
        validate();
        checkResult.setText("Подключаюсь…");
        Display display = check.getDisplay();
        McpHub shared = ChatView.mcpForCheck(request, trust);
        long revision = shared == null ? 0 : shared.revision();
        Thread thread = new Thread(() -> {
            try (McpHub temporary = shared == null ? new McpHub() : null) {
                McpHub hub = shared == null ? temporary : shared;
                String text;
                String reported = "";
                try {
                    var result = hub.checkDetails(request, trust, revision);
                    text = result.text();
                    reported = result.name();
                } catch (McpException e) {
                    text = "Не подключён: " + e.getMessage();
                }
                McpHub.ServerStatus status = hub.lastStatus(List.of(request)).get(0);
                String message = text;
                String title = reported;
                display.asyncExec(() -> {
                    if (checkResult.isDisposed()) return;
                    checking = false;
                    ChatView.mcpChecked(status, trust);
                    try {
                        if (!current().target().equals(request.target()) || selectedTransport() != request.transport()) {
                            checkResult.setText("Адрес изменился во время проверки. Проверьте снова");
                        } else {
                            if (!title.isBlank() && name.getText().equals(previousName)
                                    && (previousName.isBlank() || previousName.equals(automaticName))) {
                                automaticName = McpServer.suggestedName(title, request.target(), List.copyOf(takenNames));
                                name.setText(automaticName);
                            }
                            checkResult.setText(message);
                        }
                    } catch (IllegalArgumentException e) {
                        checkResult.setText("Адрес изменился во время проверки. Проверьте снова");
                    }
                    validate();
                    checkResult.getShell().layout(true, true);
                });
            }
        }, "EDT AI: проверка MCP-сервера");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    protected void okPressed() {
        result = current();
        super.okPressed();
    }

    McpServer result() {
        return result;
    }

    Button localhostButton() { return localhost; }
    Text portField() { return port; }
    Text targetField() { return target; }
    Text nameField() { return name; }
    Button checkButton() { return check; }
    Label checkResult() { return checkResult; }
}
