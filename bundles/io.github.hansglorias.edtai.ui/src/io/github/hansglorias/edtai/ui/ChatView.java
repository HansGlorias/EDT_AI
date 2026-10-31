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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.Optional;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
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
import org.eclipse.jface.preference.JFacePreferences;
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
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.RGB;
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
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
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
import org.osgi.service.prefs.BackingStoreException;
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
import io.github.hansglorias.edtai.core.chat.CommitMessage;
import io.github.hansglorias.edtai.core.chat.AgentMode;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.review.Finding;
import io.github.hansglorias.edtai.core.chat.ChatSession;
import io.github.hansglorias.edtai.core.chat.ChatStore;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.context.CodeInsertion;
import io.github.hansglorias.edtai.core.context.ContextItem;
import io.github.hansglorias.edtai.core.llm.ModelChatProvider;
import io.github.hansglorias.edtai.core.llm.ModelClient;
import io.github.hansglorias.edtai.core.mcp.McpHub;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.McpServer;
import io.github.hansglorias.edtai.core.settings.SettingsStore;
import io.github.hansglorias.edtai.core.settings.ModelChoices;
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
import io.github.hansglorias.edtai.core.tools.ReviewTools;
import io.github.hansglorias.edtai.core.tools.SkillTools;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolActivity;
import io.github.hansglorias.edtai.core.tools.ToolApprover.Decision;
import io.github.hansglorias.edtai.core.tools.ToolSource;

