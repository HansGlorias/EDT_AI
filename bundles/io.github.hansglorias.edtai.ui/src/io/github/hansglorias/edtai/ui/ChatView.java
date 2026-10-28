package io.github.hansglorias.edtai.ui;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.progress.IWorkbenchSiteProgressService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorExtension2;
import org.osgi.framework.FrameworkUtil;

import io.github.hansglorias.edtai.core.chat.ChatHistoryFile;
import io.github.hansglorias.edtai.core.chat.ChatListener;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatMessage.Status;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.ChatSession;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.context.CodeInsertion;
import io.github.hansglorias.edtai.core.context.ContextItem;
import io.github.hansglorias.edtai.core.llm.ModelChatProvider;
import io.github.hansglorias.edtai.core.llm.ModelClient;
import io.github.hansglorias.edtai.core.mcp.McpHub;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.SettingsStore;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.ProjectTools;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolApprover.Decision;

/**
 * Вид «AI Chat»: лента диалога, панель прикреплённых фрагментов кода и
 * поле ввода. Enter — отправить, Shift+Enter — новая строка. Ответ модели
 * появляется по мере генерации, его можно остановить кнопкой «Стоп» или Esc.
 * Диалог сохраняется в рабочей области и восстанавливается при следующем
 * открытии вида.
 */
public class ChatView extends ViewPart {

    public static final String ID = "io.github.hansglorias.edtai.ui.chatView";

    static final String SEND = "Отправить";
    static final String STOP = "Стоп";
    static final String RETRY = MessageRenderer.RETRY;
    static final String HISTORY_FILE = "chat.json";

    /** Как часто перерисовывать ленту во время стриминга. */
    private static final int REFRESH_DELAY_MS = 40;
    private static final int MIN_INPUT_LINES = 2;
    private static final int MAX_INPUT_LINES = 10;
    private static final int NOTICE_MS = 4000;

    /** Подключения к MCP-серверам из настроек. */
    private final McpHub mcp = new McpHub();
    /** Инструменты, которые пользователь разрешил «в этом чате»; сбрасываются «Новым чатом». */
    private final Set<String> allowedInChat = ConcurrentHashMap.newKeySet();
    private final ChatSession session = new ChatSession(
            ModelChatProvider.forStore(SettingsStore.workspace(), new ModelClient(), this::tools, this::approve));
    private final ChatHistoryFile historyFile = new ChatHistoryFile(
            Platform.getStateLocation(FrameworkUtil.getBundle(ChatView.class)).toFile().toPath().resolve(HISTORY_FILE));

    private Composite root;
    private Composite top;
    private StackLayout topLayout;
    private StyledText history;
    private MessageRenderer renderer;
    private Composite emptyState;
    private Label emptyTitle;
    private Label emptyText;
    private Link emptyLink;
    private ApprovalBar approvalBar;
    private ContextBar contextBar;
    private Text input;
    private Button sendButton;
    private Label status;
    private Cursor handCursor;

    /**
     * Что сейчас нарисовано в ленте: сообщение, его действия, с какого символа
     * оно начинается и где в нём блоки кода (для фона).
     */
    private record Rendered(ChatMessage message, Set<MessageRenderer.Action> actions, int start, List<int[]> codeRanges) {
    }

    private final List<Rendered> rendered = new ArrayList<>();
    private boolean refreshScheduled;
    private List<ChatMessage> saved;
    private String notice;

    /** Отправленные сообщения для ↑ / ↓ в пустом поле; {@code recall} — какое сейчас показано. */
    private int recall = -1;
    private boolean recalling;

    private final ChatListener chatListener = new ChatListener() {
        @Override
        public void messageAdded(ChatMessage message) {
            scheduleRefresh();
        }

        @Override
        public void messageUpdated(int index, ChatMessage message) {
            scheduleRefresh();
        }

        @Override
        public void historyChanged() {
            scheduleRefresh();
        }

        @Override
        public void busyChanged(boolean busy) {
            scheduleRefresh();
            if (!busy) {
                inUi(() -> approvalBar.dismiss());
            }
        }
    };

    /** Настройки для подсказок и строки состояния; запросы к модели читают их сами. */
    private AiSettings settings = AiSettings.defaults();

