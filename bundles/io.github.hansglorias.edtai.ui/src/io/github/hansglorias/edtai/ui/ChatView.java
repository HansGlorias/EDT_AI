package io.github.hansglorias.edtai.ui;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.compare.CompareUI;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.progress.IWorkbenchSiteProgressService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorExtension2;
import org.osgi.framework.FrameworkUtil;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.chat.ChatHistoryFile;
import io.github.hansglorias.edtai.core.chat.ChatListener;
import io.github.hansglorias.edtai.core.chat.ChatMarkdown;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ReplyActivity;
import io.github.hansglorias.edtai.core.chat.SlashCommand;
import io.github.hansglorias.edtai.core.chat.TokenUsage;
import io.github.hansglorias.edtai.core.chat.ChatMessage.Status;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.ChatSession;
import io.github.hansglorias.edtai.core.chat.ChatStore;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.context.CodeInsertion;
import io.github.hansglorias.edtai.core.context.ContextItem;
import io.github.hansglorias.edtai.core.llm.ModelChatProvider;
import io.github.hansglorias.edtai.core.llm.ModelClient;
import io.github.hansglorias.edtai.core.mcp.McpHub;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.SettingsStore;
import io.github.hansglorias.edtai.core.skills.Skill;
import io.github.hansglorias.edtai.core.skills.SkillLibrary;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.EditTools;
import io.github.hansglorias.edtai.core.tools.FileTools;
import io.github.hansglorias.edtai.core.tools.TextChange;
import io.github.hansglorias.edtai.core.tools.ToolException;
import io.github.hansglorias.edtai.core.tools.GitTools;
import io.github.hansglorias.edtai.core.tools.ProjectBrief;
import io.github.hansglorias.edtai.core.tools.ProjectTools;
import io.github.hansglorias.edtai.core.tools.SkillTools;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolApprover.Decision;
import io.github.hansglorias.edtai.core.tools.ToolSource;

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
    /** Единственный диалог до появления списка чатов: переносится в {@link #CHATS_DIRECTORY}. */
    static final String HISTORY_FILE = "chat.json";
    static final String CHATS_DIRECTORY = "chats";
    private static final int HISTORY_MENU = 30;
    private static final String MEMENTO_HISTORY = "historyShown";
    private static final String MEMENTO_HISTORY_WIDTH = "historyWidth";
    private static final DateTimeFormatter CHAT_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    static final String REVIEW_CHANGES = "Ревью моих изменений";
    static final String COMMIT_MESSAGE = "Сообщение коммита";
    static final String REVIEW_PROMPT = "Сделай ревью моих незакоммиченных изменений: посмотри git_status и git_diff, при"
            + " необходимости прочитай модули целиком. Найди ошибки, пропущенные случаи и нарушения стандартов 1С; по каждой"
            + " находке — Модуль:строка и как исправить. Если всё хорошо — так и скажи.";
    static final String COMMIT_PROMPT = "Напиши сообщение коммита для моих незакоммиченных изменений (посмотри git_status и"
            + " git_diff): первая строка — суть до 72 символов, по-русски, затем пустая строка и короткий список изменений."
            + " Только текст сообщения в блоке кода, без пояснений.";
    static final String EXPLAIN_PROMPT = "Объясни этот код: зачем он, что получает и что возвращает, основные шаги, побочные"
            + " эффекты (запись в базу, блокировки, вызовы сервера, транзакции) и неочевидные места. Если код вызывает другие"
            + " методы проекта и без них не понять — прочитай их. Ссылайся на строки: Модуль:строка.";
    static final String TESTS_PROMPT = "Напиши тесты для этого кода. Сначала посмотри (search_code, list_objects), есть ли в"
            + " проекте тесты и на чём: YAxUnit (общие модули с ИсполняемыеСценарии), Vanessa Automation (feature-файлы) —"
            + " пиши в том же стиле и скажи, куда положить. Тестов нет — на YAxUnit. Покрой обычный сценарий, граничные"
            + " случаи и ошибки. Модули тестов не создавай и не меняй — покажи код в ответе.";
    /** Файлы больше не читаем: картинка всё равно не пройдёт, текст — тоже. */
    private static final long MAX_FILE_BYTES = 10_000_000;
    private static final DateTimeFormatter SCREENSHOT_TIME = DateTimeFormatter.ofPattern("HHmmss");
    static final String SKILL_PROMPT = "Создай скилл по описанию ниже. Следуй приложенному скиллу create-skill; если описание"
            + " неясно — сначала задай вопросы; сохрани инструментом save_skill.";
    /** Придумывать ли название чата после первого ответа (запрос к модели). Хранится в настройках рабочей области. */
    static final String GENERATE_TITLES = "generateChatTitles";

    /** Как часто перерисовывать ленту во время стриминга. */
    private static final int REFRESH_DELAY_MS = 40;
    private static final int MIN_INPUT_LINES = 2;
    private static final int MAX_INPUT_LINES = 10;
    private static final int NOTICE_MS = 4000;
    /** Как часто обновлять секунды в строке состояния, пока идёт ответ. */
    private static final int TICK_MS = 1000;
    /** Авторежим: инструменты MCP вызываются без вопроса, во всех чатах. Хранится в настройках рабочей области. */
    static final String AUTO_APPROVE = "autoApproveTools";
    static final String AUTO_MODE = "Авторежим";
    /** Только вопросы: инструменты, меняющие проект, модели не передаются. Хранится в настройках рабочей области. */
    static final String READ_ONLY = "readOnlyMode";
    static final String READ_ONLY_MODE = "Только вопросы";

    /** Подключения к MCP-серверам из настроек. */
    private final McpHub mcp = new McpHub();
    /** Чаты, для которых уже просили название у модели: по разу. */
    private final Set<String> titleRequested = ConcurrentHashMap.newKeySet();
    /** Инструменты, которые пользователь разрешил «в этом чате»; сбрасываются «Новым чатом». */
    private final Set<String> allowedInChat = ConcurrentHashMap.newKeySet();
    private final ChatSession session = new ChatSession(
            ModelChatProvider.forStore(SettingsStore.workspace(), new ModelClient(), new ToolSource() {
                @Override
                public List<Tool> tools(Consumer<ToolUse> problems) {
                    return ChatView.this.tools(problems);
                }

                @Override
                public String brief() {
                    String brief = ProjectBrief.describe(EclipseWorkspaceFiles.current());
                    if (SettingsStore.workspace().load().toolsEnabled()) {
                        brief = (brief + "\n\n" + skills.prompt()).strip();
                    }
                    return isReadOnly() ? (brief + "\n\n" + ProjectBrief.READ_ONLY_RULES).strip() : brief;
                }
            }, this::approve));
    private final Path stateLocation = Platform.getStateLocation(FrameworkUtil.getBundle(ChatView.class)).toFile().toPath();
    /** Скиллы: встроенные, свои (в рабочей области) и открытых проектов. */
    private final SkillLibrary skills = new SkillLibrary(stateLocation.resolve("skills"), ChatView::projectSkillDirectories,
            Arrays.stream(SlashCommand.values()).map(command -> command.text().substring(1)).collect(Collectors.toSet()));
    /** Все диалоги; {@link #chatId} — открытый. */
    private final ChatStore chats = new ChatStore(stateLocation.resolve(CHATS_DIRECTORY));
    private String chatId;

    private Composite root;
    /** Правки модели в идущем ответе; по его окончании — в {@link #changes}. */
    private final List<EditTools.Change> pendingChanges = new CopyOnWriteArrayList<>();
    /** Правки модели по ответам (до перезапуска EDT): ключ — {@link #changeKey}. */
    private final Map<String, List<EditTools.Change>> changes = new HashMap<>();
    /**
     * Файлы {@code src/}, изменённые на диске за идущий ответ: так видно правки
     * инструментов MCP (EDT-MCP пишет метаданные и модули мимо плагина).
     */
    private final Set<IFile> touched = ConcurrentHashMap.newKeySet();
    /** Такие файлы по ответам: ключ — {@link #changeKey}. */
    private final Map<String, Set<IFile>> touchedByAnswer = new ConcurrentHashMap<>();
    /** EDT может дописать файлы чуть позже конца ответа: их — к этому ответу до {@link #lateUntil}. */
    private volatile String lateKey;
    private volatile long lateUntil;
    private static final long LATE_CHANGES_MS = 10_000;
    /** Развёрнутые группы вызовов: «ключ ответа#номер группы». */
    private final Set<String> expandedCalls = new java.util.HashSet<>();
    /** Открыт сохранённый чат — прокрутить ленту в конец. */
    private boolean scrollToEnd;
    private SashForm sash;
    /** Видна ли панель чатов и как поделена ширина: запоминаются между запусками (состояние вида). */
    private boolean historyShown;
    private int[] historyWeights = { 30, 70 };
    private Action historyAction;
    private ChatHistoryPanel historyPanel;
    private ThinScrollBar scrollBar;
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
    private Action autoAction;
    private Button readOnlyButton;
    private InputAssist inputAssist;
    /** Вкладка вида помечена как занятая (идёт ответ). */
    private boolean busyShown;
    private boolean ticking;
    /** Оценка занятого окна для открытого чата, пока не было ответа: пересчитывается, когда меняется история. */
    private long estimate;
    /** Окно модели из настроек, в токенах. */
    private long window;
    private List<ChatMessage> estimated;

    /**
     * Что сейчас нарисовано в ленте: сообщение, его действия, с какого символа
     * оно начинается и где в нём блоки кода (для фона).
     */
    private record Rendered(ChatMessage message, Set<MessageRenderer.Action> actions, List<String> files, Set<String> expanded,
            int start, List<int[]> codeRanges) {
    }

    private final List<Rendered> rendered = new ArrayList<>();
    private boolean refreshScheduled;
    private List<ChatMessage> saved = List.of();
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
        public void usageChanged(TokenUsage usage) {
            inUi(ChatView.this::updateStatus);
        }

        @Override
        public void busyChanged(boolean busy) {
            if (busy) {
                touched.clear();
                lateKey = null;
            }
            scheduleRefresh();
            inUi(() -> markBusy(busy));
            if (!busy) {
                inUi(() -> {
                    attachChanges();
                    approvalBar.dismiss();
                    generateTitle();
                });
            }
        }
    };

    private final IResourceChangeListener resourceListener = event -> {
        boolean busy = session.isBusy();
        String late = lateKey;
        if (event.getDelta() == null || !busy && (late == null || System.currentTimeMillis() > lateUntil)) {
            return;
        }
        List<IFile> files = new ArrayList<>();
        try {
            event.getDelta().accept(delta -> {
                IResource resource = delta.getResource();
                if (resource instanceof IFile file) {
                    boolean content = delta.getKind() != IResourceDelta.CHANGED
                            || (delta.getFlags() & (IResourceDelta.CONTENT | IResourceDelta.REPLACED)) != 0;
                    if (content && isSource(file)) {
                        files.add(file);
                    }
                    return false;
                }
                return !(resource instanceof IFolder folder) || !folder.isDerived() && !folder.getName().startsWith(".");
            });
        } catch (CoreException e) {
            return;
        }
        if (files.isEmpty()) {
            return;
        }
        if (busy) {
            touched.addAll(files);
        } else {
            touchedByAnswer.computeIfAbsent(late, key -> ConcurrentHashMap.newKeySet()).addAll(files);
            scheduleRefresh();
        }
    };

    /** Исходники конфигурации: служебное (сборка, настройки) в дифф не берём. */
    private static boolean isSource(IFile file) {
        return file.getProjectRelativePath().toString().startsWith("src/") && !file.isDerived() && !file.isTeamPrivateMember();
    }

    /** Настройки для подсказок и строки состояния; запросы к модели читают их сами. */
    private AiSettings settings = AiSettings.defaults();

    private final IPreferenceChangeListener preferenceListener = event -> inUi(() -> {
        AiSettings previous = settings;
        settings = SettingsStore.workspace().load();
        if (session.provider() instanceof ModelChatProvider provider) {
            provider.forgetRejections();
        }
        if (!previous.mcpServers().equals(settings.mcpServers())
                || !TrustSettings.from(previous).equals(TrustSettings.from(settings))) {
            // Закрытие сессий — запросы по сети, а вызов инструмента может идти минуты: не в UI-потоке.
            background("EDT AI: переподключение MCP", mcp::reset);
        }
        contextBar.setLimit(settings.contextLimit());
        if (autoAction != null) {
            autoAction.setChecked(isAutoApprove());
        }
        if (readOnlyButton != null && !readOnlyButton.isDisposed()) {
            readOnlyButton.setSelection(isReadOnly());
        }
        estimated = null;
        window = session.provider().window();
        updateStatus();
        updateEmptyState();
    });

    @Override
    public void createPartControl(Composite parent) {
        root = parent;
        GridLayoutFactory.fillDefaults().spacing(0, 4).applyTo(parent);
        handCursor = parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND);

        sash = new SashForm(parent, SWT.HORIZONTAL);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(sash);
        historyPanel = new ChatHistoryPanel(sash, new ChatHistoryPanel.Callbacks() {
            @Override
            public void open(String id) {
                openChat(id);
            }

            @Override
            public void rename(String id) {
                renameChat(id);
            }

            @Override
            public void export(String id) {
                exportChat(id);
            }

            @Override
            public void searchChanged() {
                refreshHistoryPanel();
            }

            @Override
            public void newChat() {
                ChatView.this.newChat();
                input.setFocus();
            }

            @Override
            public void delete(String id) {
                if (id.equals(chatId)) {
                    deleteChat();
                } else {
                    try {
                        chats.delete(id);
                    } catch (IOException e) {
                        showNotice("Не удалось удалить чат: " + e.getMessage());
                    }
                    refreshHistoryPanel();
                }
            }
        });
        top = new Composite(sash, SWT.NONE);
        sash.setWeights(historyWeights);
        sash.setMaximizedControl(historyShown ? null : top);
        topLayout = new StackLayout();
        top.setLayout(topLayout);
        createHistory(top);
        createEmptyState(top);
        approvalBar = new ApprovalBar(parent, this::approvalChanged);
        createContextBar(parent);
        createInput(parent);

        Composite statusRow = new Composite(parent, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(statusRow);
        GridLayoutFactory.fillDefaults().numColumns(2).extendedMargins(4, 4, 0, 0).applyTo(statusRow);
        status = new Label(statusRow, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).align(SWT.FILL, SWT.CENTER).applyTo(status);
        readOnlyButton = new Button(statusRow, SWT.CHECK);
        readOnlyButton.setText(READ_ONLY_MODE);
        readOnlyButton.setToolTipText("Модель только отвечает: инструменты правки (edit_module, git_restore, меняющие"
                + " инструменты MCP) ей не передаются. Команда /readonly");
        readOnlyButton.setSelection(isReadOnly());
        readOnlyButton.addListener(SWT.Selection, event -> setReadOnly(readOnlyButton.getSelection()));

        createActions();

        session.addListener(chatListener);
        preferences().addPreferenceChangeListener(preferenceListener);
        ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener, IResourceChangeEvent.POST_CHANGE);
        settings = SettingsStore.workspace().load();
        contextBar.setLimit(settings.contextLimit());
        window = session.provider().window();
        restoreHistory();
        updateStatus();
        updateEmptyState();
        updateSendButton();
        refresh();
        refreshHistoryPanel();
    }

    private void createHistory(Composite parent) {
        // Без системной полосы прокрутки: своя, узкая (ThinScrollBar).
        history = new StyledText(parent, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.BORDER);
        history.setMargins(12, 10, 12, 10);
        history.setLineSpacing(2);
        scrollBar = new ThinScrollBar(history);
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
            public void showChanges(ChatMessage answer) {
                ChatView.this.showChanges(answer);
            }

            @Override
            public void revertChanges(ChatMessage answer) {
                ChatView.this.revertChanges(answer);
            }

            @Override
            public List<String> changedFiles(ChatMessage answer) {
                return session.isBusy() ? List.of() : changedPaths(answer);
            }

            @Override
            public boolean expanded(ChatMessage answer, int group) {
                return expandedCalls.contains(changeKey(answer) + "#" + group);
            }

            @Override
            public void toggle(ChatMessage answer, int group) {
                String key = changeKey(answer) + "#" + group;
                if (!expandedCalls.remove(key)) {
                    expandedCalls.add(key);
                }
                refresh();
            }

            @Override
            public void openAttachment(Attachment file) {
                ChatView.this.openAttachment(file);
            }

            @Override
            public void saveAttachment(Attachment file) {
                ChatView.this.saveAttachment(file);
            }

            @Override
            public void showChange(ChatMessage answer, String path) {
                ChatView.this.showChanges(answer, path);
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
        history.addPaintObjectListener(event -> renderer.paintObject(event));
        history.addLineBackgroundListener(event -> {
            if (isInCode(event.lineOffset)) {
                event.lineBackground = renderer.codeBackground();
            } else if (isInQuestion(event.lineOffset)) {
                event.lineBackground = renderer.userBackground();
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
        GridLayoutFactory.fillDefaults().numColumns(3).applyTo(bottom);

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
        input.setMenu(inputMenu());
        inputAssist = new InputAssist(input, file -> attach(AttachedContext.wholeFile(file)), skills::all);
        // Пока пользователь правил модуль, строки фрагментов могли сдвинуться.
        input.addListener(SWT.FocusIn, event -> {
            if (!contextBar.isEmpty()) {
                contextBar.refresh();
            }
        });

        Button attachButton = new Button(bottom, SWT.PUSH);
        attachButton.setImage(Icons.image(Icons.ATTACH));
        attachButton.setToolTipText("Приложить файлы или скриншоты (картинку можно вставить в поле Ctrl+V или перетащить файл)");
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.END).applyTo(attachButton);
        attachButton.addListener(SWT.Selection, event -> chooseFiles());
        DropTarget drop = new DropTarget(input, DND.DROP_COPY | DND.DROP_DEFAULT);
        drop.setTransfer(FileTransfer.getInstance());
        drop.addDropListener(new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetEvent event) {
                event.detail = DND.DROP_COPY;
            }

            @Override
            public void drop(DropTargetEvent event) {
                if (event.data instanceof String[] paths) {
                    attachFiles(List.of(paths));
                }
            }
        });

        sendButton = new Button(bottom, SWT.PUSH);
        int width = 0;
        for (String text : new String[] { SEND, STOP }) {
            sendButton.setText(text);
            sendButton.setImage(STOP.equals(text) ? stopImage() : Icons.image(Icons.SEND));
            width = Math.max(width, sendButton.computeSize(SWT.DEFAULT, SWT.DEFAULT).x);
        }
        // Значок есть всегда: на Windows кнопка, у которой убрали картинку, оставляет под неё место и текст съезжает.
        sendButton.setImage(Icons.image(Icons.SEND));
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.END).hint(width, SWT.DEFAULT).applyTo(sendButton);
        sendButton.addListener(SWT.Selection, event -> sendOrStop());
        updateInputHeight();
    }

    private void createActions() {
        ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();

        historyAction = new Action("История чатов", IAction.AS_CHECK_BOX) {
            @Override
            public void run() {
                showHistoryPanel(isChecked());
            }
        };
        historyAction.setImageDescriptor(Icons.descriptor(Icons.HISTORY));
        historyAction.setChecked(historyShown);
        toolBar.add(historyAction);

        Action newChat = new Action("Новый чат") {
            @Override
            public void run() {
                newChat();
                input.setFocus();
            }
        };
        newChat.setImageDescriptor(Icons.descriptor(Icons.NEW_CHAT));
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
        preferencesAction.setImageDescriptor(Icons.descriptor(Icons.SETTINGS));

        autoAction = new Action(AUTO_MODE, IAction.AS_CHECK_BOX) {
            @Override
            public void run() {
                setAutoApprove(isChecked());
            }
        };
        autoAction.setToolTipText("Авторежим: модель вызывает инструменты MCP без вопроса (правка модулей и git — всегда без"
                + " вопроса). Команда /auto");
        autoAction.setImageDescriptor(Icons.descriptor(Icons.AUTO));
        autoAction.setChecked(isAutoApprove());
        toolBar.add(autoAction);
        toolBar.add(preferencesAction);

        getViewSite().getActionBars().setGlobalActionHandler(ActionFactory.PASTE.getId(), new Action() {
            @Override
            public void run() {
                paste();
            }
        });

        IMenuManager menu = getViewSite().getActionBars().getMenuManager();
        MenuManager historyMenu = new MenuManager("История чатов");
        historyMenu.setRemoveAllWhenShown(true);
        historyMenu.addMenuListener(this::fillHistory);
        // Пустышка, чтобы подменю показывалось до первого открытия.
        historyMenu.add(new Action("…") {
        });
        menu.add(historyMenu);
        menu.add(new Action("Переименовать этот чат…") {
            @Override
            public void run() {
                renameChat(chatId);
            }
        });
        menu.add(new Action("Экспорт чата в Markdown…") {
            @Override
            public void run() {
                exportChat(chatId);
            }
        });
        menu.add(new Action("Удалить этот чат") {
            @Override
            public void run() {
                deleteChat();
            }
        });
        menu.add(new Action("Скиллы…") {
            @Override
            public void run() {
                showSkills();
            }
        });
        menu.add(new Separator());
        menu.add(prompt(REVIEW_CHANGES, REVIEW_PROMPT));
        menu.add(prompt(COMMIT_MESSAGE, COMMIT_PROMPT));
    }

    /** Пункт меню вида: отправляет готовый вопрос, модель сама смотрит git. */
    private Action prompt(String title, String text) {
        return new Action(title) {
            @Override
            public void run() {
                if (session.isBusy()) {
                    showNotice("Дождитесь ответа или остановите его");
                    return;
                }
                session.send(text, contextBar.snapshot(), contextBar.attachments());
                contextBar.clear();
            }
        };
    }

    /** Новый пустой диалог; текущий остаётся в истории чатов. */
    private void newChat() {
        session.cancel();
        saveIfChanged();
        session.clear();
        allowedInChat.clear();
        if (session.provider() instanceof ModelChatProvider provider) {
            provider.forgetRejections();
        }
        chatId = chats.newId();
        saved = List.of();
        try {
            chats.prune();
        } catch (IOException e) {
            Platform.getLog(getClass()).warn("Не удалось удалить старые диалоги EDT AI", e);
        }
        refreshHistoryPanel();
    }

    /** Открывает сохранённый диалог. */
    private void openChat(String id) {
        if (id.equals(chatId)) {
            return;
        }
        if (session.isBusy()) {
            showNotice("Дождитесь ответа или остановите его, чтобы открыть другой чат");
            refreshHistoryPanel();
            return;
        }
        saveIfChanged();
        try {
            List<ChatMessage> messages = chats.file(id).load();
            session.restore(messages);
            chatId = id;
            saved = messages;
            scrollToEnd = !messages.isEmpty();
            allowedInChat.clear();
            chats.setCurrent(id);
            refreshHistoryPanel();
        } catch (IOException e) {
            showNotice("Не удалось открыть чат: " + e.getMessage());
        }
    }

    /** Удаляет открытый диалог и начинает новый. */
    private void deleteChat() {
        session.cancel();
        try {
            chats.delete(chatId);
        } catch (IOException e) {
            showNotice("Не удалось удалить чат: " + e.getMessage());
            return;
        }
        saved = List.of();
        session.clear();
        allowedInChat.clear();
        chatId = chats.newId();
        refreshHistoryPanel();
    }

    /** Сообщения чата: открытого — из вида, остальных — из файла; null — не прочитать или пуст. */
    private List<ChatMessage> messagesOf(String id) {
        List<ChatMessage> messages;
        try {
            messages = id.equals(chatId) ? session.messages() : chats.file(id).load();
        } catch (IOException e) {
            showNotice("Не удалось прочитать чат: " + e.getMessage());
            return null;
        }
        if (messages.isEmpty()) {
            showNotice("В чате ещё нет сообщений");
            return null;
        }
        return messages;
    }

    /** Даёт чату название; пустое — снова начало первого вопроса. */
    private void renameChat(String id) {
        List<ChatMessage> messages = messagesOf(id);
        String title = messages == null ? null : askTitle.apply(chats.title(id, messages));
        if (title == null) {
            return;
        }
        try {
            chats.rename(id, title);
        } catch (IOException e) {
            showNotice("Не удалось переименовать чат: " + e.getMessage());
        }
        refreshHistoryPanel();
    }

    /** Сохраняет чат в Markdown-файл, который выберет пользователь. */
    private void exportChat(String id) {
        List<ChatMessage> messages = messagesOf(id);
        if (messages == null) {
            return;
        }
        String title = chats.title(id, messages);
        Path target = chooseExportFile.apply(exportName(title));
        if (target == null) {
            return;
        }
        try {
            Files.writeString(target, ChatMarkdown.export(title, messages, ZoneId.systemDefault()), StandardCharsets.UTF_8);
            showNotice("Чат сохранён: " + target);
        } catch (IOException e) {
            showNotice("Не удалось сохранить чат: " + e.getMessage());
        }
    }

    /** Имя файла по названию чата: без символов, запрещённых в Windows. */
    static String exportName(String title) {
        String name = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replace("…", "").strip()
                .replaceAll("[. ]+$", "");
        name = name.length() > 80 ? name.substring(0, 80).strip() : name;
        return (name.isEmpty() ? "chat" : name) + ".md";
    }

    /** Новое название чата, null — отмена; тесты подменяют. */
    Function<String, String> askTitle = current -> {
        InputDialog dialog = new InputDialog(getSite().getShell(), "Переименовать чат",
                "Название чата (пустое — по первому вопросу):", current, null);
        return dialog.open() == Window.OK ? dialog.getValue() : null;
    };

    /** Куда сохранить чат, null — отмена; тесты подменяют. */
    Function<String, Path> chooseExportFile = name -> {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setText("Экспорт чата в Markdown");
        dialog.setFilterExtensions(new String[] { "*.md", "*.*" });
        dialog.setFileName(name);
        dialog.setOverwrite(true);
        String path = dialog.open();
        return path == null ? null : Path.of(path);
    };

    /** Показывает или прячет боковую панель со списком чатов. */
    void showHistoryPanel(boolean shown) {
        historyShown = shown;
        if (historyAction != null) {
            historyAction.setChecked(shown);
        }
        sash.setMaximizedControl(shown ? null : top);
        refreshHistoryPanel();
    }

    @Override
    public void init(IViewSite site, IMemento memento) throws PartInitException {
        super.init(site, memento);
        if (memento != null) {
            historyShown = Boolean.TRUE.equals(memento.getBoolean(MEMENTO_HISTORY));
            Integer left = memento.getInteger(MEMENTO_HISTORY_WIDTH);
            if (left != null && left > 5 && left < 95) {
                historyWeights = new int[] { left, 100 - left };
            }
        }
    }

    @Override
    public void saveState(IMemento memento) {
        super.saveState(memento);
        memento.putBoolean(MEMENTO_HISTORY, historyShown);
        if (sash != null && !sash.isDisposed() && historyShown) {
            int[] weights = sash.getWeights();
            memento.putInteger(MEMENTO_HISTORY_WIDTH, weights[0] * 100 / Math.max(1, weights[0] + weights[1]));
        }
    }

    /** Обновляет список чатов на панели, если она открыта. */
    private void refreshHistoryPanel() {
        if (historyPanel == null || sash.isDisposed() || sash.getMaximizedControl() != null) {
            return;
        }
        try {
            historyPanel.show(chats.search(historyPanel.query()), chatId);
        } catch (IOException e) {
            historyPanel.show(List.of(), chatId);
        }
    }

    // ---- правки модели: дифф и откат ----

    private String changeKey(ChatMessage answer) {
        return chatId + "|" + answer.time();
    }

    /** Ответ закончился: его правки — к последнему ответу ассистента. */
    private void attachChanges() {
        if (pendingChanges.isEmpty() && touched.isEmpty()) {
            return;
        }
        List<ChatMessage> messages = session.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == ChatRole.ASSISTANT) {
                String key = changeKey(messages.get(i));
                if (!pendingChanges.isEmpty()) {
                    changes.computeIfAbsent(key, k -> new ArrayList<>()).addAll(pendingChanges);
                }
                if (!touched.isEmpty()) {
                    touchedByAnswer.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).addAll(touched);
                }
                lateKey = key;
                lateUntil = System.currentTimeMillis() + LATE_CHANGES_MS;
                break;
            }
        }
        pendingChanges.clear();
        touched.clear();
        scheduleRefresh();
    }

    /**
     * Файлы, изменённые в ответе: правки {@code edit_module} — до первой и
     * после последней; файлы, изменённые мимо плагина (MCP), — с последним
     * коммитом вместо «до».
     */
    private Map<IFile, EditTools.Change> changedFiles(ChatMessage answer) {
        Map<IFile, EditTools.Change> files = new LinkedHashMap<>();
        for (EditTools.Change change : changes.getOrDefault(changeKey(answer), List.of())) {
            IFile file = ResourcesPlugin.getWorkspace().getRoot().getProject(change.project()).getFile(change.path());
            EditTools.Change first = files.get(file);
            files.put(file, first == null ? change
                    : new EditTools.Change(change.project(), change.path(), first.before(), change.after()));
        }
        int notInGit = 0;
        for (IFile file : touchedByAnswer.getOrDefault(changeKey(answer), Set.of())) {
            if (files.containsKey(file)) {
                continue;
            }
            String project = file.getProject().getName();
            String path = file.getProjectRelativePath().toString();
            try {
                String now = currentText(file);
                String head = GitTools.headText(project, file.getProject().getLocation().toFile().toPath(), path, now);
                if (!Objects.equals(head, now)) {
                    files.put(file, new EditTools.Change(project, path, head, now, true));
                }
            } catch (ToolException | LinkageError | RuntimeException e) {
                notInGit++;
            }
        }
        if (notInGit > 0) {
            showNotice("Правки инструментов MCP не показать: файлов " + notInGit + " вне git-репозитория");
        }
        return files;
    }

    /** Текст файла сейчас (с несохранённым в редакторе); {@code null} — файла нет. */
    private static String currentText(IFile file) {
        if (!file.exists()) {
            return null;
        }
        try {
            return EclipseWorkspaceFiles.text(file);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** Пути (в проекте) файлов, изменённых в ответе: для строки «Изменено:». Без обращения к git. */
    private List<String> changedPaths(ChatMessage answer) {
        String key = changeKey(answer);
        Set<String> paths = new java.util.LinkedHashSet<>();
        for (EditTools.Change change : changes.getOrDefault(key, List.of())) {
            paths.add(change.path());
        }
        for (IFile file : touchedByAnswer.getOrDefault(key, Set.of())) {
            paths.add(file.getProjectRelativePath().toString());
        }
        return List.copyOf(paths);
    }

    void showChanges(ChatMessage answer) {
        showChanges(answer, null);
    }

    /** @param only путь одного файла в проекте; {@code null} — все */
    private void showChanges(ChatMessage answer, String only) {
        Map<IFile, EditTools.Change> files = changedFiles(answer);
        if (only != null) {
            files.keySet().removeIf(file -> !file.getProjectRelativePath().toString().equals(only));
        }
        if (files.isEmpty()) {
            showNotice("Изменений относительно последнего коммита нет");
        }
        for (Map.Entry<IFile, EditTools.Change> entry : files.entrySet()) {
            String now;
            try {
                now = Objects.requireNonNullElse(currentText(entry.getKey()), "");
            } catch (RuntimeException e) {
                showNotice("Не удалось прочитать " + entry.getKey().getName() + ": " + e.getMessage());
                continue;
            }
            EditTools.Change change = entry.getValue();
            CompareUI.openCompareEditor(new ModelChangesCompare(entry.getKey(), now, Objects.requireNonNullElse(change.before(), ""),
                    change.sinceCommit()));
        }
    }

    /**
     * Возвращает модули, как были до ответа. Если после правок модели файл
     * меняли, — спрашивает: откат затрёт эти изменения.
     */
    void revertChanges(ChatMessage answer) {
        Map<IFile, EditTools.Change> files = changedFiles(answer);
        boolean changedSince = false;
        long sinceCommit = files.values().stream().filter(EditTools.Change::sinceCommit).count();
        for (Map.Entry<IFile, EditTools.Change> entry : files.entrySet()) {
            if (!entry.getValue().sinceCommit()) {
                try {
                    changedSince |= !Objects.equals(currentText(entry.getKey()), entry.getValue().after());
                } catch (RuntimeException e) {
                    changedSince = true;
                }
            }
        }
        List<String> warnings = new ArrayList<>();
        if (sinceCommit > 0) {
            warnings.add("Файлы, которые меняли инструменты MCP (" + sinceCommit + "), вернутся к последнему коммиту: если в них"
                    + " были ваши незакоммиченные правки, они тоже пропадут.");
        }
        if (changedSince) {
            warnings.add("После правок модели модули меняли: откат вернёт их к состоянию до ответа и затрёт эти изменения.");
        }
        if (!warnings.isEmpty() && !confirmRevert.test(String.join("\n\n", warnings) + "\n\nПродолжить?")) {
            return;
        }
        lateKey = null; // откат сам меняет файлы — это не правки модели
        EclipseWorkspaceFiles workspace = EclipseWorkspaceFiles.current();
        int reverted = 0;
        for (Map.Entry<IFile, EditTools.Change> entry : files.entrySet()) {
            IFile file = entry.getKey();
            String before = entry.getValue().before();
            try {
                if (before == null) {
                    if (file.exists()) {
                        file.delete(true, null);
                    }
                } else if (!file.exists()) {
                    createFolders(file.getParent());
                    file.create(new ByteArrayInputStream(before.getBytes(StandardCharsets.UTF_8)), true, null);
                } else {
                    workspace.edit(file.getProject().getName(), file.getProjectRelativePath().toString(),
                            current -> new TextChange.Replacement(0, current.length(), before));
                }
                reverted++;
            } catch (ToolException | CoreException e) {
                showNotice("Не удалось откатить " + file.getName() + ": " + e.getMessage());
            }
        }
        changes.remove(changeKey(answer));
        touchedByAnswer.remove(changeKey(answer));
        refresh();
        showNotice("Правки модели откатены, файлов: " + reverted);
    }

    private static void createFolders(IContainer container) throws CoreException {
        if (container instanceof IFolder folder && !folder.exists()) {
            createFolders(folder.getParent());
            folder.create(true, true, null);
        }
    }

    /** Вопрос перед откатом; тесты подменяют. */
    Predicate<String> confirmRevert = message -> MessageDialog.openConfirm(getSite().getShell(), "Откатить правки", message);

    /** Пункты меню «История чатов»: последние диалоги, открытый отмечен. */
    private void fillHistory(IMenuManager menu) {
        List<ChatStore.Entry> entries;
        try {
            entries = chats.list();
        } catch (IOException e) {
            entries = List.of();
        }
        if (entries.isEmpty() || entries.size() == 1 && entries.get(0).id().equals(chatId) && session.messages().isEmpty()) {
            Action none = new Action("Сохранённых чатов нет") {
            };
            none.setEnabled(false);
            menu.add(none);
            return;
        }
        for (ChatStore.Entry entry : entries.stream().limit(HISTORY_MENU).toList()) {
            String when = CHAT_TIME.format(LocalDateTime.ofInstant(entry.updated(), ZoneId.systemDefault()));
            Action open = new Action(entry.title().replace("&", "&&") + "\t" + when, IAction.AS_CHECK_BOX) {
                @Override
                public void run() {
                    openChat(entry.id());
                }
            };
            open.setChecked(entry.id().equals(chatId));
            menu.add(open);
        }
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
        if (inputAssist != null && inputAssist.isOpen()) {
            return; // Enter, Esc и стрелки — списку подсказок
        }
        boolean paste = (event.stateMask & SWT.MOD1) != 0 && (event.keyCode == 'v' || event.keyCode == 'V')
                || (event.stateMask & SWT.SHIFT) != 0 && event.keyCode == SWT.INSERT;
        if (paste && pasteAttachments()) {
            event.doit = false;
            return;
        }
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
        if (text.isBlank()) {
            return;
        }
        SlashCommand.Invocation command = SlashCommand.parse(text).orElse(null);
        String prompt = command == null ? null : switch (command.command()) {
            case REVIEW -> REVIEW_PROMPT;
            case COMMIT -> COMMIT_PROMPT;
            case EXPLAIN -> EXPLAIN_PROMPT;
            case TESTS -> TESTS_PROMPT;
            case SKILL -> SKILL_PROMPT;
            default -> null;
        };
        Skill skill = command == null ? invokedSkill(text) : null;
        if (command != null && prompt == null) {
            input.setText("");
            run(command.command());
            return;
        }
        if (session.isBusy()) {
            return;
        }
        List<Attachment> extra = new ArrayList<>();
        if (skill != null) {
            // Инструкция скилла — вложением: в ленте видно «скилл …», модель получает её целиком.
            String argument = text.strip().substring(skill.command().length()).strip();
            text = argument.isEmpty() ? "Выполни скилл «" + skill.name() + "»." : argument;
            extra.add(Attachment.skill(skill.name(), skill.body()));
        }
        if (command != null && command.command() == SlashCommand.SKILL) {
            if (command.argument().isEmpty()) {
                showNotice("Опишите после /skill, что должен делать скилл: «/skill оформление печатных форм по нашему стандарту»");
                return;
            }
            skills.find("create-skill").ifPresent(guide -> extra.add(Attachment.skill(guide.name(), guide.body())));
        }
        if (command != null) {
            boolean aboutCode = command.command() == SlashCommand.EXPLAIN || command.command() == SlashCommand.TESTS;
            if (aboutCode && contextBar.isEmpty()) {
                attachFromEditor();
            }
            if (aboutCode && contextBar.isEmpty() && command.argument().isEmpty()) {
                showNotice("Выделите код в редакторе, прикрепите модуль (@) или напишите после команды, о каком коде речь");
                return;
            }
            text = command.argument().isEmpty() ? prompt : prompt + "\n\n" + command.argument();
        }
        List<ContextItem> context = contextBar.snapshot();
        List<Attachment> attachments = new ArrayList<>(contextBar.attachments());
        attachments.addAll(extra);
        input.setText("");
        session.send(text, context, attachments);
        contextBar.clear();
    }

    /** Скилл, если сообщение начинается с его команды: {@code /query-optimize …}. */
    private Skill invokedSkill(String text) {
        String stripped = text.strip();
        if (!stripped.startsWith("/") || stripped.length() < 2) {
            return null;
        }
        String word = stripped.substring(1).split("\\s", 2)[0];
        return skills.find(word).orElse(null);
    }

    /** {@code /skills}: список в окне, из него — открыть папку своих скиллов. */
    private void showSkills() {
        if (skillsDialog.test(skills.describe())) {
            try {
                Files.createDirectories(skills.userDirectory());
                openFile.accept(skills.userDirectory());
            } catch (IOException e) {
                showNotice("Не удалось открыть папку скиллов: " + e.getMessage());
            }
        }
    }

    /** Показ списка скиллов; {@code true} — открыть папку своих. Тесты подменяют. */
    Predicate<String> skillsDialog = text -> new MessageDialog(getSite().getShell(), "Скиллы", null, text, MessageDialog.INFORMATION,
            0, "Закрыть", "Открыть папку своих скиллов").open() == 1;

    /** Каталоги скиллов открытых проектов: {@code <проект>/.edtai/skills}. */
    private static List<Path> projectSkillDirectories() {
        List<Path> directories = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isOpen() && project.getLocation() != null) {
                directories.add(project.getLocation().toFile().toPath().resolve(SkillLibrary.PROJECT_DIRECTORY));
            }
        }
        return directories;
    }

    /** Команда поля ввода, кроме тех, что отправляют вопрос. */
    private void run(SlashCommand command) {
        switch (command) {
            case NEW -> newChat();
            case ATTACH -> attachModule();
            case AUTO -> setAutoApprove(!isAutoApprove());
            case READONLY -> setReadOnly(!isReadOnly());
            case COMPACT -> compact();
            case MCP -> showMcpStatus();
            case SKILLS -> showSkills();
            default -> throw new IllegalArgumentException(command.text());
        }
    }

    private static boolean isAutoApprove() {
        return preferences().getBoolean(AUTO_APPROVE, false);
    }

    void setAutoApprove(boolean on) {
        preferences().putBoolean(AUTO_APPROVE, on);
        if (autoAction != null) {
            autoAction.setChecked(on);
        }
        showNotice(on ? "Авторежим включён: инструменты MCP вызываются без вопроса"
                : "Авторежим выключен: перед вызовом инструмента MCP — вопрос");
    }

    /**
     * Ctrl+V в EDT — команда «Вставить» верстака: до поля ввода нажатие не
     * доходит, команда сама вставляет текст. Поэтому у вида свой обработчик:
     * в поле ввода картинка или файлы из буфера прикладываются, текст —
     * вставляется; в других полях вида — обычная вставка.
     */
    void paste() {
        Control focus = input.getDisplay().getFocusControl();
        if (focus == input) {
            if (!pasteAttachments()) {
                input.paste();
            }
        } else if (focus instanceof Text text) {
            text.paste();
        }
    }

    /** Меню поля ввода: «Вставить» умеет картинки и файлы, рядом — «Приложить файлы…». */
    private Menu inputMenu() {
        Menu menu = new Menu(input);
        MenuItem cut = new MenuItem(menu, SWT.PUSH);
        cut.setText("Вырезать");
        cut.addListener(SWT.Selection, event -> input.cut());
        MenuItem copy = new MenuItem(menu, SWT.PUSH);
        copy.setText("Копировать");
        copy.addListener(SWT.Selection, event -> input.copy());
        MenuItem paste = new MenuItem(menu, SWT.PUSH);
        paste.setText("Вставить\tCtrl+V");
        paste.addListener(SWT.Selection, event -> {
            if (!pasteAttachments()) {
                input.paste();
            }
        });
        MenuItem selectAll = new MenuItem(menu, SWT.PUSH);
        selectAll.setText("Выделить всё");
        selectAll.addListener(SWT.Selection, event -> input.selectAll());
        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem attach = new MenuItem(menu, SWT.PUSH);
        attach.setText("Приложить файлы…");
        attach.setImage(Icons.image(Icons.ATTACH));
        attach.addListener(SWT.Selection, event -> chooseFiles());
        menu.addListener(SWT.Show, event -> {
            boolean selected = input.getSelectionCount() > 0;
            cut.setEnabled(selected);
            copy.setEnabled(selected);
        });
        return menu;
    }

    /** Открывает вложение (картинку, файл из ответа) в программе системы: файл во временном каталоге. */
    private void openAttachment(Attachment attachment) {
        try {
            String name = attachment.name().replaceAll("[\\\\/:*?\"<>|]", "_");
            Path file = Files.createTempDirectory("edtai-").resolve(name.isBlank() ? "file" : name);
            Files.write(file, attachment.bytes());
            file.toFile().deleteOnExit();
            file.getParent().toFile().deleteOnExit();
            openFile.accept(file);
        } catch (IOException | IllegalArgumentException e) {
            showNotice("Не удалось открыть " + attachment.name() + ": " + e.getMessage());
        }
    }

    /** Сохраняет файл из ответа модели, куда выберет пользователь. */
    private void saveAttachment(Attachment attachment) {
        Path target = chooseSaveFile.apply(attachment.name());
        if (target == null) {
            return;
        }
        try {
            Files.write(target, attachment.bytes());
            lastSaveDirectory = target.getParent();
            showNotice("Сохранено: " + target);
        } catch (IOException | IllegalArgumentException e) {
            showNotice("Не удалось сохранить " + attachment.name() + ": " + e.getMessage());
        }
    }

    /** Каталог, куда сохраняли файл из ответа в последний раз. */
    private Path lastSaveDirectory;

    /** Куда сохранить файл из ответа, null — отмена; тесты подменяют. */
    Function<String, Path> chooseSaveFile = name -> {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setText("Сохранить файл");
        int dot = name.lastIndexOf('.');
        dialog.setFilterExtensions(dot > 0 ? new String[] { "*" + name.substring(dot), "*.*" } : new String[] { "*.*" });
        dialog.setFileName(name);
        if (lastSaveDirectory != null) {
            dialog.setFilterPath(lastSaveDirectory.toString());
        }
        dialog.setOverwrite(true);
        String path = dialog.open();
        return path == null ? null : Path.of(path);
    };

    /** Открыть файл программой системы; тесты подменяют. */
    Consumer<Path> openFile = file -> {
        if (!Program.launch(file.toString())) {
            showNotice("Нет программы для просмотра " + file.getFileName());
        }
    };

    /** Диалог выбора файлов: картинки и текстовые файлы. */
    private void chooseFiles() {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.OPEN | SWT.MULTI);
        dialog.setText("Приложить файлы");
        dialog.setFilterExtensions(new String[] { "*.*", "*.png;*.jpg;*.jpeg;*.gif;*.webp" });
        dialog.setFilterNames(new String[] { "Все файлы", "Картинки" });
        if (dialog.open() != null) {
            List<String> paths = new ArrayList<>();
            for (String name : dialog.getFileNames()) {
                paths.add(Path.of(dialog.getFilterPath(), name).toString());
            }
            attachFiles(paths);
        }
    }

    /** Прикладывает файлы с диска; что не вышло — в строке состояния. */
    void attachFiles(List<String> paths) {
        List<String> problems = new ArrayList<>();
        for (String name : paths) {
            Path path = Path.of(name);
            try {
                if (Files.isDirectory(path)) {
                    problems.add("«" + path.getFileName() + "» — папка");
                    continue;
                }
                if (Files.size(path) > MAX_FILE_BYTES) {
                    problems.add("«" + path.getFileName() + "» больше " + MAX_FILE_BYTES / 1_000_000 + " МБ");
                    continue;
                }
                contextBar.add(Attachment.file(path.getFileName().toString(), Files.readAllBytes(path)));
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            } catch (IOException e) {
                problems.add("«" + path.getFileName() + "» не прочитать: " + e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            showNotice(String.join("; ", problems));
        }
        input.setFocus();
    }

    /**
     * Ctrl+V в поле ввода: картинка из буфера (скриншот) или скопированные
     * в проводнике файлы прикладываются; текст вставляется как обычно.
     *
     * @return {@code true}, если в буфере была картинка или файлы
     */
    boolean pasteAttachments() {
        Clipboard clipboard = new Clipboard(input.getDisplay());
        try {
            if (clipboard.getContents(FileTransfer.getInstance()) instanceof String[] paths && paths.length > 0) {
                attachFiles(List.of(paths));
                return true;
            }
            if (clipboard.getContents(ImageTransfer.getInstance()) instanceof ImageData image) {
                attachImage(image);
                return true;
            }
            return false;
        } finally {
            clipboard.dispose();
        }
    }

    /** Скриншот из буфера — в PNG. */
    void attachImage(ImageData image) {
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] { image };
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        loader.save(png, SWT.IMAGE_PNG);
        String name = "скриншот-" + LocalDateTime.now().format(SCREENSHOT_TIME) + ".png";
        try {
            contextBar.add(Attachment.png(name, png.toByteArray()));
        } catch (IllegalArgumentException e) {
            showNotice(e.getMessage());
        }
        input.setFocus();
    }

    /** Код из активного редактора — выделение или метод под курсором, как по Ctrl+Alt+A; нет редактора — ничего. */
    private void attachFromEditor() {
        ITextEditor editor = EditorAccess.textEditor(getSite().getPage().getActiveEditor());
        AttachedContext context = editor == null ? null : AttachedContext.fromEditor(editor);
        if (context != null) {
            contextBar.add(context);
        }
    }

    /** {@code /compact}: краткое содержание диалога вместо сообщений выше. */
    private void compact() {
        if (session.isBusy()) {
            showNotice("Дождитесь ответа или остановите его");
            return;
        }
        if (!session.canCompact()) {
            showNotice("Сжимать нечего: после последнего сжатия ответов не было");
            return;
        }
        session.compact().thenAccept(done -> {
            if (done) {
                inUi(() -> showNotice("История сжата: модель дальше видит краткое содержание"));
            }
        });
    }

    /** {@code /mcp}: подключение к серверам — в фоне, итог — в окне. */
    private void showMcpStatus() {
        AiSettings current = SettingsStore.workspace().load();
        showNotice("Проверяю MCP-серверы…");
        background("EDT AI: состояние MCP", () -> {
            List<McpHub.ServerStatus> servers = mcp.status(current.mcpServers(), TrustSettings.from(current));
            inUi(() -> {
                updateStatus();
                mcpStatus.accept(servers);
            });
        });
    }

    /** Показ итога {@code /mcp}; тесты подменяют. */
    Consumer<List<McpHub.ServerStatus>> mcpStatus = servers -> new McpStatusDialog(getSite().getShell(), servers,
            this::openPreferences).open();

    /**
     * После ответа: если у чата ещё нет названия, просит модель придумать его
     * по первому обмену — по разу на чат. Пользователь может переименовать.
     */
    private void generateTitle() {
        String id = chatId;
        List<ChatMessage> messages = session.messages();
        boolean answered = messages.stream().anyMatch(message -> message.role() == ChatRole.ASSISTANT
                && message.status() == Status.DONE && !message.text().isBlank());
        if (!preferences().getBoolean(GENERATE_TITLES, true) || !answered || chats.customTitle(id).isPresent()
                || !titleRequested.add(id)) {
            return;
        }
        session.provider().title(messages).whenComplete((title, error) -> inUi(() -> {
            try {
                if (error == null && title != null && !title.isBlank() && chats.customTitle(id).isEmpty()
                        && Files.exists(chats.file(id).path())) {
                    chats.rename(id, title);
                    refreshHistoryPanel();
                }
            } catch (IOException e) {
                // Название — удобство: остаётся начало вопроса.
            }
        }));
    }

    private static boolean isReadOnly() {
        return preferences().getBoolean(READ_ONLY, false);
    }

    void setReadOnly(boolean on) {
        preferences().putBoolean(READ_ONLY, on);
        if (readOnlyButton != null && !readOnlyButton.isDisposed()) {
            readOnlyButton.setSelection(on);
        }
        showNotice(on ? "Только вопросы: модель не меняет проект, правки предлагает кодом в ответе"
                : "Только вопросы выключено: модель может править модули");
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
        message.attachments().forEach(contextBar::add);
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
        List<List<String>> files = new ArrayList<>();
        List<Set<String>> expanded = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            String prefix = changeKey(messages.get(i)) + "#";
            expanded.add(expandedCalls.stream().filter(key -> key.startsWith(prefix)).collect(Collectors.toSet()));
            files.add(idle && messages.get(i).role() == ChatRole.ASSISTANT ? changedPaths(messages.get(i)) : List.of());
            actions.add(actions(messages, i, idle));
            String key = changeKey(messages.get(i));
            if (idle && messages.get(i).role() == ChatRole.ASSISTANT && (changes.containsKey(key) || touchedByAnswer.containsKey(key))) {
                actions.get(i).add(MessageRenderer.Action.CHANGES);
                actions.get(i).add(MessageRenderer.Action.REVERT);
            }
        }
        int first = 0;
        while (first < rendered.size() && first < messages.size()
                && rendered.get(first).message().equals(messages.get(first))
                && rendered.get(first).actions().equals(actions.get(first))
                && rendered.get(first).files().equals(files.get(first))
                && rendered.get(first).expanded().equals(expanded.get(first))) {
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
                rendered.add(new Rendered(messages.get(i), actions.get(i), files.get(i), expanded.get(i), start + text.length(),
                        codeRanges));
                renderer.render(messages.get(i), i, i > 0, actions.get(i), start, text, styles, codeRanges);
            }
            history.replaceTextRange(start, history.getCharCount() - start, text.toString());
            history.replaceStyleRanges(start, text.length(), styles.toArray(StyleRange[]::new));
            history.redraw();
            if (atBottom || scrollToEnd) {
                history.setTopPixel(Integer.MAX_VALUE);
            }
            if (scrollToEnd) {
                scrollToEnd = false;
                // При первом показе вида размеры ленты ещё не известны: прокручиваем и после компоновки.
                history.getDisplay().asyncExec(() -> {
                    if (!history.isDisposed()) {
                        history.setTopPixel(Integer.MAX_VALUE);
                    }
                });
            }
        }
        // Новый чат сохраняем сразу, с первым вопросом: он появляется в панели чатов, не дожидаясь ответа.
        if ((idle || saved.isEmpty()) && !messages.equals(saved)) {
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

    /** Строка вопроса пользователя (без пустой строки перед ним). */
    private boolean isInQuestion(int offset) {
        for (int i = 0; i < rendered.size(); i++) {
            Rendered item = rendered.get(i);
            int start = item.start() + (i > 0 ? 2 : 0);
            int end = i + 1 < rendered.size() ? rendered.get(i + 1).start() : history.getCharCount() + 1;
            if (offset >= start && offset < end) {
                return item.message().role() == ChatRole.USER;
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

    /** Открывает последний диалог; старый единственный {@code chat.json} становится первым в истории. */
    private void restoreHistory() {
        try {
            chats.migrate(stateLocation.resolve(HISTORY_FILE));
            chatId = chats.current().orElseGet(chats::newId);
            List<ChatMessage> messages = chats.file(chatId).load();
            session.restore(messages);
            saved = messages;
            scrollToEnd = !messages.isEmpty();
        } catch (IOException e) {
            chatId = chats.newId();
            Platform.getLog(getClass()).warn("Не удалось восстановить диалог EDT AI из " + chats.file(chatId).path().getParent(), e);
        }
    }

    private void saveIfChanged() {
        List<ChatMessage> messages = session.messages();
        if (!messages.equals(saved)) {
            saveHistory(messages);
        }
    }

    /** Пустой диалог не хранится: файла нет, в истории его нет. */
    private void saveHistory(List<ChatMessage> messages) {
        ChatHistoryFile file = chats.file(chatId);
        try {
            if (messages.isEmpty()) {
                Files.deleteIfExists(file.path());
            } else {
                file.save(messages);
                chats.setCurrent(chatId);
            }
        } catch (IOException e) {
            Platform.getLog(getClass()).warn("Не удалось сохранить диалог EDT AI в " + file.path(), e);
        }
        saved = messages;
        refreshHistoryPanel();
    }

    // ---- состояние ----

    private void updateEmptyState() {
        boolean configured = settings.isConfigured();
        String title = configured ? "Чем помочь?" : "Модель не настроена";
        String text = configured
                ? "Задайте вопрос модели " + settings.model() + ". Enter — отправить, Shift+Enter — новая строка,"
                        + " Esc — остановить ответ. Чтобы спросить о коде, выделите его в модуле и нажмите Ctrl+Alt+A. Скриншот — Ctrl+V в поле ввода, файлы — кнопкой рядом с ним или перетаскиванием."
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
            // «Стоп» с красным значком: видно, что идёт ответ и его можно остановить.
            sendButton.setImage(busy ? stopImage() : Icons.image(Icons.SEND));
        }
        sendButton.setEnabled(busy || !input.getText().isBlank());
    }

    /**
     * Строка под полем ввода: модель; пока идёт ответ — что она делает и
     * сколько секунд; потом — сколько окна занято и токены ответа (в чате
     * без ответов в этом сеансе — оценка по истории).
     */
    private static Image stopImage() {
        return PlatformUI.getWorkbench().getSharedImages().getImage(ISharedImages.IMG_ELCL_STOP);
    }

    private void updateStatus() {
        String text;
        if (notice != null) {
            text = notice;
        } else if (!settings.isConfigured()) {
            text = "Модель не настроена";
        } else {
            List<String> parts = new ArrayList<>(List.of(settings.model()));
            ReplyActivity activity = session.activity();
            TokenUsage usage = session.usage();
            if (approvalBar != null && approvalBar.isShown()) {
                parts.add("ждёт вашего решения");
            } else if (activity != null) {
                parts.add(activity.describe(Instant.now()));
            } else if (usage != null) {
                parts.add(usage.describe(window));
            } else if (estimate() > 0) {
                parts.add(TokenUsage.describeEstimate(estimate(), window));
            } else {
                parts.add(host(settings.baseUrl()));
            }
            if (isAutoApprove()) {
                parts.add(AUTO_MODE.toLowerCase(Locale.ROOT));
            }
            text = String.join(" · ", parts);
        }
        if (!text.equals(status.getText())) {
            status.setText(text);
            status.setToolTipText(settings.isConfigured() ? settings.model() + " · " + settings.baseUrl() : null);
            // Строка и так во всю ширину: перекомпоновка всего вида раз в секунду (счётчик секунд) дёргала ленту.
            if (status.computeSize(SWT.DEFAULT, SWT.DEFAULT).y != status.getSize().y) {
                root.layout(new Control[] { status });
            }
        }
    }

    /** Оценка занятого окна для чата без ответов в этом сеансе; считается заново, только когда изменилась история. */
    private long estimate() {
        List<ChatMessage> messages = session.messages();
        if (!messages.equals(estimated)) {
            estimated = messages;
            estimate = session.estimateTokens();
        }
        return estimate;
    }

    /** Идёт ответ: вкладка вида помечена занятой, секунды в строке состояния идут. */
    private void markBusy(boolean busy) {
        markTabBusy(busy);
        if (busy && !ticking) {
            ticking = true;
            history.getDisplay().timerExec(TICK_MS, this::tick);
        }
        updateStatus();
    }

    private void markTabBusy(boolean busy) {
        IWorkbenchSiteProgressService progress = getSite().getService(IWorkbenchSiteProgressService.class);
        if (busy != busyShown && progress != null) {
            busyShown = busy;
            if (busy) {
                progress.incrementBusy();
            } else {
                progress.decrementBusy();
            }
        }
    }

    private void tick() {
        ticking = false;
        if (!history.isDisposed() && session.isBusy()) {
            updateStatus();
            ticking = true;
            history.getDisplay().timerExec(TICK_MS, this::tick);
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
        EclipseWorkspaceFiles files = EclipseWorkspaceFiles.current();
        List<Tool> tools = new ArrayList<>(ProjectTools.create(files, current::contextLimit));
        tools.addAll(EditTools.create(files, pendingChanges::add));
        tools.addAll(SkillTools.create(skills));
        tools.addAll(FileTools.create());
        try {
            tools.addAll(GitTools.create(files, current::contextLimit));
        } catch (LinkageError e) {
            // В этой сборке EDT нет JGit (EGit): без инструментов git.
        }
        Set<String> builtIn = tools.stream().map(Tool::name).collect(Collectors.toSet());
        tools.addAll(mcp.tools(current.mcpServers(), TrustSettings.from(current), builtIn, current::contextLimit, problems));
        if (isReadOnly()) {
            tools.removeIf(Tool::modifies);
        }
        return tools;
    }

    /** Вопрос пользователю перед вызовом инструмента MCP-сервера: панель над полем ввода. */
    private CompletableFuture<Decision> approve(ToolUse use, String arguments) {
        if (allowedInChat.contains(use.name()) || isAutoApprove()) {
            return CompletableFuture.completedFuture(Decision.ALLOW);
        }
        CompletableFuture<Decision> decision = new CompletableFuture<>();
        decision.thenAccept(value -> {
            if (value == Decision.ALLOW_IN_CHAT) {
                allowedInChat.add(use.name());
            } else if (value == Decision.ALWAYS) {
                inUi(() -> setAutoApprove(true));
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
        markTabBusy(false);
        contextBar.clear();
        if (renderer != null) {
            renderer.dispose();
        }
        session.removeListener(chatListener);
        session.cancel();
        // Закрытие сессий MCP — запросы по сети, не в UI-потоке.
        background("EDT AI: закрытие MCP", mcp::close);
        saveIfChanged();
        preferences().removePreferenceChangeListener(preferenceListener);
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(resourceListener);
        super.dispose();
    }

    // ---- для тестов ----

    ChatSession session() {
        return session;
    }

    ChatHistoryFile historyFile() {
        return chats.file(chatId);
    }

    ChatStore chats() {
        return chats;
    }

    ThinScrollBar scrollBar() {
        return scrollBar;
    }

    ChatHistoryPanel historyPanel() {
        return historyPanel;
    }

    void openChatForTest(String id) {
        openChat(id);
    }

    void newChatForTest() {
        newChat();
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

    Button readOnlyButton() {
        return readOnlyButton;
    }

    Label status() {
        return status;
    }

    InputAssist inputAssist() {
        return inputAssist;
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
