package io.github.hansglorias.edtai.ui;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ICheckStateProvider;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import io.github.hansglorias.edtai.core.llm.ModelClient;
import io.github.hansglorias.edtai.core.llm.ModelConnection;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.McpServer;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.settings.SettingsStore;
import io.github.hansglorias.edtai.core.settings.SettingsValidator;
import io.github.hansglorias.edtai.core.tls.PemCertificates;

/**
 * Window → Preferences → EDT AI: подключение к модели, токен, сертификаты,
 * MCP-серверы.
 */
public class AiPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    public static final String ID = "io.github.hansglorias.edtai.ui.preferences";

    private final SettingsStore store = SettingsStore.workspace();
    private final List<McpServer> servers = new ArrayList<>();

    private Combo protocol;
    private Text baseUrl;
    private Text model;
    private Text maxTokens;
    private Text systemPrompt;
    private Text token;
    private Label tokenState;
    private Button removeToken;
    private boolean tokenRemovalRequested;
    private Button checkButton;
    private Label checkResult;
    private CompletableFuture<String> check;
    private Button useSystemTrustStore;
    private Text caFile;
    private Label caState;
    private CheckboxTableViewer serverViewer;
    private Button editServer;
    private Button removeServer;

    @Override
    public void init(IWorkbench workbench) {
        setDescription("Подключение к языковой модели, сертификаты и MCP-серверы.");
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite page = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().applyTo(page);

        createConnectionGroup(page);
        createCertificatesGroup(page);
        createMcpGroup(page);

        fill(store.load());
        updateTokenState();
        validate();
        Dialog.applyDialogFont(page);
        return page;
    }

    private void createConnectionGroup(Composite page) {
        Group group = group(page, "Сервер модели", 3);

        label(group, "Протокол:");
        protocol = new Combo(group, SWT.READ_ONLY);
        for (Protocol value : Protocol.values()) {
            protocol.add(value.label());
        }
        GridDataFactory.fillDefaults().span(2, 1).applyTo(protocol);

        label(group, "Адрес:");
        baseUrl = new Text(group, SWT.BORDER);
        baseUrl.setMessage("https://llm.example.invalid или http://localhost:11434");
        baseUrl.setToolTipText("Адрес сервера (https://сервер), адрес с версией API (https://сервер/v1)"
                + " или полный адрес метода — подойдёт любой.");
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).applyTo(baseUrl);
        baseUrl.addModifyListener(event -> validate());

        label(group, "Модель:");
        model = new Text(group, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).applyTo(model);

        label(group, "Макс. токенов ответа:");
        maxTokens = new Text(group, SWT.BORDER);
        maxTokens.setMessage("по умолчанию");
        maxTokens.setToolTipText("Пусто — по умолчанию: для Anthropic " + 8192 + ", для OpenAI-совместимого не передаётся.");
        GridDataFactory.fillDefaults().hint(100, SWT.DEFAULT).span(2, 1).applyTo(maxTokens);
        maxTokens.addModifyListener(event -> validate());

        Label promptLabel = label(group, "Системный промпт:");
        GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.BEGINNING).indent(0, 3).applyTo(promptLabel);
        systemPrompt = new Text(group, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        systemPrompt.setToolTipText("Инструкция модели перед каждым диалогом. Пусто — без системного промпта."
                + " «Restore Defaults» возвращает промпт по умолчанию.");
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(300, systemPrompt.getLineHeight() * 4)
                .applyTo(systemPrompt);

        label(group, "Токен:");
        token = new Text(group, SWT.BORDER | SWT.PASSWORD);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(token);
        token.addModifyListener(event -> {
            if (!token.getText().isEmpty()) {
                tokenRemovalRequested = false;
            }
            updateTokenState();
        });
        removeToken = new Button(group, SWT.PUSH);
        removeToken.setText("Удалить");
        removeToken.addListener(SWT.Selection, event -> {
            token.setText("");
            tokenRemovalRequested = true;
            updateTokenState();
        });

        new Label(group, SWT.NONE);
        tokenState = new Label(group, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).applyTo(tokenState);

        checkButton = new Button(group, SWT.PUSH);
        checkButton.setText("Проверить подключение");
        GridDataFactory.fillDefaults().applyTo(checkButton);
        checkButton.addListener(SWT.Selection, event -> checkConnection());
        checkResult = new Label(group, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).hint(300, SWT.DEFAULT).span(2, 1).applyTo(checkResult);
    }

    /** Короткий запрос к модели с текущими значениями полей, ещё не сохранёнными. */
    private void checkConnection() {
        AiSettings settings = current();
        if (!isValid()) {
            showCheckResult("Сначала исправьте ошибку в настройках.");
            return;
        }
        if (!settings.isConfigured()) {
            showCheckResult("Укажите адрес сервера и модель.");
            return;
        }
        String tokenValue;
        try {
            tokenValue = !token.getText().isEmpty() ? token.getText()
                    : tokenRemovalRequested || !store.hasToken() ? "" : store.loadToken();
        } catch (Exception e) {
            showCheckResult("Не удалось прочитать токен из Secure Storage: " + e.getMessage());
            return;
        }
        checkButton.setEnabled(false);
        showCheckResult("Проверяю…");
        Display display = checkButton.getDisplay();
        check = new ModelClient().check(ModelConnection.of(settings, tokenValue));
        check.whenComplete((result, error) -> display.asyncExec(() -> {
            if (checkButton.isDisposed()) {
                return;
            }
            checkButton.setEnabled(true);
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            showCheckResult(cause == null ? result : "Не работает: " + cause.getMessage());
        }));
    }

    private void showCheckResult(String text) {
        checkResult.setText(text);
        checkResult.getParent().layout(true);
        checkResult.getShell().layout(new Control[] { checkResult });
    }

    private void createCertificatesGroup(Composite page) {
        Group group = group(page, "Сертификаты", 3);

        useSystemTrustStore = new Button(group, SWT.CHECK);
        useSystemTrustStore.setText("Доверять корневым сертификатам операционной системы");
        GridDataFactory.fillDefaults().span(3, 1).applyTo(useSystemTrustStore);
        useSystemTrustStore.addListener(SWT.Selection, event -> updateCaState());

        label(group, "Файл CA (PEM):");
        caFile = new Text(group, SWT.BORDER);
        caFile.setMessage("корпоративный корневой сертификат, необязательно");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(caFile);
        caFile.addModifyListener(event -> {
            updateCaState();
            validate();
        });
        Button browse = new Button(group, SWT.PUSH);
        browse.setText("Обзор…");
        browse.addListener(SWT.Selection, event -> {
            FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
            dialog.setFilterExtensions(new String[] { "*.pem;*.crt;*.cer", "*.*" });
            String file = dialog.open();
            if (file != null) {
                caFile.setText(file);
            }
        });

        new Label(group, SWT.NONE);
        caState = new Label(group, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).applyTo(caState);
    }

    private void createMcpGroup(Composite page) {
        Group group = group(page, "MCP-серверы", 2);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(group);

        serverViewer = CheckboxTableViewer.newCheckList(group, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        Table table = serverViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, true).hint(SWT.DEFAULT, 120).applyTo(table);

        column("Имя", 140, McpServer::name);
        column("Транспорт", 80, server -> server.transport().label());
        column("Команда / URL", 260, McpServer::target);

        serverViewer.setContentProvider(ArrayContentProvider.getInstance());
        serverViewer.setCheckStateProvider(new ICheckStateProvider() {
            @Override
            public boolean isChecked(Object element) {
                return ((McpServer) element).enabled();
            }

            @Override
            public boolean isGrayed(Object element) {
                return false;
            }
        });
        serverViewer.addCheckStateListener(event -> {
            McpServer server = (McpServer) event.getElement();
            servers.set(servers.indexOf(server), server.withEnabled(event.getChecked()));
            refreshServers();
        });
        serverViewer.addSelectionChangedListener(event -> updateServerButtons());
        serverViewer.addDoubleClickListener(event -> editSelectedServer());
        serverViewer.setInput(servers);

        Composite buttons = new Composite(group, SWT.NONE);
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.BEGINNING).applyTo(buttons);
        GridLayoutFactory.fillDefaults().applyTo(buttons);

        Button add = button(buttons, "Добавить…");
        add.addListener(SWT.Selection, event -> {
            McpServerDialog dialog = new McpServerDialog(getShell(), null, names(null));
            if (dialog.open() == Window.OK) {
                servers.add(dialog.result());
                refreshServers();
            }
        });
        editServer = button(buttons, "Изменить…");
        editServer.addListener(SWT.Selection, event -> editSelectedServer());
        removeServer = button(buttons, "Удалить");
        removeServer.addListener(SWT.Selection, event -> {
            McpServer selected = selectedServer();
            if (selected != null) {
                servers.remove(selected);
                refreshServers();
            }
        });
        updateServerButtons();
    }

    private void editSelectedServer() {
        McpServer selected = selectedServer();
        if (selected == null) {
            return;
        }
        McpServerDialog dialog = new McpServerDialog(getShell(), selected, names(selected));
        if (dialog.open() == Window.OK) {
            servers.set(servers.indexOf(selected), dialog.result());
            refreshServers();
        }
    }

    private McpServer selectedServer() {
        Object element = serverViewer.getStructuredSelection().getFirstElement();
        return element instanceof McpServer server ? server : null;
    }

    private List<String> names(McpServer except) {
        return servers.stream().filter(server -> server != except).map(McpServer::name).toList();
    }

    private void refreshServers() {
        serverViewer.refresh();
        updateServerButtons();
        validate();
    }

    private void updateServerButtons() {
        boolean selected = selectedServer() != null;
        editServer.setEnabled(selected);
        removeServer.setEnabled(selected);
    }

    private void fill(AiSettings settings) {
        protocol.select(settings.protocol().ordinal());
        baseUrl.setText(settings.baseUrl());
        model.setText(settings.model());
        maxTokens.setText(settings.maxTokens() > 0 ? String.valueOf(settings.maxTokens()) : "");
        systemPrompt.setText(settings.systemPrompt());
        useSystemTrustStore.setSelection(settings.useSystemTrustStore());
        caFile.setText(settings.caCertificateFile());
        servers.clear();
        servers.addAll(settings.mcpServers());
        refreshServers();
        updateCaState();
    }

    AiSettings current() {
        return new AiSettings(Protocol.values()[Math.max(0, protocol.getSelectionIndex())], baseUrl.getText(),
                model.getText(), Math.max(0, maxTokensValue()), systemPrompt.getText(), useSystemTrustStore.getSelection(),
                caFile.getText(), servers);
    }

    /** @return число из поля, 0 — пусто, -1 — не число */
    private int maxTokensValue() {
        String text = maxTokens.getText().strip();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(text);
            return value > 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void validate() {
        if (baseUrl == null || maxTokens == null || caFile == null || serverViewer == null) {
            return;
        }
        List<String> errors = new ArrayList<>(SettingsValidator.validate(current()));
        if (maxTokensValue() < 0) {
            errors.add(0, "Макс. токенов ответа: нужно целое положительное число или пусто");
        }
        setErrorMessage(errors.isEmpty() ? null : errors.get(0));
        setMessage(SettingsValidator.plainHttpWarning(baseUrl.getText()), IMessageProvider.WARNING);
        setValid(errors.isEmpty());
    }

    private void updateTokenState() {
        boolean saved = hasSavedToken();
        String text;
        if (!token.getText().isEmpty()) {
            text = "Новый токен будет сохранён в Secure Storage.";
        } else if (tokenRemovalRequested) {
            text = "Сохранённый токен будет удалён.";
        } else if (saved) {
            text = "Токен сохранён в Secure Storage. Введите новый, чтобы заменить.";
        } else {
            text = "Токен не задан.";
        }
        tokenState.setText(text);
        removeToken.setEnabled(saved && !tokenRemovalRequested || !token.getText().isEmpty());
        tokenState.getParent().layout(true);
    }

    private boolean hasSavedToken() {
        try {
            return store.hasToken();
        } catch (RuntimeException e) {
            Platform.getLog(getClass()).warn("Secure Storage недоступно", e);
            return false;
        }
    }

    private void updateCaState() {
        String file = caFile.getText().strip();
        if (file.isEmpty()) {
            caState.setText("Используются только корневые сертификаты JDK" + (useSystemTrustStore.getSelection() ? " и ОС." : "."));
        } else {
            try {
                List<X509Certificate> certificates = PemCertificates.read(Path.of(file));
                caState.setText("Сертификатов в файле: " + certificates.size() + ". Первый: "
                        + certificates.get(0).getSubjectX500Principal().getName());
            } catch (Exception e) {
                caState.setText("Не удалось прочитать: " + e.getMessage());
            }
        }
        caState.getParent().layout(true);
    }

    @Override
    public void dispose() {
        if (check != null) {
            check.cancel(true);
        }
        super.dispose();
    }

    @Override
    protected void performDefaults() {
        fill(AiSettings.defaults());
        validate();
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        AiSettings settings = current();
        validate();
        if (!isValid()) {
            return false;
        }
        try {
            store.save(settings);
            if (!token.getText().isEmpty()) {
                store.saveToken(token.getText());
                token.setText("");
            } else if (tokenRemovalRequested) {
                store.saveToken("");
            }
            tokenRemovalRequested = false;
            updateTokenState();
            return true;
        } catch (Exception e) {
            Platform.getLog(getClass()).error("Не удалось сохранить настройки EDT AI", e);
            setErrorMessage("Не удалось сохранить настройки: " + e.getMessage());
            return false;
        }
    }

    private void column(String title, int width, Function<McpServer, String> text) {
        TableViewerColumn column = new TableViewerColumn(serverViewer, SWT.NONE);
        column.getColumn().setText(title);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return text.apply((McpServer) element);
            }
        });
    }

    private static Group group(Composite parent, String title, int columns) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(title);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(group);
        GridLayoutFactory.swtDefaults().numColumns(columns).applyTo(group);
        return group;
    }

    private static Label label(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
        return label;
    }

    private static Button button(Composite parent, String text) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(button);
        return button;
    }

    // ---- для тестов ----

    Text baseUrlField() {
        return baseUrl;
    }

    Text modelField() {
        return model;
    }

    Text maxTokensField() {
        return maxTokens;
    }

    Text systemPromptField() {
        return systemPrompt;
    }

    Button checkButton() {
        return checkButton;
    }

    Label checkResult() {
        return checkResult;
    }
}