    private final IPreferenceChangeListener preferenceListener = event -> inUi(() -> {
        AiSettings previous = settings;
        settings = SettingsStore.workspace().load();
        if (!previous.mcpServers().equals(settings.mcpServers())
                || !TrustSettings.from(previous).equals(TrustSettings.from(settings))) {
            // Закрытие сессий — запросы по сети, а вызов инструмента может идти минуты: не в UI-потоке.
            background("EDT AI: переподключение MCP", mcp::reset);
        }
        contextBar.setLimit(settings.contextLimit());
        updateStatus();
        updateEmptyState();
    });

    @Override
    public void createPartControl(Composite parent) {
        root = parent;
        GridLayoutFactory.fillDefaults().spacing(0, 4).applyTo(parent);
        handCursor = parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND);

        top = new Composite(parent, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(top);
        topLayout = new StackLayout();
        top.setLayout(topLayout);
        createHistory(top);
        createEmptyState(top);
        approvalBar = new ApprovalBar(parent, this::approvalChanged);
        createContextBar(parent);
        createInput(parent);

        status = new Label(parent, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).indent(4, 0).applyTo(status);

        createActions();

        session.addListener(chatListener);
        preferences().addPreferenceChangeListener(preferenceListener);
        settings = SettingsStore.workspace().load();
        contextBar.setLimit(settings.contextLimit());
        restoreHistory();
        updateStatus();
        updateEmptyState();
        updateSendButton();
        refresh();
    }

    private void createHistory(Composite parent) {
        history = new StyledText(parent, SWT.MULTI | SWT.V_SCROLL | SWT.WRAP | SWT.READ_ONLY | SWT.BORDER);
        history.setMargins(8, 8, 8, 8);
        renderer = new MessageRenderer(history, new MessageRenderer.Callbacks() {
            @Override
            public void copy(String text) {
                copyToClipboard(text);
            }

            @Override
            public void insert(String code) {
                insertIntoEditor(code);
            }

            @Override
            public void retry() {
                if (session.canRetry()) {
                    session.retry();
                }
            }

            @Override
            public void edit(int index) {
                editMessage(index);
            }

            @Override
            public void open(String url) {
                openLink(url);
            }

            @Override
            public void openContext(ContextItem item) {
                IFile file = ResourcesPlugin.getWorkspace().getRoot().getProject(item.project()).getFile(item.path());
                reveal(file, item.whole() ? 0 : item.firstLine(), item.requestedLastLine());
            }
        });
        history.addListener(SWT.MouseUp, event -> {
            if (event.button == 1 && history.getSelectionCount() == 0) {
                Runnable action = actionAt(event.x, event.y);
                if (action != null) {
                    action.run();
                }
            }
        });
        history.addListener(SWT.MouseMove, event -> history.setCursor(actionAt(event.x, event.y) != null ? handCursor : null));
        // Фон блока кода — на всю ширину ленты, а не только под символами.
        history.addLineBackgroundListener(event -> {
            if (isInCode(event.lineOffset)) {
                event.lineBackground = renderer.codeBackground();
            }
        });

        Menu menu = new Menu(history);
        MenuItem copy = new MenuItem(menu, SWT.PUSH);
        copy.setText("Копировать\tCtrl+C");
        copy.addListener(SWT.Selection, event -> history.copy());
        MenuItem selectAll = new MenuItem(menu, SWT.PUSH);
        selectAll.setText("Выделить всё\tCtrl+A");
        selectAll.addListener(SWT.Selection, event -> history.selectAll());
        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem newChat = new MenuItem(menu, SWT.PUSH);
        newChat.setText("Новый чат");
        newChat.addListener(SWT.Selection, event -> newChat());
        menu.addListener(SWT.Show, event -> copy.setEnabled(history.getSelectionCount() > 0));
        history.setMenu(menu);
        history.addListener(SWT.KeyDown, event -> {
            if ((event.stateMask & SWT.MOD1) != 0 && (event.keyCode == 'a' || event.keyCode == 'A')) {
                history.selectAll();
            }
        });
    }

