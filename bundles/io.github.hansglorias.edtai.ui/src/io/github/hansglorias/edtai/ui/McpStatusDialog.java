package io.github.hansglorias.edtai.ui;

import java.util.List;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;

import io.github.hansglorias.edtai.core.mcp.McpHub;
import io.github.hansglorias.edtai.core.mcp.McpToolInfo;

/**
 * Окно команды {@code /mcp}: серверы с состоянием подключения и их
 * инструменты — что модель получает, что только читает, что меняет проект.
 * Внизу — полное описание выбранного.
 */
class McpStatusDialog extends TitleAreaDialog {

    static final String READS = "читает";
    static final String MODIFIES = "меняет проект";
    static final String FILTERED = "не передаётся";
    private static final int SETTINGS_ID = IDialogConstants.CLIENT_ID + 1;

    private final List<McpHub.ServerStatus> servers;
    private final Runnable openSettings;
    private Tree tree;
    private Text details;

    McpStatusDialog(Shell parent, List<McpHub.ServerStatus> servers, Runnable openSettings) {
        super(parent);
        this.servers = List.copyOf(servers);
        this.openSettings = openSettings;
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("MCP-серверы");
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        setTitle("MCP-серверы");
        if (servers.isEmpty()) {
            setMessage(McpHub.NO_SERVERS, IMessageProvider.WARNING);
        } else {
            long connected = servers.stream().filter(server -> server.state() == McpHub.State.CONNECTED).count();
            int tools = servers.stream().mapToInt(server -> server.allowed().size()).sum();
            setMessage("Подключено " + connected + " из " + servers.size() + ", модели доступно инструментов: " + tools
                    + ". Меняющие проект в режиме «только вопросы» не передаются.");
        }

        Composite body = new Composite(area, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(body);
        GridLayoutFactory.swtDefaults().margins(10, 10).spacing(0, 8).applyTo(body);

        tree = new Tree(body, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        tree.setHeaderVisible(true);
        tree.setLinesVisible(false);
        GridDataFactory.fillDefaults().grab(true, true).hint(760, 340).applyTo(tree);
        column("Сервер, инструмент", 260);
        column("Доступ", 120);
        column("Состояние, описание", 380);
        fill();

        details = new Text(body, SWT.BORDER | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.MULTI);
        GridDataFactory.fillDefaults().grab(true, false).hint(760, 72).applyTo(details);
        tree.addListener(SWT.Selection, event -> showDetails());
        if (tree.getItemCount() > 0) {
            tree.setSelection(tree.getItem(0));
            showDetails();
        }
        return area;
    }

    private void column(String title, int width) {
        TreeColumn column = new TreeColumn(tree, SWT.LEFT);
        column.setText(title);
        column.setWidth(width);
    }

    private void fill() {
        Color muted = mutedColor();
        for (McpHub.ServerStatus server : servers) {
            TreeItem item = new TreeItem(tree, SWT.NONE);
            item.setText(new String[] { server.server().name(), state(server.state()), server.summary() });
            item.setImage(Icons.image(switch (server.state()) {
                case CONNECTED -> Icons.STATUS_OK;
                case FAILED, UNSUPPORTED -> Icons.STATUS_ERROR;
                case DISABLED, UNKNOWN -> Icons.STATUS_OFF;
            }));
            item.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT));
            item.setData(server.server().name() + " — " + server.server().target() + "\n" + server.summary());
            for (McpToolInfo tool : server.tools()) {
                boolean allowed = server.server().allowsTool(tool.name());
                TreeItem child = new TreeItem(item, SWT.NONE);
                child.setText(new String[] { tool.name(), !allowed ? FILTERED : tool.modifies() ? MODIFIES : READS,
                        firstLine(tool.description()) });
                if (!allowed) {
                    child.setForeground(muted);
                }
                child.setData(tool.name() + (allowed ? "" : " — не передаётся модели: нет в списке «Инструменты» сервера")
                        + (tool.description().isBlank() ? "" : "\n" + tool.description().strip()));
            }
            item.setExpanded(server.state() == McpHub.State.CONNECTED);
        }
    }

    private void showDetails() {
        TreeItem[] selection = tree.getSelection();
        details.setText(selection.length == 0 || selection[0].getData() == null ? "" : (String) selection[0].getData());
    }

    static String state(McpHub.State state) {
        return switch (state) {
            case CONNECTED -> "подключён";
            case FAILED -> "ошибка";
            case UNSUPPORTED -> "не поддерживается";
            case DISABLED -> "выключен";
            case UNKNOWN -> "не проверялся";
        };
    }

    private static String firstLine(String text) {
        String stripped = text.strip();
        int end = stripped.indexOf('\n');
        return end < 0 ? stripped : stripped.substring(0, end).strip();
    }

    private Color mutedColor() {
        Color color = JFaceResources.getColorRegistry().get(JFacePreferences.QUALIFIER_COLOR);
        return color != null ? color : getShell().getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, SETTINGS_ID, "Настройки MCP…", false);
        createButton(parent, IDialogConstants.OK_ID, "Закрыть", true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == SETTINGS_ID) {
            close();
            openSettings.run();
            return;
        }
        super.buttonPressed(buttonId);
    }

    Tree tree() {
        return tree;
    }

    Text details() {
        return details;
    }
}