/**
 * Вид «EDT AI»: лента диалога, панель прикреплённых фрагментов кода и
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
            + " необходимости прочитай модули целиком. Найди ошибки, пропущенные случаи и нарушения стандартов 1С. Находки"
            + " передай инструментом report_review (модуль, строка, серьёзность, суть, как исправить), потом коротко итог."
            + " Если всё хорошо — так и скажи.";
    static final String COMMIT_PROMPT = "Напиши сообщение коммита для моих незакоммиченных изменений (посмотри git_status и"
            + " git_diff) по скиллу commit-message. Только текст сообщения в блоке кода, без пояснений.";
    static final String EXPLAIN_PROMPT = "Объясни этот код: зачем он, что получает и что возвращает, основные шаги, побочные"
            + " эффекты (запись в базу, блокировки, вызовы сервера, транзакции) и неочевидные места. Если код вызывает другие"
            + " методы проекта и без них не понять — прочитай их. Ссылайся на строки: Модуль:строка.";
    static final String REVIEW_CODE_PROMPT = "Сделай ревью этого кода по скиллам bsl-style и code-standards (запросы — по"
            + " query-style). Находки передай инструментом report_review (модуль, строка в модуле, серьёзность, суть, как"
            + " исправить), потом коротко итог. Код не правь.";
    static final String REFACTOR_PROMPT = "Сделай рефакторинг этого кода по скиллу bsl-style: проще и понятнее, поведение не"
            + " меняй. Правь модуль (edit_module), потом кратко перечисли, что изменил. Если правка рискованная — сначала опиши"
            + " её и спроси.";
    static final String TESTS_PROMPT = "Напиши тесты для этого кода. Сначала посмотри (search_code, list_objects), есть ли в"
            + " проекте тесты и на чём: YAxUnit (общие модули с ИсполняемыеСценарии), Vanessa Automation (feature-файлы) —"
            + " пиши в том же стиле и скажи, куда положить. Тестов нет — на YAxUnit. Покрой обычный сценарий, граничные"
            + " случаи и ошибки. Модули тестов не создавай и не меняй — покажи код в ответе.";
    /** Файлы больше не читаем: документ такого размера — уже не то, что приложили к вопросу. */
    private static final long MAX_FILE_BYTES = 30_000_000;
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
    /** Режим модели ({@link AgentMode}), во всех чатах. Хранится в настройках рабочей области. */
    static final String AGENT_MODE = "agentMode";
    /** Прежние флажки: из них режим берётся, пока {@link #AGENT_MODE} не сохранён. */
    static final String AUTO_APPROVE = "autoApproveTools";
    static final String READ_ONLY = "readOnlyMode";

    /** Подключения к MCP-серверам из настроек. */
    private final McpHub mcp = new McpHub();
    /** Чаты, для которых уже просили название у модели: по разу. */
    private final Set<String> titleRequested = ConcurrentHashMap.newKeySet();
    /**
     * Живой диалог: сессия и всё, что относится к её ответу. Открыт в виде
     * один ({@link #current}), остальные живы, пока отвечают в фоне.
     */
    private final class Live {
        final String id;
        final ChatSession session;
        /** Инструменты, которые пользователь разрешил «в этом чате». */
        final Set<String> allowed = ConcurrentHashMap.newKeySet();
        /** Правки модели в идущем ответе; по его окончании — в {@link ChatView#changes}. */
        final List<EditTools.Change> pendingChanges = new CopyOnWriteArrayList<>();
        /**
         * Файлы {@code src/}, изменённые на диске во время вызовов инструментов
         * MCP в идущем ответе: EDT-MCP пишет метаданные и модули мимо плагина.
         * Изменения в остальное время ответа — пользователя или другого чата.
         */
        final Set<IFile> touched = ConcurrentHashMap.newKeySet();
        /** Идёт ли вызов инструмента MCP: только тогда изменения файлов — в {@link #touched}. */
        final ToolActivity toolActivity = new ToolActivity();
        /** Что сохранено в файл чата. */
        volatile List<ChatMessage> saved = List.of();
        /** Вопрос о разрешении инструмента, пока чат в фоне: задаётся, когда чат откроют. */
        Runnable approval;

        Live(String id) {
            this.id = id;
            session = newSession(this);
            session.addListener(liveListener(this));
        }
    }

    /** Чаты, ответившие в фоне и ещё не открытые: зелёная точка в списке. */
    private final Set<String> unread = ConcurrentHashMap.newKeySet();
    /** Живые диалоги по id: открытый и отвечающие в фоне. */
    private final Map<String, Live> lives = new ConcurrentHashMap<>();
    private Live current;
    /** Сессия открытого диалога. */
    private ChatSession session;

    private ChatSession newSession(Live chat) {
        return new ChatSession(
            ModelChatProvider.forStore(SettingsStore.workspace(), new ModelClient(), new ToolSource() {
                @Override
                public List<Tool> tools(Consumer<ToolUse> problems) {
                    return ChatView.this.tools(chat, problems);
                }

                @Override
                public String brief() {
                    String brief = ProjectBrief.describe(EclipseWorkspaceFiles.current());
                    if (SettingsStore.workspace().load().toolsEnabled()) {
                        brief = (brief + "\n\n" + skills.prompt()).strip();
                    }
                    return isReadOnly() ? (brief + "\n\n" + ProjectBrief.READ_ONLY_RULES).strip() : brief;
                }
            }, (use, arguments) -> approve(chat, use, arguments)));
    }

    /**
     * Ответ любого живого диалога, открытого или фонового: правки — к ответу,
     * история — в файл, название; фоновый, закончив, больше не живой.
     */
    private ChatListener liveListener(Live chat) {
        return new ChatListener() {
            @Override
            public void busyChanged(boolean busy) {
                if (busy) {
                    chat.touched.clear();
                    lateKey = null;
                    chat.toolActivity.reset();
                }
                inUi(() -> {
                    if (!busy) {
                        attachChanges(chat);
                        if (chat != current) {
                            unread.add(chat.id);
                            chat.approval = null;
                            saveIfChanged(chat);
                            lives.remove(chat.id, chat);
                        }
                        generateTitle(chat);
                        deliverCommitMessage(chat);
                    }
                    refreshHistoryPanel();
                });
            }
        };
    }
    private final Path stateLocation = Platform.getStateLocation(FrameworkUtil.getBundle(ChatView.class)).toFile().toPath();
    /** Скиллы: встроенные, свои (в рабочей области) и открытых проектов. */
    private final SkillLibrary skills = new SkillLibrary(stateLocation.resolve("skills"), ChatView::projectSkillDirectories,
            Arrays.stream(SlashCommand.values()).map(command -> command.text().substring(1)).collect(Collectors.toSet()),
            () -> SkillLibrary.parseDisabled(preferences().get(DISABLED_SKILLS, "")));
    /** Все диалоги; {@link #chatId} — открытый. */
    private final ChatStore chats = new ChatStore(stateLocation.resolve(CHATS_DIRECTORY));
    private String chatId;

    private Composite root;
    /** Правки модели по ответам (до перезапуска EDT): ключ — {@link #changeKey}. */
    private final Map<String, List<EditTools.Change>> changes = new HashMap<>();
    /** Файлы, изменённые мимо плагина, по ответам: ключ — {@link #changeKey}. */
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
    /** Правая часть: лента, панели над вводом, ввод, строка состояния. Список чатов — слева на всю высоту. */
    private Composite column;
    /** Рамка ввода: поле и строка кнопок под ним. */
    private Composite composer;
    private StackLayout topLayout;
    private StyledText history;
    private MessageRenderer renderer;
    private Composite emptyState;
    private Label emptyTitle;
    private Label emptyText;
    private Link emptyLink;
    private ApprovalBar approvalBar;
    private ContextBar contextBar;
    /** Сообщения, набранные во время ответа. */
    private QueueBar queueBar;
    /** Куда вставить сообщение коммита, когда ответ готов; {@code null} — не ждём. */
    private Predicate<String> commitTarget;
    /** Номер ответа на «Сообщение коммита» в истории. */
    private int commitAnswer;
    /** Чат, в котором просили сообщение коммита. */
    private Live commitChat;
    private Text input;
    private SendButton sendButton;
    private Label status;
    private Cursor handCursor;
    /** «MCP 1/2»: подключено серверов из настроенных; щелчок — /mcp. */
    private Chip mcpButton;
    /** Выбор модели справа в поле ввода, рядом с «Отправить». */
    private Chip modelButton;
    /** Режим модели и формат следующего вопроса: одна кнопка слева. */
    private Chip modeButton;
    /** Модели с сервера ({@code /models}); пока не загружены — пусто. */
    private List<String> serverModels = List.of();
    /** Почему список не загрузился; {@code null} — загрузился или не загружали. */
    private String modelsError;
    private boolean modelsLoading;
    /** Для какого сервера загружен список: сменился адрес — загрузить заново. */
    private String modelsFor = "";
    /** Что сделать, когда список загрузится: {@code /model имя}. */
    private final List<Runnable> afterModels = new ArrayList<>();
    static final String RECENT_MODELS = "recentModels";
    /** Выключенные скиллы: имена через перевод строки. */
    static final String DISABLED_SKILLS = "disabledSkills";
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
            scheduleRefresh();
            inUi(() -> markBusy(busy));
            if (!busy) {
                inUi(() -> {
                    approvalBar.dismiss();
                    sendQueued();
                });
            }
        }
    };

    private final IResourceChangeListener resourceListener = event -> {
        List<Live> answering = lives.values().stream().filter(chat -> chat.session.isBusy()).toList();
        boolean busy = !answering.isEmpty();
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
            // Ответ, в котором сейчас вызов MCP, — его правка; нет такого — правка пользователя.
            answering.stream().filter(chat -> chat.toolActivity.active()).forEach(chat -> chat.touched.addAll(files));
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
            background("EDT AI: переподключение MCP", () -> {
                mcp.reset();
                inUi(this::checkMcpInBackground);
            });
        }
        contextBar.setLimit(settings.contextLimit());
        updateModeButton();
        updateMcpButton();
        updateModelButton();
        if (!modelsFor.equals(modelsKey())) {
            // Другой сервер — старый список не годится; загрузится, когда понадобится.
            serverModels = List.of();
            modelsError = null;
            modelsFor = "";
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
        column = new Composite(sash, SWT.NONE);
        GridLayoutFactory.fillDefaults().spacing(0, 6).extendedMargins(0, 0, 0, 2).applyTo(column);
        sash.setWeights(historyWeights);
        sash.setMaximizedControl(historyShown ? null : column);
        top = new Composite(column, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(top);
        topLayout = new StackLayout();
        top.setLayout(topLayout);
        createHistory(top);
        createEmptyState(top);
        approvalBar = new ApprovalBar(column, this::approvalChanged);
        queueBar = new QueueBar(column, new QueueBar.Callbacks() {
            @Override
            public void edit(QueueBar.Queued message) {
                returnToInput(message);
            }

            @Override
            public void changed() {
                root.layout(true, true);
            }
        });
        createContextBar(column);
        createInput(column);

        status = new Label(column, SWT.NONE);
        status.setForeground(metaColor());
        GridDataFactory.fillDefaults().grab(true, false).indent(4, 0).applyTo(status);

        createActions();

        preferences().addPreferenceChangeListener(preferenceListener);
        ResourcesPlugin.getWorkspace().addResourceChangeListener(resourceListener, IResourceChangeEvent.POST_CHANGE);
        settings = SettingsStore.workspace().load();
        contextBar.setLimit(settings.contextLimit());
        updateModelButton();
        restoreHistory();
        window = session.provider().window();
        updateStatus();
        updateEmptyState();
        updateSendButton();
        refresh();
        refreshHistoryPanel();
        updateMcpButton();
        checkMcpInBackground();
    }

    private void createHistory(Composite parent) {
        // Без системной полосы прокрутки: своя, узкая (ThinScrollBar).
        history = new StyledText(parent, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY);
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
            public void openFinding(Finding finding) {
                reveal(ReviewMarkers.file(ResourcesPlugin.getWorkspace().getRoot(), finding), finding.line(), finding.line());
            }

            @Override
            public void clearReviewMarkers() {
                ReviewMarkers.clear();
                showNotice("Отметки ревью убраны из редактора и окна «Проблемы»");
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
        history.addListener(SWT.MouseMove, event -> {
            history.setCursor(actionAt(event.x, event.y) != null ? handCursor : null);
            String hint = hintAt(event.x, event.y);
            if (!java.util.Objects.equals(hint, history.getToolTipText())) {
                history.setToolTipText(hint);
            }
        });
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

    /**
     * Ввод в одной рамке, как в чатах: поле без своей рамки, под ним
     * плоские кнопки (вложения, авторежим, MCP, «Только вопросы») и справа
     * «Отправить». Рамка подсвечивается, пока поле в фокусе.
     */
    private void createInput(Composite parent) {
        composer = new Composite(parent, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(composer);
        GridLayoutFactory.fillDefaults().margins(COMPOSER_MARGIN, COMPOSER_MARGIN - 3).spacing(0, 5).applyTo(composer);
        composer.addListener(SWT.Paint, this::paintComposer);

        input = new Text(composer, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
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
            composer.redraw();
        });
        input.addListener(SWT.FocusOut, event -> composer.redraw());
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

        actions = new Composite(composer, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(actions);
        GridLayoutFactory.fillDefaults().numColumns(5).spacing(2, 0).applyTo(actions);
        Chip attachButton = Chip.plus(actions);
        GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.CENTER).applyTo(attachButton);
        attachButton.setToolTipText("Добавить файл или модуль проекта");
        attachButton.addListener(SWT.Selection, event -> showMenu(attachmentMenu(), attachButton));

        modeButton = new Chip(actions, true);
        GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.CENTER).applyTo(modeButton);
        modeButton.addListener(SWT.Selection, event -> showMenu(modeMenu(), modeButton));
        updateModeButton();

        mcpButton = new Chip(actions, false);
        GridDataFactory.fillDefaults().grab(true, false).align(SWT.BEGINNING, SWT.CENTER).applyTo(mcpButton);
        mcpButton.addListener(SWT.Selection, event -> showMcpStatus());

        modelButton = new Chip(actions, true);
        GridDataFactory.fillDefaults().align(SWT.END, SWT.CENTER).applyTo(modelButton);
        modelButton.addListener(SWT.Selection, event -> showModelMenu());
        // Список — заранее, пока указатель над кнопкой: к щелчку он обычно уже есть.
        modelButton.addListener(SWT.MouseEnter, event -> loadModels(false));
        updateModelButton();

        sendButton = new SendButton(actions);
        GridDataFactory.fillDefaults().align(SWT.END, SWT.CENTER).applyTo(sendButton);
        sendButton.addListener(SWT.Selection, event -> sendOrStop());
        updateInputHeight();
    }

    /** Отступ содержимого от рамки ввода. */
    private static final int COMPOSER_MARGIN = 8;

    /**
     * Рамка ввода: скруглённый прямоугольник цвета поля, обводка — цвет
     * выделения в фокусе, иначе смесь текста и фона. Фон кнопок — как у
     * поля, чтобы всё читалось одним блоком и в тёмной теме.
     */
    private void paintComposer(Event event) {
        Color fill = input.getBackground();
        for (Control child : composer.getChildren()) {
            if (child != input && !fill.equals(child.getBackground())) {
                child.setBackground(fill);
                if (child instanceof Composite group) {
                    for (Control inner : group.getChildren()) {
                        if (!(inner instanceof Button)) {
                            inner.setBackground(fill);
                        }
                    }
                }
            }
        }
        Point size = composer.getSize();
        GC gc = event.gc;
        gc.setAntialias(SWT.ON);
        gc.setBackground(composer.getParent().getBackground());
        gc.fillRectangle(0, 0, size.x, size.y);
        gc.setBackground(fill);
        gc.fillRoundRectangle(0, 0, size.x - 1, size.y - 1, 10, 10);
        boolean focused = input.isFocusControl();
        gc.setForeground(focused ? input.getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION) : borderColor());
        gc.setLineWidth(focused ? 2 : 1);
        gc.drawRoundRectangle(focused ? 1 : 0, focused ? 1 : 0, size.x - (focused ? 3 : 1), size.y - (focused ? 3 : 1), 10, 10);
        // Тонкая черта между полем ввода и рядом кнопок — бледнее обводки.
        if (actions != null && !actions.isDisposed()) {
            int y = actions.getBounds().y - 3;
            gc.setLineWidth(1);
            gc.setForeground(separatorColor());
            gc.drawLine(COMPOSER_MARGIN, y, size.x - 1 - COMPOSER_MARGIN, y);
        }
    }

    /** Ряд кнопок под полем ввода. */
    private Composite actions;

    private Color separatorColor() {
        RGB back = input.getBackground().getRGB();
        RGB fore = input.getForeground().getRGB();
        return new Color(new RGB((back.red * 7 + fore.red) / 8, (back.green * 7 + fore.green) / 8, (back.blue * 7 + fore.blue) / 8));
    }

    private Color borderColor;
    private RGB borderFrom;

    /** Обводка ввода: текст, подмешанный к фону на четверть, — видна в светлой и тёмной теме. */
    private Color borderColor() {
        RGB background = input.getBackground().getRGB();
        RGB foreground = input.getForeground().getRGB();
        if (borderColor == null || !background.equals(borderFrom)) {
            borderColor = new Color((background.red * 3 + foreground.red) / 4, (background.green * 3 + foreground.green) / 4,
                    (background.blue * 3 + foreground.blue) / 4);
            borderFrom = background;
        }
        return borderColor;
    }

    private Color metaColor() {
        Color color = JFaceResources.getColorRegistry().get(JFacePreferences.QUALIFIER_COLOR);
        return color != null ? color : root.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);
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
        menu.add(new Action("Убрать отметки ревью") {
            @Override
            public void run() {
                ReviewMarkers.clear();
                showNotice("Отметки ревью убраны из редактора и окна «Проблемы»");
            }
        });
    }

    /** Пункт меню вида: отправляет готовый вопрос, модель сама смотрит git. */
    private Action prompt(String title, String text) {
        return new Action(title) {
            @Override
            public void run() {
                if (session.isBusy()) {
                    queueBar.add(new QueueBar.Queued(text, contextBar.items(), contextBar.attachments()));
                    contextBar.detach();
                    return;
                }
                session.send(text, contextBar.snapshot(), contextBar.attachments());
                contextBar.clear();
            }
        };
    }

    /** Новый пустой диалог; текущий остаётся в истории чатов, его ответ идёт дальше в фоне. */
    private void newChat() {
        show(new Live(freshId()), List.of());
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
        try {
            Live chat = lives.get(id);
            if (chat != null) {
                show(chat, null);
            } else {
                show(new Live(id), chats.file(id).load());
            }
            chats.setCurrent(id);
        } catch (IOException e) {
            showNotice("Не удалось открыть чат: " + e.getMessage());
        }
        refreshHistoryPanel();
    }

    /** Id нового чата: нет ни файла, ни живого диалога с таким. */
    private String freshId() {
        String id = chats.newId();
        while (lives.containsKey(id)) {
            id = id + "-1";
        }
        return id;
    }

    /**
     * Показывает диалог в виде. Прежний, если отвечает, остаётся живым в
     * фоне, иначе — сохраняется и отпускается. {@code messages} — история
     * нового живого диалога из файла; {@code null} — диалог уже живой.
     */
    private void show(Live chat, List<ChatMessage> messages) {
        Live previous = current;
        if (previous != null) {
            previous.session.removeListener(chatListener);
            previous.approval = approvalBar.park();
            if (!queueBar.isEmpty()) {
                queueBar.drain().forEach(this::returnToInput);
                showNotice("Сообщения из очереди вернулись в поле ввода");
            }
            if (!previous.session.isBusy()) {
                saveIfChanged(previous);
                lives.remove(previous.id, previous);
            }
        }
        if (messages != null) {
            chat.session.restore(messages);
            chat.saved = messages;
        }
        lives.put(chat.id, chat);
        unread.remove(chat.id);
        current = chat;
        session = chat.session;
        chatId = chat.id;
        session.addListener(chatListener);
        expandedCalls.clear();
        recall = -1;
        scrollToEnd = !session.messages().isEmpty();
        if (previous != null) {
            markBusy(session.isBusy());
            scheduleRefresh();
            Runnable approval = chat.approval;
            chat.approval = null;
            if (approval != null) {
                approval.run();
            }
        }
    }

    /** Удаляет открытый диалог и начинает новый. */
    private void deleteChat() {
        Live deleted = current;
        deleted.session.cancel();
        try {
            chats.delete(deleted.id);
        } catch (IOException e) {
            showNotice("Не удалось удалить чат: " + e.getMessage());
            return;
        }
        deleted.session.clear();
        deleted.saved = List.of();
        show(new Live(freshId()), List.of());
        refreshHistoryPanel();
    }

    /** Сообщения чата: открытого — из вида, остальных — из файла; null — не прочитать или пуст. */
    private List<ChatMessage> messagesOf(String id) {
        List<ChatMessage> messages;
        try {
            Live chat = lives.get(id);
            messages = chat != null ? chat.session.messages() : chats.file(id).load();
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
        sash.setMaximizedControl(shown ? null : column);
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
            historyPanel.show(chats.search(historyPanel.query()), chatId, answering(), unread);
        } catch (IOException e) {
            historyPanel.show(List.of(), chatId, Set.of(), Set.of());
        }
    }

    /** Чаты, которые сейчас отвечают. */
    private Set<String> answering() {
        return lives.values().stream().filter(chat -> chat.session.isBusy()).map(chat -> chat.id).collect(Collectors.toSet());
    }

    // ---- правки модели: дифф и откат ----

    private String changeKey(ChatMessage answer) {
        return changeKey(chatId, answer);
    }

    private static String changeKey(String id, ChatMessage answer) {
        return id + "|" + answer.time();
    }

    /** Ответ закончился: его правки — к последнему ответу ассистента. */
    private void attachChanges(Live chat) {
        if (chat.pendingChanges.isEmpty() && chat.touched.isEmpty()) {
            return;
        }
        List<ChatMessage> messages = chat.session.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == ChatRole.ASSISTANT) {
                String key = changeKey(chat.id, messages.get(i));
                if (!chat.pendingChanges.isEmpty()) {
                    changes.computeIfAbsent(key, k -> new ArrayList<>()).addAll(chat.pendingChanges);
                }
                if (!chat.touched.isEmpty()) {
                    touchedByAnswer.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).addAll(chat.touched);
                }
                if (chat.toolActivity.used()) {
                    lateKey = key;
                    lateUntil = System.currentTimeMillis() + LATE_CHANGES_MS;
                }
                break;
            }
        }
        chat.pendingChanges.clear();
        chat.touched.clear();
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
        int needed = (content + lineHeight - 1) / lineHeight;
        int lines = Math.max(MIN_INPUT_LINES, Math.min(MAX_INPUT_LINES, needed));
        int height = lines * lineHeight + trim;
        // Полоса прокрутки — только когда текст не помещается: пустая полоса в поле выглядит как рамка.
        ScrollBar bar = input.getVerticalBar();
        if (bar != null && bar.getVisible() != needed > MAX_INPUT_LINES) {
            bar.setVisible(needed > MAX_INPUT_LINES);
        }
        GridData data = (GridData) input.getLayoutData();
        if (data.heightHint != height) {
            data.heightHint = height;
            root.layout(true, true);
        }
    }

    /** Кнопка у поля: идёт ответ и поле пусто — «Стоп», иначе отправить (или в очередь). */
    private void sendOrStop() {
        if (session.isBusy() && input.getText().isBlank()) {
            session.cancel();
        } else {
            send();
        }
    }

    /**
     * Отправляет текст из поля ввода. Пока идёт ответ — ставит в очередь:
     * уйдёт, когда ответ закончится. Команды без вопроса модели выполняются
     * сразу.
     */
    void send() {
        String text = input.getText();
        if (text.isBlank()) {
            return;
        }
        SlashCommand.Invocation command = SlashCommand.parse(text).orElse(null);
        if (command != null && command.command() == SlashCommand.PLAN) {
            // «/plan» — режим «План»; с текстом — и сразу вопрос в нём.
            if (agentMode() != AgentMode.PLAN) {
                setAgentMode(AgentMode.PLAN);
            }
            if (command.argument().isEmpty()) {
                input.setText("");
                return;
            }
        }
        if (command != null && command.command() != SlashCommand.PLAN && promptOf(command.command()) == null) {
            input.setText("");
            if (command.command() == SlashCommand.MODEL) {
                modelCommand(command.argument());
            } else {
                run(command.command());
            }
            return;
        }
        if (command != null && command.command() == SlashCommand.SKILL && command.argument().isEmpty()) {
            showNotice("Опишите после /skill, что должен делать скилл: «/skill оформление печатных форм по нашему стандарту»");
            return;
        }
        if (command != null && (command.command() == SlashCommand.EXPLAIN || command.command() == SlashCommand.TESTS)) {
            if (contextBar.isEmpty()) {
                attachFromEditor();
            }
            if (contextBar.isEmpty() && command.argument().isEmpty()) {
                showNotice("Выделите код в редакторе, прикрепите модуль (@) или напишите после команды, о каком коде речь");
                return;
            }
        }
        if (session.isBusy()) {
            queueBar.add(new QueueBar.Queued(text, contextBar.items(), contextBar.attachments()));
            contextBar.detach();
            input.setText("");
            showNotice("В очереди: уйдёт после ответа");
            return;
        }
        submit(text, contextBar.snapshot(), contextBar.attachments());
        input.setText("");
        contextBar.clear();
    }

    /** Готовый вопрос команды ({@code /review}…); {@code null} — команда без вопроса модели. */
    private static String promptOf(SlashCommand command) {
        return switch (command) {
            case REVIEW -> REVIEW_PROMPT;
            case COMMIT -> COMMIT_PROMPT;
            case EXPLAIN -> EXPLAIN_PROMPT;
            case TESTS -> TESTS_PROMPT;
            case SKILL -> SKILL_PROMPT;
            default -> null;
        };
    }

    /** Вопрос модели: текст как набран (команда, скилл) и прикреплённое. */
    private void submit(String typed, List<ContextItem> context, List<Attachment> files) {
        String text = typed;
        SlashCommand.Invocation command = SlashCommand.parse(text).orElse(null);
        Skill skill = command == null ? invokedSkill(text) : null;
        List<Attachment> attachments = new ArrayList<>(files);
        if (skill != null) {
            // Инструкция скилла — вложением: в ленте видно «скилл …», модель получает её целиком.
            String argument = text.strip().substring(skill.command().length()).strip();
            text = argument.isEmpty() ? "Выполни скилл «" + skill.name() + "»." : argument;
            attachments.add(Attachment.skill(skill.name(), skill.body()));
        }
        if (command != null && command.command() == SlashCommand.PLAN) {
            text = command.argument();
        } else if (command != null) {
            if (command.command() == SlashCommand.SKILL) {
                skills.find("create-skill").ifPresent(guide -> attachments.add(Attachment.skill(guide.name(), guide.body())));
            }
            String prompt = promptOf(command.command());
            text = command.argument().isEmpty() ? prompt : prompt + "\n\n" + command.argument();
        }
        Attachment instruction = agentMode().attachment();
        if (instruction != null) {
            // Режим «План»: каждый вопрос — с инструкцией плана (инструменты правки и так не передаются).
            attachments.add(instruction);
        }
        session.send(text, context, attachments);
    }

    /**
     * Ответ закончился: следующее из очереди — модели. Ответ остановили или
     * он оборвался — очередь возвращается в поле ввода: решать пользователю.
     */
    private void sendQueued() {
        if (queueBar.isEmpty() || session.isBusy()) {
            return;
        }
        List<ChatMessage> messages = session.messages();
        boolean done = !messages.isEmpty() && messages.get(messages.size() - 1).role() == ChatRole.ASSISTANT
                && messages.get(messages.size() - 1).status() == Status.DONE;
        if (!done) {
            List<QueueBar.Queued> all = queueBar.drain();
            all.forEach(this::returnToInput);
            showNotice("Ответ остановлен — сообщения из очереди вернулись в поле ввода");
            return;
        }
        QueueBar.Queued next = queueBar.poll();
        List<ContextItem> context = contextBar.snapshot(next.context());
        next.dispose();
        submit(next.text(), context, next.files());
    }

    /** Сообщение из очереди — обратно в поле ввода (к набранному) и прикреплённое — на панель. */
    private void returnToInput(QueueBar.Queued message) {
        String current = input.getText();
        input.setText(current.isBlank() ? message.text() : current.stripTrailing() + Text.DELIMITER + Text.DELIMITER + message.text());
        input.setSelection(input.getText().length());
        message.context().forEach(contextBar::add);
        message.files().forEach(contextBar::add);
        input.setFocus();
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
        Set<String> off = SkillLibrary.parseDisabled(preferences().get(DISABLED_SKILLS, ""));
        Set<String> chosen = skillsDialog.apply(skills.everything(), off);
        if (chosen != null && !chosen.equals(off)) {
            preferences().put(DISABLED_SKILLS, SkillLibrary.formatDisabled(chosen));
            showNotice("Скиллы: включено " + skills.all().size() + " из " + skills.everything().size());
        }
    }

    void openSkillsFolder() {
        try {
            Files.createDirectories(skills.userDirectory());
            openFile.accept(skills.userDirectory());
        } catch (IOException e) {
            showNotice("Не удалось открыть папку скиллов: " + e.getMessage());
        }
    }

    /** Окно {@code /skills}: все скиллы и выключенные → выключенные после «Готово» или {@code null}. Тесты подменяют. */
    BiFunction<List<Skill>, Set<String>, Set<String>> skillsDialog = (all, off) -> new SkillsDialog(getSite().getShell(), all, off,
            this::openSkillsFolder).choose();

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
            case AUTO -> setAgentMode(agentMode() == AgentMode.AUTO ? AgentMode.AGENT : AgentMode.AUTO);
            case READONLY -> setAgentMode(agentMode() == AgentMode.ASK ? AgentMode.AGENT : AgentMode.ASK);
            case COMPACT -> compact();
            case MCP -> showMcpStatus();
            case SKILLS -> showSkills();
            default -> throw new IllegalArgumentException(command.text());
        }
    }

    /** Режим модели: из настроек, у старых рабочих областей — из прежних флажков. */
    static AgentMode agentMode() {
        return AgentMode.parse(preferences().get(AGENT_MODE, ""), preferences().getBoolean(AUTO_APPROVE, false),
                preferences().getBoolean(READ_ONLY, false));
    }

    private static boolean isAutoApprove() {
        return agentMode().autoApprove();
    }

    void setAgentMode(AgentMode value) {
        preferences().put(AGENT_MODE, value.name());
        updateModeButton();
        showNotice("Режим «" + value.label() + "»: " + lowerFirst(value.description()));
    }

    /** «Авто» включить или выключить (кнопка «Всегда» в вопросе, тесты). */
    void setAutoApprove(boolean on) {
        setAgentMode(on ? AgentMode.AUTO : AgentMode.AGENT);
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

    /** Диалог выбора файлов: картинки, документы, текстовые файлы. */
    private void chooseFiles() {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.OPEN | SWT.MULTI);
        dialog.setText("Приложить файлы");
        dialog.setFilterExtensions(new String[] { "*.*", "*.docx;*.xlsx;*.xlsm;*.pptx;*.odt;*.ods;*.odp;*.pdf",
                "*.png;*.jpg;*.jpeg;*.gif;*.webp" });
        dialog.setFilterNames(new String[] { "Все файлы", "Документы (Word, Excel, PowerPoint, LibreOffice, PDF)", "Картинки" });
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
                updateMcpButton();
                mcpStatus.accept(servers);
            });
        });
    }

    // ---- выбор модели ----

    /** Подпись кнопки модели: короткое имя; полное и сервер — в подсказке. */
    private void updateModelButton() {
        if (modelButton == null || modelButton.isDisposed()) {
            return;
        }
        String text = settings.model().isEmpty() ? "Модель" : ModelChoices.label(settings.model(), 28);
        if (!text.equals(modelButton.getText())) {
            modelButton.setText(text);
            modelButton.getParent().layout(true, true);
        }
        modelButton.setToolTipText(settings.model().isEmpty() ? "Модель не выбрана: щелчок — выбрать"
                : "Модель: " + settings.model() + " · " + host(settings.baseUrl()) + "\nЩелчок — сменить (/model)");
    }

    /** Модели для меню: текущая, недавние, с сервера. */
    List<String> modelChoices() {
        return ModelChoices.merge(settings.model(), ModelChoices.parse(preferences().get(recentModelsKey(), "")), serverModels);
    }

    /** Недавние модели — у каждого сервера свои. */
    private String recentModelsKey() {
        return ModelChoices.recentKey(RECENT_MODELS, settings.baseUrl());
    }

    private String modelsKey() {
        return settings.protocol() + " " + settings.baseUrl();
    }

    /**
     * Список моделей сервера — в фоне, когда понадобится (указатель над
     * кнопкой, меню, {@code /model}) и один раз на сервер; {@code force} —
     * «Обновить список».
     */
    void loadModels(boolean force) {
        loadModels(force, null);
    }

    /** То же; {@code then} — в UI-потоке, когда список есть (или сразу, если загружать не нужно). */
    private void loadModels(boolean force, Runnable then) {
        if (then != null) {
            afterModels.add(then);
        }
        String key = modelsKey();
        if (modelsLoading) {
            return;
        }
        if (!settings.isConfigured() || !force && key.equals(modelsFor)) {
            runAfterModels();
            return;
        }
        modelsFor = key;
        modelsLoading = true;
        modelsError = null;
        background("EDT AI: список моделей", () -> {
            List<String> models = List.of();
            String error = null;
            try {
                models = new ModelClient().models(ModelChatProvider.connection(SettingsStore.workspace())).get(60, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                error = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                error = e.getMessage();
            }
            List<String> loaded = models;
            String problem = error;
            inUi(() -> {
                modelsLoading = false;
                serverModels = loaded;
                modelsError = problem;
                runAfterModels();
            });
        });
    }

    private void runAfterModels() {
        List<Runnable> pending = List.copyOf(afterModels);
        afterModels.clear();
        pending.forEach(Runnable::run);
    }

    /** Меню режима модели ({@link AgentMode}, во всех чатах); выбранный отмечен. */
    Menu modeMenu() {
        Menu menu = new Menu(modeButton);
        AgentMode current = agentMode();
        for (AgentMode each : AgentMode.values()) {
            MenuItem item = new MenuItem(menu, SWT.RADIO);
            item.setText(each.label() + " — " + lowerFirst(each.description()));
            item.setSelection(each == current);
            item.addListener(SWT.Selection, event -> {
                if (item.getSelection() && agentMode() != each) {
                    setAgentMode(each);
                }
            });
        }
        return menu;
    }

    /** «Правит модули…» → «правит модули…»: в середину фразы; «MCP» и прочие сокращения не трогает. */
    private static String lowerFirst(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
    }

    /** Режим модели цветом, как у агентов: «Авто» — оранжевый, «План» — бирюзовый, «Вопросы» — синий. */
    private void updateModeButton() {
        if (modeButton == null || modeButton.isDisposed()) {
            return;
        }
        AgentMode agent = agentMode();
        String text = agent.label();
        if (!text.equals(modeButton.getText())) {
            modeButton.setText(text);
            modeButton.getParent().layout(true, true);
        }
        modeButton.setColor(switch (agent) {
            case AGENT -> null;
            case AUTO -> new RGB(0xD9, 0x77, 0x06);
            case PLAN -> new RGB(0x0D, 0x94, 0x88);
            case ASK -> new RGB(0x25, 0x63, 0xEB);
        });
        modeButton.setToolTipText("Режим «" + agent.label() + "»: " + agent.description() + ".\nЩелчок — сменить режим (/auto,"
                + " /readonly, /plan)");
    }

    /** Меню выбора модели под кнопкой. */
    private void showModelMenu() {
        loadModels(false);
        showMenu(modelMenu(), modelButton);
    }

    /** Меню под кнопкой у поля ввода (над ней, если внизу не хватает места — решает система). */
    private static void showMenu(Menu menu, Control under) {
        menu.setLocation(under.toDisplay(0, under.getSize().y));
        menu.setVisible(true);
    }

    /** Меню моделей: выбранная отмечена; внизу — обновить список и настройки. */
    Menu modelMenu() {
        Menu menu = new Menu(modelButton.getParent());
        for (String name : modelChoices()) {
            MenuItem item = new MenuItem(menu, SWT.RADIO);
            item.setText(name);
            item.setSelection(name.equals(settings.model()));
            item.addListener(SWT.Selection, event -> {
                if (item.getSelection()) {
                    chooseModel(name);
                }
            });
        }
        String state = modelsLoading ? "Загружаю список с сервера…"
                : modelsError != null ? "Список не получен: " + shorten(modelsError, 80)
                : serverModels.isEmpty() && settings.isConfigured() ? "Сервер не отдал список моделей" : null;
        if (state != null) {
            MenuItem note = new MenuItem(menu, SWT.PUSH);
            note.setText(state);
            note.setEnabled(false);
        }
        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem refresh = new MenuItem(menu, SWT.PUSH);
        refresh.setText("Обновить список");
        refresh.setEnabled(settings.isConfigured() && !modelsLoading);
        refresh.addListener(SWT.Selection, event -> {
            loadModels(true);
            showNotice("Загружаю список моделей…");
        });
        MenuItem configure = new MenuItem(menu, SWT.PUSH);
        configure.setText("Настройки модели…");
        configure.addListener(SWT.Selection, event -> openPreferences());
        return menu;
    }

    private static String shorten(String text, int max) {
        String line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= max ? line : line.substring(0, max - 1) + "…";
    }

    /** Выбор модели: в настройки (со следующего запроса), в недавние. */
    void chooseModel(String name) {
        String model = name.strip();
        if (model.isEmpty() || model.equals(settings.model())) {
            return;
        }
        try {
            SettingsStore.workspace().save(settings.withModel(model));
        } catch (BackingStoreException e) {
            showNotice("Модель не сохранить: " + e.getMessage());
            return;
        }
        // Прежняя — тоже в недавние: к ней обычно возвращаются.
        List<String> recent = ModelChoices.remember(ModelChoices.parse(preferences().get(recentModelsKey(), "")), settings.model());
        preferences().put(recentModelsKey(), ModelChoices.format(ModelChoices.remember(recent, model)));
        settings = SettingsStore.workspace().load();
        updateModelButton();
        showNotice("Модель: " + model + (session.isBusy() ? " — со следующего вопроса" : ""));
    }

    /** {@code /model}: без имени — меню, с именем — выбрать (точно или единственную подходящую). */
    private void modelCommand(String argument) {
        if (argument.isBlank()) {
            showModelMenu();
            return;
        }
        loadModels(false, () -> chooseTyped(argument));
    }

    private void chooseTyped(String argument) {
        List<String> choices = modelChoices();
        Optional<String> found = ModelChoices.find(argument, choices);
        if (found.isPresent()) {
            chooseModel(found.get());
            return;
        }
        String lower = argument.strip().toLowerCase(Locale.ROOT);
        List<String> similar = choices.stream().filter(name -> name.toLowerCase(Locale.ROOT).contains(lower)).toList();
        if (similar.size() > 1) {
            showNotice("Под «" + argument.strip() + "» подходят: " + String.join(", ", similar) + " — уточните");
        } else {
            // Сервер может не отдавать список: имя — как набрано.
            chooseModel(argument.strip());
        }
    }

    /** Значок и подсказка кнопки MCP по последнему известному состоянию серверов, без подключения. */
    void updateMcpButton() {
        if (mcpButton == null || mcpButton.isDisposed()) {
            return;
        }
        McpHub.Health health = McpHub.health(mcp.lastStatus(SettingsStore.workspace().load().mcpServers()));
        List<McpHub.ServerStatus> servers = mcp.lastStatus(SettingsStore.workspace().load().mcpServers());
        mcpButton.setText(McpHub.counter(servers));
        mcpButton.setColor(health.state() == McpHub.State.FAILED || health.state() == McpHub.State.UNSUPPORTED
                ? new RGB(0xDC, 0x26, 0x26) : null);
        mcpButton.setToolTipText(health.text() + "\nЩелчок — проверить и показать инструменты (/mcp)");
        mcpButton.setData(health.state());
        mcpButton.getParent().layout(true, true);
    }

    /** При открытии вида — проверка включённых серверов в фоне: индикатор сразу показывает, подключены ли. */
    private void checkMcpInBackground() {
        AiSettings current = SettingsStore.workspace().load();
        if (current.mcpServers().stream().noneMatch(McpServer::enabled)) {
            return;
        }
        background("EDT AI: проверка MCP", () -> {
            mcp.status(current.mcpServers(), TrustSettings.from(current));
            inUi(this::updateMcpButton);
        });
    }

    /** Показ итога {@code /mcp}; тесты подменяют. */
    Consumer<List<McpHub.ServerStatus>> mcpStatus = servers -> new McpStatusDialog(getSite().getShell(), servers,
            this::openPreferences).open();

    /**
     * После ответа: если у чата ещё нет названия, просит модель придумать его
     * по первому обмену — по разу на чат. Пользователь может переименовать.
     */
    private void generateTitle(Live chat) {
        String id = chat.id;
        List<ChatMessage> messages = chat.session.messages();
        boolean answered = messages.stream().anyMatch(message -> message.role() == ChatRole.ASSISTANT
                && message.status() == Status.DONE && !message.text().isBlank());
        if (!preferences().getBoolean(GENERATE_TITLES, true) || !answered || chats.customTitle(id).isPresent()
                || !titleRequested.add(id)) {
            return;
        }
        chat.session.provider().title(messages).whenComplete((title, error) -> inUi(() -> {
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
        return agentMode().readOnly();
    }

    /** Добавляет фрагмент на панель над полем ввода и ставит фокус в поле. */
    /**
     * Кнопка в окне коммита: вопрос «Сообщение коммита»; когда ответ готов,
     * текст из его блока кода — {@code target} (поле окна коммита).
     * {@code false} от него — поля нет, текст остаётся в чате.
     */
    void requestCommitMessage(Predicate<String> target) {
        if (session.isBusy()) {
            showNotice("Дождитесь ответа или остановите его, потом нажмите кнопку ещё раз");
            return;
        }
        commitTarget = target;
        commitChat = current;
        commitAnswer = session.messages().size() + 1;
        submit(COMMIT_PROMPT, List.of(), List.of());
    }

    /** Ответ на «Сообщение коммита» готов: текст — в окно коммита. */
    private void deliverCommitMessage(Live chat) {
        if (chat != commitChat) {
            return;
        }
        Predicate<String> target = commitTarget;
        commitTarget = null;
        commitChat = null;
        List<ChatMessage> messages = chat.session.messages();
        if (target == null || commitAnswer >= messages.size()) {
            return;
        }
        ChatMessage answer = messages.get(commitAnswer);
        if (answer.role() != ChatRole.ASSISTANT || answer.status() != Status.DONE) {
            return;
        }
        String text = CommitMessage.extract(answer.text());
        if (!text.isEmpty() && target.test(text)) {
            showNotice("Сообщение коммита — в окне коммита");
        }
    }

    /**
     * Команда редактора («Объяснить», «Ревью», «Рефакторинг»): фрагмент и
     * готовый вопрос — модели сразу, вместе с уже прикреплённым; идёт ответ —
     * в очередь.
     */
    void askAboutCode(AttachedContext context, String prompt) {
        contextBar.add(context);
        if (session.isBusy()) {
            queueBar.add(new QueueBar.Queued(prompt, contextBar.items(), contextBar.attachments()));
            contextBar.detach();
            showNotice("В очереди: уйдёт после ответа");
            return;
        }
        submit(prompt, contextBar.snapshot(), contextBar.attachments());
        contextBar.clear();
    }

    void attach(AttachedContext context) {
        if (!contextBar.add(context)) {
            showNotice("Этот фрагмент уже прикреплён");
        }
        input.setFocus();
    }

    Menu attachmentMenu() {
        Menu menu = new Menu(getSite().getShell(), SWT.POP_UP);
        MenuItem file = new MenuItem(menu, SWT.PUSH);
        file.setText("Загрузить файл…");
        file.setImage(Icons.image(Icons.ATTACH));
        file.addListener(SWT.Selection, event -> chooseFiles());
        MenuItem module = new MenuItem(menu, SWT.PUSH);
        module.setText("Сослаться на модуль проекта…");
        module.addListener(SWT.Selection, event -> attachModule());
        return menu;
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
        if ((idle || current.saved.isEmpty()) && !messages.equals(current.saved)) {
            saveHistory(current, messages);
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

    /** Подсказка под указателем: полный текст ошибки вызова инструмента. */
    String hintAt(int x, int y) {
        int offset = history.getOffsetAtPoint(new Point(x, y));
        if (offset < 0 || offset >= history.getCharCount()) {
            return null;
        }
        StyleRange style = history.getStyleRangeAtOffset(offset);
        return style != null && style.data instanceof MessageRenderer.Hint hint ? hint.text() : null;
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
            String id = chats.current().orElseGet(chats::newId);
            show(new Live(id), chats.file(id).load());
        } catch (IOException e) {
            show(new Live(chats.newId()), List.of());
            Platform.getLog(getClass()).warn("Не удалось восстановить диалог EDT AI из " + chats.file(chatId).path().getParent(), e);
        }
    }

    private void saveIfChanged(Live chat) {
        List<ChatMessage> messages = chat.session.messages();
        if (!messages.equals(chat.saved)) {
            saveHistory(chat, messages);
        }
    }

    /** Пустой диалог не хранится: файла нет, в истории его нет. */
    private void saveHistory(Live chat, List<ChatMessage> messages) {
        ChatHistoryFile file = chats.file(chat.id);
        try {
            if (messages.isEmpty()) {
                Files.deleteIfExists(file.path());
            } else {
                file.save(messages);
                if (chat == current) {
                    chats.setCurrent(chat.id);
                }
            }
        } catch (IOException e) {
            Platform.getLog(getClass()).warn("Не удалось сохранить диалог EDT AI в " + file.path(), e);
        }
        chat.saved = messages;
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
        boolean typed = !input.getText().isBlank();
        sendButton.setBusy(busy && !typed);
        sendButton.setQueue(busy && typed);
        sendButton.setEnabled(busy || typed);
    }

    /**
     * Строка под полем ввода: модель; пока идёт ответ — что она делает и
     * сколько секунд; потом — сколько окна занято и токены ответа (в чате
     * без ответов в этом сеансе — оценка по истории).
     */
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
            if (agentMode() != AgentMode.AGENT) {
                parts.add("режим «" + agentMode().label() + "»");
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
    private List<Tool> tools(Live chat, Consumer<ToolUse> problems) {
        AiSettings current = SettingsStore.workspace().load();
        if (!current.toolsEnabled()) {
            return List.of();
        }
        EclipseWorkspaceFiles files = EclipseWorkspaceFiles.current();
        List<Tool> tools = new ArrayList<>(ProjectTools.create(files, current::contextLimit));
        tools.addAll(EditTools.create(files, chat.pendingChanges::add));
        tools.addAll(SkillTools.create(skills));
        tools.addAll(ReviewTools.create(files, ReviewMarkers::show));
        tools.addAll(FileTools.create());
        try {
            tools.addAll(GitTools.create(files, current::contextLimit));
        } catch (LinkageError e) {
            // В этой сборке EDT нет JGit (EGit): без инструментов git.
        }
        Set<String> builtIn = tools.stream().map(Tool::name).collect(Collectors.toSet());
        // Сервер не подключился — это видно на кнопке MCP, а не строкой в каждом ответе.
        tools.addAll(mcp.tools(current.mcpServers(), TrustSettings.from(current), builtIn, current::contextLimit, problem -> { }));
        inUi(this::updateMcpButton);
        if (isReadOnly()) {
            tools.removeIf(Tool::modifies);
        }
        // Встроенные читающие инструменты файлов не меняют: изменения во время их вызова — не модели.
        return chat.toolActivity.track(tools, tool -> !builtIn.contains(tool.name()) || tool.modifies());
    }

    /**
     * Вопрос пользователю перед вызовом инструмента MCP-сервера: панель над
     * полем ввода; чат в фоне — вопрос ждёт, пока его откроют.
     */
    private CompletableFuture<Decision> approve(Live chat, ToolUse use, String arguments) {
        if (chat.allowed.contains(use.name()) || isAutoApprove()) {
            return CompletableFuture.completedFuture(Decision.ALLOW);
        }
        CompletableFuture<Decision> decision = new CompletableFuture<>();
        decision.thenAccept(value -> {
            if (value == Decision.ALLOW_IN_CHAT) {
                chat.allowed.add(use.name());
            } else if (value == Decision.ALWAYS) {
                inUi(() -> setAutoApprove(true));
            }
        });
        inUi(() -> {
            if (chat == current) {
                approvalBar.ask(use, arguments, decision);
            } else if (!decision.isDone()) {
                chat.approval = () -> approvalBar.ask(use, arguments, decision);
                showNotice("Чат «" + chats.title(chat.id, chat.session.messages()) + "» ждёт разрешения на " + use.name());
            }
        });
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
        lives.values().forEach(chat -> chat.session.cancel());
        // Закрытие сессий MCP — запросы по сети, не в UI-потоке.
        background("EDT AI: закрытие MCP", mcp::close);
        lives.values().forEach(this::saveIfChanged);
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

    QueueBar queueBar() {
        return queueBar;
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

    SendButton sendButton() {
        return sendButton;
    }

    Chip mcpButton() {
        return mcpButton;
    }

    Chip modeButton() {
        return modeButton;
    }

    Chip modelButton() {
        return modelButton;
    }

    boolean isEmptyStateShown() {
        return topLayout.topControl == emptyState;
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
