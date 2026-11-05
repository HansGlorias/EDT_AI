package io.github.hansglorias.edtai.ui;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
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
import org.eclipse.ui.dialogs.ElementListSelectionDialog;

import io.github.hansglorias.edtai.core.llm.ModelClient;
import io.github.hansglorias.edtai.core.llm.ModelConnection;
import io.github.hansglorias.edtai.core.mcp.McpException;
import io.github.hansglorias.edtai.core.mcp.McpHub;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.ApiProvider;
import io.github.hansglorias.edtai.core.settings.McpServer;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.settings.SettingsStore;
import io.github.hansglorias.edtai.core.settings.SettingsValidator;
import io.github.hansglorias.edtai.core.tls.PemCertificates;
import io.github.hansglorias.edtai.core.tls.TrustSettings;

/**
 * Window → Preferences → EDT AI: подключение к модели, токен, сертификаты,
 * MCP-серверы.
 */
public class AiPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    public static final String ID = "io.github.hansglorias.edtai.ui.preferences";

    private final SettingsStore store = SettingsStore.workspace();
    private final List<McpServer> servers = new ArrayList<>();

    private final List<ApiProvider> providers = new ArrayList<>();
    private final Map<String, String> tokenEdits = new HashMap<>();
    private final List<String> deletedProviders = new ArrayList<>();
    private Combo providerChoice;
    private Text providerName;
    private Button deleteProvider;
    private String providerId = ApiProvider.DEFAULT_ID;
    private boolean catalogChanged;
    private boolean filling;
    private int connectionRevision;

    private Combo protocol;
    private Text baseUrl;
    private Text model;
    private Text maxTokens;
    private Text systemPrompt;
    private Text contextLimit;
    private Text modelWindow;
    private Label windowState;
    private Button toolsEnabled;
    private Text token;
    private Label tokenState;
    private Button removeToken;
    private boolean tokenRemovalRequested;
    private Button checkButton;
    private Button listModels;
    private Label checkResult;
    private CompletableFuture<String> check;
    private Button useSystemTrustStore;
    private Text caFile;
    private Label caState;
    private CheckboxTableViewer serverViewer;
    private Button editServer;
    private Button removeServer;
    private Button checkServer;
    private Label serverResult;
    private Combo mcpInterval;
    private Button localDiagnostics;
    private ScrollArea scroll;

    @Override
    public void init(IWorkbench workbench) {
        setDescription("Подключение к языковой модели, сертификаты и MCP-серверы.");
    }

    @Override
    protected Control createContents(Composite parent) {
        scroll = new ScrollArea(parent, SWT.V_SCROLL | SWT.H_SCROLL) {
            @Override
            public Point computeSize(int widthHint, int heightHint, boolean changed) {
                var preferred = super.computeSize(widthHint, heightHint, changed);
                if (widthHint == SWT.DEFAULT) preferred.x = Math.min(preferred.x, 850);
                if (heightHint == SWT.DEFAULT) preferred.y = Math.min(preferred.y, 600);
                return preferred;
            }
        };
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        scroll.setShowFocusedControl(true);
        Composite page = new Composite(scroll, SWT.NONE);
        GridLayoutFactory.fillDefaults().applyTo(page);

        providers.addAll(store.providers());
        providerId = store.activeProviderId();
        catalogChanged = !store.load().providerId().isEmpty();
        createProvidersGroup(page);
        createConnectionGroup(page);
        createCertificatesGroup(page);
        createMcpGroup(page);
        Group diagnostics = group(page, "Локальная диагностика", 1);
        localDiagnostics = new Button(diagnostics, SWT.CHECK);
        localDiagnostics.setText("Вести локальный диагностический журнал запросов");
        localDiagnostics.setSelection(UiDiagnosticLog.preference());
        Label diagnosticInfo = new Label(diagnostics, SWT.WRAP);
        diagnosticInfo.setText("Только числовые метрики, без содержимого запросов и ответов. "
                + "Ничего не отправляется автоматически. До трёх файлов по 1 МиБ.\n" + UiDiagnosticLog.location()
                + "\nСостояние журнала — в подсказке строки состояния чата.");
        GridDataFactory.fillDefaults().grab(true, false).hint(400, SWT.DEFAULT).applyTo(diagnosticInfo);

        filling = true;
        fill(store.load());
        refreshProviders();
        filling = false;
        updateTokenState();
        validate();
        Dialog.applyDialogFont(page);
        scroll.setContent(page);
        Runnable resize = () -> scroll.setMinSize(page.computeSize(
                Math.max(650, scroll.viewport().width), SWT.DEFAULT));
        scroll.addListener(SWT.Resize, event -> resize.run());
        resize.run();
        return scroll;
    }

    private void createProvidersGroup(Composite page) {
        Group group = group(page, "API-провайдеры", 3);
        providerChoice = new Combo(group, SWT.READ_ONLY);
        GridDataFactory.fillDefaults().hint(220, SWT.DEFAULT).applyTo(providerChoice);
        providerChoice.addListener(SWT.Selection, event -> switchProvider(providerChoice.getSelectionIndex()));
        Button add = button(group, "Добавить");
        GridDataFactory.fillDefaults().applyTo(add);
        add.addListener(SWT.Selection, event -> addProvider());
        deleteProvider = button(group, "Удалить");
        GridDataFactory.fillDefaults().applyTo(deleteProvider);
        deleteProvider.addListener(SWT.Selection, event -> deleteProvider());
        label(group, "Имя:");
        providerName = new Text(group, SWT.BORDER);
        GridDataFactory.fillDefaults().hint(260, SWT.DEFAULT).span(2, 1).applyTo(providerName);
        providerName.addModifyListener(event -> {
            if (!filling) { catalogChanged = true; validate(); }
        });
    }

    private int providerIndex() {
        for (int i = 0; i < providers.size(); i++) if (providers.get(i).id().equals(providerId)) return i;
        return 0;
    }

    private void refreshProviders() {
        providerChoice.setItems(providers.stream().map(ApiProvider::name).toArray(String[]::new));
        providerChoice.select(providerIndex());
        providerName.setText(providers.get(providerIndex()).name());
        deleteProvider.setEnabled(providers.size() > 1);
    }

    private boolean stageProvider() {
        validate();
        if (!isValid()) { providerChoice.select(providerIndex()); return false; }
        providers.set(providerIndex(), ApiProvider.of(providerId, providerName.getText(), current()));
        if (!token.getText().isEmpty()) tokenEdits.put(providerId, token.getText());
        else if (tokenRemovalRequested) tokenEdits.put(providerId, "");
        return true;
    }

    void switchProvider(int index) {
        if (index < 0 || index >= providers.size() || !stageProvider()) return;
        AiSettings shared = current();
        providerId = providers.get(index).id();
        catalogChanged = true;
        showProvider(shared);
    }

    void addProvider() {
        if (!stageProvider()) return;
        AiSettings shared = current();
        providerId = UUID.randomUUID().toString();
        providers.add(ApiProvider.of(providerId, "Провайдер " + (providers.size() + 1), AiSettings.defaults()));
        catalogChanged = true;
        showProvider(shared);
    }

    void deleteProvider() {
        if (providers.size() < 2) return;
        AiSettings shared = current();
        deletedProviders.add(providerId);
        tokenEdits.remove(providerId);
        providers.remove(providerIndex());
        providerId = providers.get(0).id();
        catalogChanged = true;
        showProvider(shared);
    }

    private void showProvider(AiSettings shared) {
        connectionRevision++;
        filling = true;
        fill(providers.get(providerIndex()).apply(shared));
        tokenRemovalRequested = tokenEdits.containsKey(providerId) && tokenEdits.get(providerId).isEmpty();
        token.setText(tokenEdits.getOrDefault(providerId, ""));
        refreshProviders();
        filling = false;
        updateTokenState();
        checkResult.setText("");
        checkButton.setEnabled(true);
        listModels.setEnabled(true);
        validate();
    }

    private void createConnectionGroup(Composite page) {
        Group group = group(page, "Сервер модели", 3);

        label(group, "Протокол:");
        protocol = new Combo(group, SWT.READ_ONLY);
        for (Protocol value : Protocol.values()) {
            protocol.add(value.label());
        }
        GridDataFactory.fillDefaults().span(2, 1).applyTo(protocol);
        protocol.addListener(SWT.Selection, event -> updateWindowState());

        label(group, "Адрес:");
        baseUrl = new Text(group, SWT.BORDER);
        baseUrl.setMessage("https://llm.example.invalid или http://localhost:11434");
        baseUrl.setToolTipText("Адрес сервера (https://сервер), адрес с версией API (https://сервер/v1)"
                + " или полный адрес метода — подойдёт любой.");
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).applyTo(baseUrl);
        baseUrl.addModifyListener(event -> validate());

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

        label(group, "Модель:");
        model = new Text(group, SWT.BORDER);
        model.addModifyListener(event -> updateWindowState());
        GridDataFactory.fillDefaults().grab(true, false).applyTo(model);
        listModels = new Button(group, SWT.PUSH);
        listModels.setText("Список…");
        listModels.setToolTipText("Загрузить модели сервера (GET /v1/models) и выбрать из списка");
        listModels.addListener(SWT.Selection, event -> chooseModel());

        label(group, "Макс. токенов ответа:");
        maxTokens = new Text(group, SWT.BORDER);
        maxTokens.setMessage("по умолчанию");
        maxTokens.setToolTipText("Максимум выходных токенов одного запроса к модели, а не всего ответа с шагами инструментов."
                + " Пусто (0 в сохранённых настройках): OpenAI-совместимый — max_tokens не передаётся,"
                + " Anthropic — " + ModelConnection.ANTHROPIC_DEFAULT_MAX_TOKENS + ". Для автоматического значения очистите поле.");
        GridDataFactory.fillDefaults().hint(100, SWT.DEFAULT).span(2, 1).applyTo(maxTokens);
        maxTokens.addModifyListener(event -> validate());

        Label promptLabel = label(group, "Системный промпт:");
        GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.BEGINNING).indent(0, 3).applyTo(promptLabel);
        systemPrompt = new Text(group, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        systemPrompt.setToolTipText("Инструкция модели перед каждым диалогом. Пусто — без системного промпта."
                + " «Restore Defaults» возвращает промпт по умолчанию.");
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(300, systemPrompt.getLineHeight() * 4)
                .applyTo(systemPrompt);
                ScrollFrame.wrap(systemPrompt);

        label(group, "Лимит кода, символов:");
        contextLimit = new Text(group, SWT.BORDER);
        contextLimit.setMessage(String.valueOf(AiSettings.DEFAULT_CONTEXT_LIMIT));
        contextLimit.setToolTipText("Лимит приложенного к сообщению кода из EDT, а не всего запроса. Пусто — "
                + AiSettings.DEFAULT_CONTEXT_LIMIT + " символов; лишнее обрезается по строкам."
                + " Вопрос, история, инструкции и описания инструментов сюда не входят."
                + " Размер страниц результатов MCP определяется отдельно. Символы нельзя точно перевести в токены.");
        GridDataFactory.fillDefaults().hint(100, SWT.DEFAULT).span(2, 1).applyTo(contextLimit);
        contextLimit.addModifyListener(event -> validate());

        label(group, "Окно модели, токенов:");
        modelWindow = new Text(group, SWT.BORDER);
        modelWindow.setMessage("предположение по имени");
        modelWindow.setToolTipText("Общее окно входа и выхода одного запроса: инструкции, история, код, инструменты и ответ."
                + " Пусто (0 в сохранённых настройках) — предположение по имени/протоколу, не сведения сервера;"
                + " В OpenAI-совместимом протоколе Qwen и неизвестная модель — " + AiSettings.DEFAULT_MODEL_WINDOW + "."
                + " Укажите окно из конфигурации локального сервера (max_model_len, num_ctx),"
                + " а не максимальное окно семейства модели. Большое окно не говорит о качестве модели."
                + " Для автоматического значения очистите поле.");
        GridDataFactory.fillDefaults().hint(100, SWT.DEFAULT).span(2, 1).applyTo(modelWindow);
        modelWindow.addModifyListener(event -> {
            updateWindowState();
            validate();
        });

        new Label(group, SWT.NONE);
        windowState = new Label(group, SWT.WRAP);
        windowState.setToolTipText("Это окно, выбранное плагином для расчёта бюджета запроса. Сервер не опрашивается."
                + " Знак ≈ у токенов в чате означает оценку по длине текста, когда сервер не прислал usage;"
                + " это не точная токенизация. Размер окна и оценка занятого места — разные величины.");
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(300, SWT.DEFAULT).applyTo(windowState);

        new Label(group, SWT.NONE);
        toolsEnabled = new Button(group, SWT.CHECK);
        toolsEnabled.setText("Инструменты: модель сама читает проект и вызывает MCP-серверы");
        toolsEnabled.setToolTipText("Встроенные — только чтение: сведения о проекте, список объектов, чтение модуля, поиск"
                + " по коду. Инструменты MCP-серверов — с подтверждением каждого вызова. Выключите, если сервер модели"
                + " не поддерживает инструменты.");
        GridDataFactory.fillDefaults().span(2, 1).applyTo(toolsEnabled);

        checkButton = new Button(group, SWT.PUSH);
        checkButton.setText("Проверить подключение");
        GridDataFactory.fillDefaults().applyTo(checkButton);
        checkButton.addListener(SWT.Selection, event -> checkConnection());
        checkResult = new Label(group, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).hint(300, SWT.DEFAULT).span(2, 1).applyTo(checkResult);
    }

    /** Предпросмотр по текущим полям; не читает токен и не обращается к серверу. */
    private void updateWindowState() {
        if (windowState == null) {
            return;
        }
        int value = number(modelWindow);
        if (value < 0 || value > 0 && (value < AiSettings.MIN_MODEL_WINDOW || value > AiSettings.MAX_MODEL_WINDOW)) {
            windowState.setText("Для расчёта: исправьте значение окна модели");
        } else {
            ModelConnection connection = new ModelConnection(Protocol.values()[Math.max(0, protocol.getSelectionIndex())],
                    "", model.getText(), "", 0, "", TrustSettings.from(AiSettings.defaults()), value);
            windowState.setText("Для расчёта: " + connection.window() + " токенов · "
                    + (value > 0 ? "настройка" : "предположение по имени/протоколу"));
        }
        windowState.getParent().layout(true);
    }

    /** Список моделей сервера с текущими значениями полей; выбранная попадает в поле «Модель». */
    private void chooseModel() {
        AiSettings settings = current();
        if (settings.baseUrl().isEmpty()) {
            showCheckResult("Укажите адрес сервера.");
            return;
        }
        String tokenValue;
        try {
            tokenValue = tokenValue();
        } catch (Exception e) {
            showCheckResult("Не удалось прочитать токен из Secure Storage: " + e.getMessage());
            return;
        }
        int revision = connectionRevision;
        listModels.setEnabled(false);
        showCheckResult("Загружаю список моделей…");
        Display display = listModels.getDisplay();
        new ModelClient().models(ModelConnection.of(settings, tokenValue)).whenComplete((ids, error) -> display.asyncExec(() -> {
            if (listModels.isDisposed() || revision != connectionRevision) {
                return;
            }
            listModels.setEnabled(true);
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            if (cause != null) {
                showCheckResult("Список моделей не получен: " + cause.getMessage());
            } else if (ids.isEmpty()) {
                showCheckResult("Сервер не вернул ни одной модели.");
            } else {
                showCheckResult("Моделей на сервере: " + ids.size() + ".");
                // Диалог с поиском и прокруткой: меню с сотней моделей не помещалось на экран.
                ElementListSelectionDialog dialog = new ElementListSelectionDialog(listModels.getShell(), new LabelProvider()) {
                    @Override
                    protected org.eclipse.ui.dialogs.FilteredList createFilteredList(Composite parent) {
                        var list = super.createFilteredList(parent);
                        ScrollFrame.installTables(list);
                        return list;
                    }
                };
                dialog.setTitle("Модель");
                dialog.setMessage("Модели сервера (начните вводить имя для поиска):");
                dialog.setElements(ids.toArray());
                dialog.setMultipleSelection(false);
                if (ids.contains(model.getText())) {
                    dialog.setInitialSelections(model.getText());
                }
                if (dialog.open() == Window.OK && dialog.getFirstResult() instanceof String id) {
                    model.setText(id);
                }
            }
        }));
    }

    /** Токен из поля, а если оно пусто — сохранённый (если его не удаляют). */
    private String tokenValue() throws Exception {
        return !token.getText().isEmpty() ? token.getText() : tokenRemovalRequested || !store.hasToken(providerId) ? "" : store.loadToken(providerId);
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
            tokenValue = tokenValue();
        } catch (Exception e) {
            showCheckResult("Не удалось прочитать токен из Secure Storage: " + e.getMessage());
            return;
        }
        int revision = connectionRevision;
        checkButton.setEnabled(false);
        showCheckResult("Проверяю…");
        Display display = checkButton.getDisplay();
        check = new ModelClient().check(ModelConnection.of(settings, tokenValue));
        check.whenComplete((result, error) -> display.asyncExec(() -> {
            if (checkButton.isDisposed() || revision != connectionRevision) {
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

        serverViewer = CheckboxTableViewer.newCheckList(group, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.NO_SCROLL);
        Table table = serverViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridDataFactory.fillDefaults().grab(true, true).hint(SWT.DEFAULT, 120).applyTo(table);
        ScrollFrame.wrap(table);

        column("Имя", 140, McpServer::name);
        column("Транспорт", 80, server -> server.transport().label());
        column("Команда / URL", 220, McpServer::target);
        column("Инструменты", 140, server -> server.tools().isEmpty() ? "все" : server.tools());

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
            McpServerDialog dialog = new McpServerDialog(getShell(), null, names(null), TrustSettings.from(current()));
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
        checkServer = button(buttons, "Проверить");
        checkServer.setToolTipText("Подключиться к выбранному серверу и получить список инструментов");
        checkServer.addListener(SWT.Selection, event -> checkSelectedServer());

        serverResult = new Label(group, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).hint(300, SWT.DEFAULT).applyTo(serverResult);
        new Label(group, SWT.NONE).setText("Проверять связь:");
        mcpInterval = new Combo(group, SWT.READ_ONLY);
        mcpInterval.setItems("Периодическая проверка выключена", "Каждые 3 минуты", "Каждые 5 минут", "Каждые 10 минут");
        mcpInterval.setToolTipText("Первая проверка — при открытии чата. Занятый сервер проверяется после освобождения.");
        updateServerButtons();
    }

    /** Подключение к выбранному MCP-серверу в фоне; результат — под таблицей. */
    private void checkSelectedServer() {
        McpServer server = selectedServer();
        if (server == null) {
            return;
        }
        checkServer.setEnabled(false);
        showServerResult("Подключаюсь к «" + server.name() + "»…");
        TrustSettings trust = TrustSettings.from(current());
        McpHub shared = ChatView.mcpForCheck(server, trust);
        long revision = shared == null ? 0 : shared.revision();
        Display display = checkServer.getDisplay();
        Thread thread = new Thread(() -> {
            try (McpHub temporary = shared == null ? new McpHub() : null) {
                McpHub hub = shared == null ? temporary : shared;
                String result;
                try {
                    result = "«" + server.name() + "»: " + hub.checkDetails(server, trust, revision).text();
                } catch (McpException e) {
                    result = "«" + server.name() + "» не работает: " + e.getMessage();
                }
                String text = result;
                McpHub.ServerStatus status = hub.lastStatus(List.of(server)).get(0);
                display.asyncExec(() -> {
                    if (!serverResult.isDisposed()) {
                        ChatView.mcpChecked(status, trust);
                        if (servers.contains(server)) showServerResult(text);
                        updateServerButtons();
                    }
                });
            }
        }, "EDT AI: проверка MCP-сервера");
        thread.setDaemon(true);
        thread.start();
    }

    private void showServerResult(String text) {
        serverResult.setText(text);
        serverResult.getShell().layout(new Control[] { serverResult });
    }

    private void editSelectedServer() {
        McpServer selected = selectedServer();
        if (selected == null) {
            return;
        }
        McpServerDialog dialog = new McpServerDialog(getShell(), selected, names(selected), TrustSettings.from(current()));
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
        if (checkServer != null) {
            checkServer.setEnabled(selected);
        }
    }

    private void fill(AiSettings settings) {
        protocol.select(settings.protocol().ordinal());
        baseUrl.setText(settings.baseUrl());
        model.setText(settings.model());
        maxTokens.setText(settings.maxTokens() > 0 ? String.valueOf(settings.maxTokens()) : "");
        systemPrompt.setText(settings.systemPrompt());
        contextLimit.setText(
                settings.contextLimit() == AiSettings.DEFAULT_CONTEXT_LIMIT ? "" : String.valueOf(settings.contextLimit()));
        modelWindow.setText(
                settings.modelWindow() == AiSettings.AUTO_MODEL_WINDOW ? "" : String.valueOf(settings.modelWindow()));
        toolsEnabled.setSelection(settings.toolsEnabled());
        useSystemTrustStore.setSelection(settings.useSystemTrustStore());
        caFile.setText(settings.caCertificateFile());
        servers.clear();
        servers.addAll(settings.mcpServers());
        mcpInterval.select(List.of(0, 3, 5, 10).indexOf(settings.mcpCheckMinutes()));
        refreshServers();
        updateCaState();
    }

    AiSettings current() {
        return new AiSettings(Protocol.values()[Math.max(0, protocol.getSelectionIndex())], baseUrl.getText(),
                model.getText(), Math.max(0, maxTokensValue()), systemPrompt.getText(), Math.max(0, number(contextLimit)),
                toolsEnabled.getSelection(), useSystemTrustStore.getSelection(), caFile.getText(), servers, Math.max(0, number(modelWindow)),
                List.of(0, 3, 5, 10).get(Math.max(0, mcpInterval.getSelectionIndex())), catalogChanged ? providerId : "");
    }

    private int maxTokensValue() {
        return number(maxTokens);
    }

    /** @return число из поля, 0 — пусто, -1 — не число */
    private static int number(Text field) {
        String text = field.getText().strip();
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
        if (baseUrl == null || maxTokens == null || contextLimit == null || modelWindow == null || caFile == null || serverViewer == null) {
            return;
        }
        if (filling) return;
        List<String> errors = new ArrayList<>(SettingsValidator.validate(current()));
        if (providerName.getText().isBlank()) errors.add(0, "Укажите имя провайдера");
        if (number(modelWindow) < 0) {
            errors.add(0, "Окно модели: нужно целое положительное число или пусто");
        }
        if (number(contextLimit) < 0) {
            errors.add(0, "Лимит контекста: нужно целое положительное число или пусто");
        }
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
            return store.hasToken(providerId);
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
        tokenEdits.clear();
        if (check != null) {
            check.cancel(true);
        }
        super.dispose();
    }

    @Override
    protected void performDefaults() {
        filling = true;
        fill(AiSettings.defaults());
        filling = false;
        localDiagnostics.setSelection(false);
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
        if (!stageProvider()) return false;
        try {
            for (var entry : tokenEdits.entrySet()) store.saveToken(entry.getKey(), entry.getValue());
            if (catalogChanged) store.saveProviders(providers, providerId);
            store.save(settings);
            var preferences = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER);
            preferences.putBoolean(UiDiagnosticLog.ENABLED, localDiagnostics.getSelection());
            preferences.flush();
            for (String deleted : deletedProviders) store.saveToken(deleted, "");
            deletedProviders.clear();
            tokenEdits.clear();
            token.setText("");
            tokenRemovalRequested = false;
            updateTokenState();
            return true;
        } catch (Exception e) {
            Platform.getLog(getClass()).error("Не удалось сохранить настройки EDT AI", e);
            setErrorMessage("Не удалось сохранить настройки: " + e.getMessage());
            return false;
        }
    }

    Combo providerChoice() { return providerChoice; }
    Text providerNameField() { return providerName; }
    Text tokenField() { return token; }
    Button localDiagnosticsField() { return localDiagnostics; }

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

    Text contextLimitField() {
        return contextLimit;
    }

    Text modelWindowField() {
        return modelWindow;
    }

    Combo protocolField() {
        return protocol;
    }

    Label windowState() {
        return windowState;
    }

    Button checkServerButton() {
        return checkServer;
    }

    Label serverResult() {
        return serverResult;
    }

    Combo mcpIntervalField() { return mcpInterval; }

    CheckboxTableViewer serverViewer() {
        return serverViewer;
    }

    Button checkButton() {
        return checkButton;
    }

    Label checkResult() {
        return checkResult;
    }
}