    private void createEmptyState(Composite parent) {
        emptyState = new Composite(parent, SWT.BORDER);
        emptyState.setBackground(history.getBackground());
        emptyState.setBackgroundMode(SWT.INHERIT_FORCE);
        GridLayoutFactory.swtDefaults().margins(16, 16).spacing(0, 8).applyTo(emptyState);

        emptyTitle = new Label(emptyState, SWT.WRAP);
        emptyTitle.setFont(JFaceResources.getBannerFont());
        GridDataFactory.fillDefaults().grab(true, false).applyTo(emptyTitle);

        emptyText = new Label(emptyState, SWT.WRAP);
        GridDataFactory.fillDefaults().grab(true, false).hint(200, SWT.DEFAULT).applyTo(emptyText);

        emptyLink = new Link(emptyState, SWT.NONE);
        emptyLink.setText("<a>Открыть настройки EDT AI</a>");
        emptyLink.addListener(SWT.Selection, event -> openPreferences());
        GridDataFactory.fillDefaults().applyTo(emptyLink);
    }

    private void createContextBar(Composite parent) {
        contextBar = new ContextBar(parent, new ContextBar.Callbacks() {
            @Override
            public void open(AttachedContext context) {
                int[] lines = context.lines();
                reveal(context.file(), lines[0], lines[1]);
            }

            @Override
            public void changed() {
                root.layout(true, true);
            }
        });
    }

    private void createInput(Composite parent) {
        Composite bottom = new Composite(parent, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(bottom);
        GridLayoutFactory.fillDefaults().numColumns(2).applyTo(bottom);

        input = new Text(bottom, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        input.setMessage("Сообщение (Enter — отправить, Shift+Enter — новая строка)");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(input);
        input.addListener(SWT.KeyDown, this::onInputKey);
        input.addModifyListener(event -> {
            if (!recalling) {
                recall = -1;
            }
            updateSendButton();
            updateInputHeight();
        });
        input.addListener(SWT.Resize, event -> updateInputHeight());
        // Пока пользователь правил модуль, строки фрагментов могли сдвинуться.
        input.addListener(SWT.FocusIn, event -> {
            if (!contextBar.isEmpty()) {
                contextBar.refresh();
            }
        });

        sendButton = new Button(bottom, SWT.PUSH);
        int width = 0;
        for (String text : new String[] { SEND, STOP }) {
            sendButton.setText(text);
            width = Math.max(width, sendButton.computeSize(SWT.DEFAULT, SWT.DEFAULT).x);
        }
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.END).hint(width, SWT.DEFAULT).applyTo(sendButton);
        sendButton.addListener(SWT.Selection, event -> sendOrStop());
        updateInputHeight();
    }

    private void createActions() {
        ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();

        Action newChat = new Action("Новый чат") {
            @Override
            public void run() {
                newChat();
                input.setFocus();
            }
        };
        newChat.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_TOOL_NEW_WIZARD));
        toolBar.add(newChat);

