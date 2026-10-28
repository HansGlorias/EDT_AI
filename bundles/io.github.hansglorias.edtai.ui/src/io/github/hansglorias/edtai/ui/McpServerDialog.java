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

import io.github.hansglorias.edtai.core.settings.McpServer;
import io.github.hansglorias.edtai.core.settings.McpTransport;
import io.github.hansglorias.edtai.core.settings.SettingsValidator;

/**
 * Добавление и изменение MCP-сервера.
 */
class McpServerDialog extends TitleAreaDialog {

    private final McpServer initial;
    private final Set<String> takenNames;

    private Text name;
    private Combo transport;
    private Label targetLabel;
    private Text target;
    private Text tools;
    private Button enabled;

    private McpServer result;

    /**
     * @param initial    редактируемый сервер или {@code null} для нового
     * @param otherNames имена остальных серверов, чтобы не допустить дубль
     */
    McpServerDialog(Shell parent, McpServer initial, List<String> otherNames) {
        super(parent);
        this.initial = initial;
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
        setMessage("MCP-сервер по HTTP (Streamable HTTP), например EDT-MCP: http://localhost:8765/mcp."
                + " Вызовы его инструментов модель делает с вашего разрешения.");

        Composite body = new Composite(area, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(body);
        GridLayoutFactory.swtDefaults().numColumns(2).margins(10, 10).applyTo(body);

        new Label(body, SWT.NONE).setText("Имя:");
        name = new Text(body, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).hint(400, SWT.DEFAULT).applyTo(name);

        new Label(body, SWT.NONE).setText("Транспорт:");
        transport = new Combo(body, SWT.READ_ONLY);
        for (McpTransport value : McpTransport.values()) {
            transport.add(value.label());
        }

        targetLabel = new Label(body, SWT.NONE);
        target = new Text(body, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(target);

        new Label(body, SWT.NONE).setText("Инструменты:");
        tools = new Text(body, SWT.BORDER);
        tools.setMessage("все; или через запятую: read_module_source, search_in_code, get_*");
        tools.setToolTipText("Какие инструменты сервера давать модели. Описание каждого уходит в каждый запрос: у EDT-MCP"
                + " их около сотни, лучше перечислить нужные. * в конце — по началу имени.");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(tools);

        new Label(body, SWT.NONE);
        enabled = new Button(body, SWT.CHECK);
        enabled.setText("Включён");

        McpServer start = initial != null ? initial : new McpServer("", McpTransport.HTTP, "", true);
        name.setText(start.name());
        transport.select(start.transport().ordinal());
        target.setText(start.target());
        tools.setText(start.tools());
        enabled.setSelection(start.enabled());

        name.addModifyListener(event -> validate());
        transport.addListener(SWT.Selection, event -> validate());
        target.addModifyListener(event -> validate());
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
        targetLabel.setText(selectedTransport() == McpTransport.STDIO ? "Команда:" : "URL:");
        target.setMessage(selectedTransport() == McpTransport.STDIO ? "python3 server.py (stdio пока не поддерживается)"
                : "http://localhost:8765/mcp");
        targetLabel.getParent().layout(true);
    }

    private McpServer current() {
        return new McpServer(name.getText(), selectedTransport(), target.getText(), enabled.getSelection(), tools.getText());
    }

    private void validate() {
        updateTargetLabel();
        McpServer server = current();
        List<String> errors = SettingsValidator.validateMcpServer(server);
        String error = errors.isEmpty() ? null : errors.get(0);
        if (error == null && takenNames.contains(server.name().toLowerCase(Locale.ROOT))) {
            error = "Сервер с именем «" + server.name() + "» уже есть";
        }
        boolean untouched = initial == null && server.name().isEmpty() && server.target().isEmpty();
        setErrorMessage(untouched ? null : error);
        Button ok = getButton(IDialogConstants.OK_ID);
        if (ok != null) {
            ok.setEnabled(error == null);
        }
    }

    @Override
    protected void okPressed() {
        result = current();
        super.okPressed();
    }

    McpServer result() {
        return result;
    }
}