        Action attachModule = new Action("Прикрепить модуль…") {
            @Override
            public void run() {
                attachModule();
            }
        };
        attachModule.setToolTipText("Прикрепить к вопросу модуль целиком: поиск по имени объекта или пути");
        attachModule.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_OBJ_FILE));
        toolBar.add(attachModule);

        Action preferencesAction = new Action("Настройки EDT AI") {
            @Override
            public void run() {
                openPreferences();
            }
        };
        preferencesAction.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_DEF_VIEW));
        toolBar.add(preferencesAction);
    }

    private void newChat() {
        session.clear();
        allowedInChat.clear();
    }

    /** Вопрос о вызове появился или снят: перекомпоновать вид; новый вопрос — пометить вкладку вида. */
    private void approvalChanged() {
        if (approvalBar == null || status == null) {
            return; // панель создаётся
        }
        root.layout(true, true);
        updateStatus();
        if (approvalBar.isShown()) {
            IWorkbenchSiteProgressService progress = getSite().getService(IWorkbenchSiteProgressService.class);
            if (progress != null) {
                progress.warnOfContentChange();
            }
        }
    }

    private void openPreferences() {
        PreferencesUtil.createPreferenceDialogOn(getSite().getShell(), AiPreferencePage.ID,
                new String[] { AiPreferencePage.ID }, null).open();
    }

    // ---- ввод ----

    void onInputKey(Event event) {
        boolean enter = event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR;
        if (enter && (event.stateMask & SWT.SHIFT) == 0) {
            event.doit = false;
            send();
        } else if (event.keyCode == SWT.ESC && approvalBar.isShown()) {
            event.doit = false;
            approvalBar.deny();
        } else if (event.keyCode == SWT.ESC && session.isBusy()) {
            event.doit = false;
            session.cancel();
        } else if ((event.keyCode == SWT.ARROW_UP || event.keyCode == SWT.ARROW_DOWN) && event.stateMask == 0
                && (input.getText().isEmpty() || recall >= 0)) {
            event.doit = !recallSent(event.keyCode == SWT.ARROW_UP ? -1 : 1);
        }
    }

    /**
     * Показывает в поле ввода предыдущее ({@code -1}) или следующее ({@code +1})
     * отправленное сообщение.
     *
     * @return {@code true}, если поле изменилось
     */
    private boolean recallSent(int direction) {
        List<String> sent = session.messages().stream().filter(message -> message.role() == ChatRole.USER).map(ChatMessage::text).toList();
        if (sent.isEmpty()) {
            return false;
        }
        int next;
        if (direction < 0) {
            next = recall < 0 ? sent.size() - 1 : Math.max(0, recall - 1);
        } else if (recall < 0) {
            return false;
        } else {
            next = recall + 1;
        }
        recalling = true;
        try {
            if (next >= sent.size()) {
                recall = -1;
                input.setText("");
            } else {
                recall = next;
                input.setText(sent.get(next));
                input.setSelection(input.getText().length());
            }
        } finally {
            recalling = false;
        }
        return true;
    }

    /** Высота поля по тексту: от {@link #MIN_INPUT_LINES} до {@link #MAX_INPUT_LINES} строк. */
    private void updateInputHeight() {
        if (input == null || input.isDisposed()) {
            return;
        }
        int width = input.getSize().x > 0 ? input.getSize().x : SWT.DEFAULT;
        int lineHeight = input.getLineHeight();
        int trim = input.computeTrim(0, 0, 0, 0).height;
        int content = input.computeSize(width, SWT.DEFAULT).y - trim;
        int lines = Math.max(MIN_INPUT_LINES, Math.min(MAX_INPUT_LINES, (content + lineHeight - 1) / lineHeight));
        int height = lines * lineHeight + trim;
        GridData data = (GridData) input.getLayoutData();
        if (data.heightHint != height) {
            data.heightHint = height;
            root.layout(true, true);
        }
    }

    private void sendOrStop() {
        if (session.isBusy()) {
            session.cancel();
        } else {
            send();
        }
    }

    /** Отправляет текст из поля ввода. Ничего не делает, если текст пуст или ждём ответа. */
    void send() {
        String text = input.getText();
        if (text.isBlank() || session.isBusy()) {
            return;
        }
        List<ContextItem> context = contextBar.snapshot();
        input.setText("");
        session.send(text, context);
        contextBar.clear();
    }

    /** Добавляет фрагмент на панель над полем ввода и ставит фокус в поле. */
    void attach(AttachedContext context) {
        if (!contextBar.add(context)) {
            showNotice("Этот фрагмент уже прикреплён");
        }
        input.setFocus();
    }

    private void attachModule() {
        ModuleSelectionDialog dialog = new ModuleSelectionDialog(getSite().getShell());
        if (dialog.open() != Window.OK) {
            return;
        }
        for (Object item : dialog.getResult()) {
            if (item instanceof IFile file) {
                attach(AttachedContext.wholeFile(file));
            }
        }
    }

    private void editMessage(int index) {
        if (session.isBusy()) {
            showNotice("Дождитесь ответа или остановите его, чтобы изменить сообщение");
            return;
        }
        ChatMessage message = session.editFrom(index);
        input.setText(message.text());
        input.setSelection(message.text().length());
        contextBar.clear();
        message.context().forEach(item -> contextBar.add(AttachedContext.fromItem(item)));
        input.setFocus();
    }

    // ---- действия в ленте ----

    private void copyToClipboard(String text) {
        Clipboard clipboard = new Clipboard(history.getDisplay());
        try {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
        showNotice("Скопировано в буфер обмена");
    }

    /**
     * Заменяет выделение в последнем активном текстовом редакторе; отменяется
     * через Ctrl+Z. Переводы строк — как в документе, вторая и следующие
     * строки получают отступ строки, куда вставляем.
     */
    void insertIntoEditor(String code) {
        IEditorPart editor = getSite().getPage().getActiveEditor();
        ITextEditor textEditor = EditorAccess.textEditor(editor);
        if (textEditor == null) {
            showNotice("Откройте модуль в редакторе, чтобы вставить код");
            return;
        }
        if (!textEditor.isEditable()
                || textEditor instanceof ITextEditorExtension2 extension && !extension.validateEditorInputState()) {
            showNotice("Редактор открыт только для чтения");
            return;
        }
        IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
        if (!(textEditor.getSelectionProvider().getSelection() instanceof ITextSelection selection) || document == null) {
            showNotice("Не удалось вставить код в редактор");
            return;
        }
        String inserted;
        try {
            int lineStart = document.getLineOffset(document.getLineOfOffset(selection.getOffset()));
            inserted = CodeInsertion.adapt(code, document.get(lineStart, selection.getOffset() - lineStart),
                    TextUtilities.getDefaultLineDelimiter(document));
            document.replace(selection.getOffset(), selection.getLength(), inserted);
        } catch (BadLocationException e) {
            showNotice("Не удалось вставить код в редактор: " + e.getMessage());
            return;
        }
        textEditor.selectAndReveal(selection.getOffset(), inserted.length());
        getSite().getPage().activate(editor);
        showNotice("Код вставлен в " + editor.getTitle() + " (Ctrl+Z — отменить)");
    }

    /** Открывает файл фрагмента на его строках (с 1); {@code firstLine <= 0} — только открыть. */
    private void reveal(IFile file, int firstLine, int lastLine) {
        if (!file.exists()) {
            showNotice("Файл не найден: " + file.getFullPath().makeRelative());
            return;
        }
        try {
            EditorAccess.reveal(getSite().getPage(), file, firstLine, lastLine);
        } catch (PartInitException e) {
            showNotice("Не удалось открыть " + file.getName() + ": " + e.getMessage());
        }
    }

    private void openLink(String url) {
        String scheme = url.contains(":") ? url.substring(0, url.indexOf(':')).toLowerCase(Locale.ROOT) : "";
        if (!Set.of("http", "https", "mailto").contains(scheme)) {
            showNotice("Ссылка не открыта: поддерживаются только http, https и mailto");
            return;
        }
        try {
            PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(new URL(url));
        } catch (Exception e) {
            showNotice("Не удалось открыть ссылку: " + e.getMessage());
        }
    }

    /** Короткое сообщение в строке состояния. */
    void showNotice(String text) {
        notice = text;
        updateStatus();
        history.getDisplay().timerExec(NOTICE_MS, () -> {
            if (!status.isDisposed() && Objects.equals(notice, text)) {
                notice = null;
                updateStatus();
            }
        });
    }

    // ---- лента ----

    private void scheduleRefresh() {
        inUi(() -> {
            if (!refreshScheduled) {
                refreshScheduled = true;
                history.getDisplay().timerExec(REFRESH_DELAY_MS, () -> {
                    refreshScheduled = false;
                    if (!history.isDisposed()) {
                        refresh();
                    }
                });
            }
        });
    }

    /**
     * Приводит ленту в соответствие с историей диалога: перерисовывает всё,
     * начиная с первого изменившегося сообщения (при стриминге — только
     * последнее).
     */
    void refresh() {
        List<ChatMessage> messages = session.messages();
        boolean idle = !session.isBusy();
        List<Set<MessageRenderer.Action>> actions = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            actions.add(actions(messages, i, idle));
        }
        int first = 0;
        while (first < rendered.size() && first < messages.size()
                && rendered.get(first).message().equals(messages.get(first))
                && rendered.get(first).actions().equals(actions.get(first))) {
            first++;
        }
        if (first < rendered.size() || first < messages.size()) {
            boolean atBottom = isScrolledToBottom();
            int start = first < rendered.size() ? rendered.get(first).start() : history.getCharCount();
            rendered.subList(first, rendered.size()).clear();

            StringBuilder text = new StringBuilder();
            List<StyleRange> styles = new ArrayList<>();
            for (int i = first; i < messages.size(); i++) {
                List<int[]> codeRanges = new ArrayList<>();
                rendered.add(new Rendered(messages.get(i), actions.get(i), start + text.length(), codeRanges));
                renderer.render(messages.get(i), i, i > 0, actions.get(i), start, text, styles, codeRanges);
            }
            history.replaceTextRange(start, history.getCharCount() - start, text.toString());
            history.replaceStyleRanges(start, text.length(), styles.toArray(StyleRange[]::new));
            history.redraw();
            if (atBottom) {
                history.setTopPixel(Integer.MAX_VALUE);
            }
        }
        if (idle && !messages.equals(saved)) {
            saveHistory(messages);
        }
        updateEmptyState();
        updateSendButton();
        updateStatus();
    }

    private static Set<MessageRenderer.Action> actions(List<ChatMessage> messages, int index, boolean idle) {
        ChatMessage message = messages.get(index);
        boolean last = index == messages.size() - 1;
        Set<MessageRenderer.Action> actions = EnumSet.noneOf(MessageRenderer.Action.class);
        switch (message.role()) {
            // «Изменить» видно всегда: иначе каждая отправка перерисовывала бы всю ленту.
            case USER -> actions.add(MessageRenderer.Action.EDIT);
            case ASSISTANT -> {
                if (!message.text().isEmpty() && message.status() != Status.STREAMING) {
                    actions.add(MessageRenderer.Action.COPY);
                }
                if (last && idle) {
                    actions.add(MessageRenderer.Action.RETRY);
                }
            }
            case ERROR -> {
                if (last && idle) {
                    actions.add(MessageRenderer.Action.RETRY);
                }
            }
        }
        return actions;
    }

    private boolean isInCode(int offset) {
        for (Rendered item : rendered) {
            for (int[] range : item.codeRanges()) {
                if (offset >= range[0] && offset <= range[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    private Runnable actionAt(int x, int y) {
        int offset = history.getOffsetAtPoint(new Point(x, y));
        if (offset < 0 || offset >= history.getCharCount()) {
            return null;
        }
        StyleRange style = history.getStyleRangeAtOffset(offset);
        return style != null && style.data instanceof Runnable action ? action : null;
    }

    private boolean isScrolledToBottom() {
        int bottom = history.getLinePixel(history.getLineCount());
        return bottom <= history.getClientArea().height + history.getLineHeight();
    }

    // ---- сохранение диалога ----

    private void restoreHistory() {
        try {
            List<ChatMessage> messages = historyFile.load();
            session.restore(messages);
            saved = messages;
        } catch (IOException e) {
            Platform.getLog(getClass()).warn("Не удалось восстановить диалог EDT AI из " + historyFile.path(), e);
        }
    }

    private void saveHistory(List<ChatMessage> messages) {
        try {
            historyFile.save(messages);
            saved = messages;
        } catch (IOException e) {
            saved = messages;
            Platform.getLog(getClass()).warn("Не удалось сохранить диалог EDT AI в " + historyFile.path(), e);
        }
    }

    // ---- состояние ----

    private void updateEmptyState() {
        boolean configured = settings.isConfigured();
        String title = configured ? "Чем помочь?" : "Модель не настроена";
        String text = configured
                ? "Задайте вопрос модели " + settings.model() + ". Enter — отправить, Shift+Enter — новая строка,"
                        + " Esc — остановить ответ. Чтобы спросить о коде, выделите его в модуле и нажмите Ctrl+Alt+A."
                : "Укажите адрес сервера, протокол и модель в настройках EDT AI. Для локальной Ollama или"
                        + " LM Studio токен не нужен.";
        if (!title.equals(emptyTitle.getText()) || !text.equals(emptyText.getText())) {
            emptyTitle.setText(title);
            emptyText.setText(text);
            emptyLink.setVisible(!configured);
            ((GridData) emptyLink.getLayoutData()).exclude = configured;
            emptyState.layout(true, true);
        }
        Control shown = session.messages().isEmpty() ? emptyState : history;
        if (topLayout.topControl != shown) {
            topLayout.topControl = shown;
            top.layout(true);
        }
    }

    private void updateSendButton() {
        if (sendButton.isDisposed()) {
            return;
        }
        boolean busy = session.isBusy();
        String text = busy ? STOP : SEND;
        if (!Objects.equals(sendButton.getText(), text)) {
            sendButton.setText(text);
        }
        sendButton.setEnabled(busy || !input.getText().isBlank());
    }

    private void updateStatus() {
        String text;
        if (notice != null) {
            text = notice;
        } else if (!settings.isConfigured()) {
            text = "Модель не настроена";
        } else {
            String state = approvalBar != null && approvalBar.isShown() ? " · ждёт вашего решения"
                    : session.isBusy() ? " · генерирует ответ…" : "";
            text = settings.model() + " · " + host(settings.baseUrl()) + state;
        }
        if (!text.equals(status.getText())) {
            status.setText(text);
            status.getParent().layout(true);
        }
    }

    private static String host(String url) {
        try {
            String host = new URI(url).getHost();
            return host != null ? host : url;
        } catch (Exception e) {
            return url;
        }
    }

    private void inUi(Runnable runnable) {
        Display display = history == null || history.isDisposed() ? null : history.getDisplay();
        if (display == null) {
            return;
        }
        display.asyncExec(() -> {
            if (!history.isDisposed()) {
                runnable.run();
            }
        });
    }

    /**
     * Инструменты для очередного ответа: встроенные и включённых MCP-серверов.
     * Вызывается в фоновом потоке ответа: подключение к серверу может занять
     * время.
     */
    private List<Tool> tools(Consumer<ToolUse> problems) {
        AiSettings current = SettingsStore.workspace().load();
        if (!current.toolsEnabled()) {
            return List.of();
        }
        List<Tool> tools = new ArrayList<>(ProjectTools.create(EclipseWorkspaceFiles.current(), current::contextLimit));
        Set<String> builtIn = tools.stream().map(Tool::name).collect(Collectors.toSet());
        tools.addAll(mcp.tools(current.mcpServers(), TrustSettings.from(current), builtIn, current::contextLimit, problems));
        return tools;
    }

    /** Вопрос пользователю перед вызовом инструмента MCP-сервера: панель над полем ввода. */
    private CompletableFuture<Decision> approve(ToolUse use, String arguments) {
        if (allowedInChat.contains(use.name())) {
            return CompletableFuture.completedFuture(Decision.ALLOW);
        }
        CompletableFuture<Decision> decision = new CompletableFuture<>();
        decision.thenAccept(value -> {
            if (value == Decision.ALLOW_IN_CHAT) {
                allowedInChat.add(use.name());
            }
        });
        inUi(() -> approvalBar.ask(use, arguments, decision));
        return decision;
    }

    private static void background(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static IEclipsePreferences preferences() {
        return InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER);
    }

    @Override
    public void setFocus() {
        input.setFocus();
    }

    @Override
    public void dispose() {
        contextBar.clear();
        session.removeListener(chatListener);
        session.cancel();
        // Закрытие сессий MCP — запросы по сети, не в UI-потоке.
        background("EDT AI: закрытие MCP", mcp::close);
        List<ChatMessage> messages = session.messages();
        if (!messages.equals(saved)) {
            saveHistory(messages);
        }
        preferences().removePreferenceChangeListener(preferenceListener);
        super.dispose();
    }

    // ---- для тестов ----

    ChatSession session() {
        return session;
    }

    ChatHistoryFile historyFile() {
        return historyFile;
    }

    Composite control() {
        return root;
    }

    Text input() {
        return input;
    }

    ContextBar contextBar() {
        return contextBar;
    }

    ApprovalBar approvalBar() {
        return approvalBar;
    }

    StyledText history() {
        return history;
    }

    Button sendButton() {
        return sendButton;
    }

    boolean isEmptyStateShown() {
        return topLayout.topControl == emptyState;
    }

    Label status() {
        return status;
    }

    /** Действие ссылки с текстом {@code text} в ленте или {@code null}; если таких несколько — последней. */
    Runnable linkAction(String text) {
        List<Runnable> actions = linkActions(text);
        return actions.isEmpty() ? null : actions.get(actions.size() - 1);
    }

    /** Действия всех ссылок с текстом {@code text}, по порядку в ленте. */
    List<Runnable> linkActions(String text) {
        List<Runnable> actions = new ArrayList<>();
        for (StyleRange style : history.getStyleRanges()) {
            if (style.data instanceof Runnable action && history.getTextRange(style.start, style.length).equals(text)) {
                actions.add(action);
            }
        }
        return actions;
    }

    /** Есть ли у ленты фон кода в строке, где стоит {@code offset}. */
    boolean hasCodeBackground(int offset) {
        return isInCode(history.getOffsetAtLine(history.getLineAtOffset(offset)));
    }
}
