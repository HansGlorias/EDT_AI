package io.github.hansglorias.edtai.ui;

import java.net.URI;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
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
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;

import io.github.hansglorias.edtai.core.chat.ChatListener;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatMessage.Status;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.ChatSession;
import io.github.hansglorias.edtai.core.llm.ModelChatProvider;
import io.github.hansglorias.edtai.core.llm.ModelClient;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.SettingsStore;

/**
 * Вид «AI Chat»: лента диалога, поле ввода, отправка по кнопке или
 * Ctrl+Enter. Ответ модели появляется по мере генерации, его можно
 * остановить кнопкой «Стоп» или Esc.
 */
public class ChatView extends ViewPart {

    public static final String ID = "io.github.hansglorias.edtai.ui.chatView";

    static final String SEND = "Отправить";
    static final String STOP = "Стоп";
    static final String RETRY = "Повторить";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    /** Как часто перерисовывать ленту во время стриминга. */
    private static final int REFRESH_DELAY_MS = 40;

    private final ChatSession session = new ChatSession(ModelChatProvider.forStore(SettingsStore.workspace(), new ModelClient()));

    private Composite root;
    private Composite top;
    private StackLayout topLayout;
    private StyledText history;
    private Composite emptyState;
    private Label emptyTitle;
    private Label emptyText;
    private Link emptyLink;
    private Text input;
    private Button sendButton;
    private Label status;
    private Cursor handCursor;

    /** Что сейчас нарисовано в ленте: сообщение, есть ли у него «Повторить» и с какого символа оно начинается. */
    private record Rendered(ChatMessage message, boolean retry, int start) {
    }

    private final List<Rendered> rendered = new ArrayList<>();
    private boolean refreshScheduled;

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
        }
    };

    private final IPreferenceChangeListener preferenceListener = event -> inUi(() -> {
        settings = SettingsStore.workspace().load();
        updateStatus();
        updateEmptyState();
    });

    /** Настройки для подсказок и строки состояния; запросы к модели читают их сами. */
    private AiSettings settings = AiSettings.defaults();

    @Override
    public void createPartControl(Composite parent) {
        root = parent;
        GridLayoutFactory.fillDefaults().spacing(0, 4).applyTo(parent);
        handCursor = parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND);

        SashForm sash = new SashForm(parent, SWT.VERTICAL);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(sash);

        top = new Composite(sash, SWT.NONE);
        topLayout = new StackLayout();
        top.setLayout(topLayout);
        createHistory(top);
        createEmptyState(top);

        Composite bottom = new Composite(sash, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).applyTo(bottom);

        input = new Text(bottom, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        input.setMessage("Сообщение (Ctrl+Enter — отправить, Esc — остановить ответ)");
        GridDataFactory.fillDefaults().grab(true, true).applyTo(input);
        input.addListener(SWT.KeyDown, this::onInputKey);
        input.addModifyListener(event -> updateSendButton());

        sendButton = new Button(bottom, SWT.PUSH);
        int width = 0;
        for (String text : new String[] { SEND, STOP }) {
            sendButton.setText(text);
            width = Math.max(width, sendButton.computeSize(SWT.DEFAULT, SWT.DEFAULT).x);
        }
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.END).hint(width, SWT.DEFAULT).applyTo(sendButton);
        sendButton.addListener(SWT.Selection, event -> sendOrStop());

        sash.setWeights(new int[] { 78, 22 });

        status = new Label(parent, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).indent(4, 0).applyTo(status);

        createActions();

        session.addListener(chatListener);
        preferences().addPreferenceChangeListener(preferenceListener);
        settings = SettingsStore.workspace().load();
        updateStatus();
        updateEmptyState();
        updateSendButton();
    }

    private void createHistory(Composite parent) {
        history = new StyledText(parent, SWT.MULTI | SWT.V_SCROLL | SWT.WRAP | SWT.READ_ONLY | SWT.BORDER);
        history.setMargins(8, 8, 8, 8);
        history.addListener(SWT.MouseUp, event -> {
            if (event.button == 1 && history.getSelectionCount() == 0) {
                Runnable action = actionAt(event.x, event.y);
                if (action != null) {
                    action.run();
                }
            }
        });
        history.addListener(SWT.MouseMove, event -> history.setCursor(actionAt(event.x, event.y) != null ? handCursor : null));
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

    private void createActions() {
        ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
        IToolBarManager toolBar = getViewSite().getActionBars().getToolBarManager();

        Action clear = new Action("Очистить историю") {
            @Override
            public void run() {
                session.clear();
            }
        };
        clear.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_ETOOL_CLEAR));
        toolBar.add(clear);

        Action preferencesAction = new Action("Настройки EDT AI") {
            @Override
            public void run() {
                openPreferences();
            }
        };
        preferencesAction.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_DEF_VIEW));
        toolBar.add(preferencesAction);
    }

    private void openPreferences() {
        PreferencesUtil.createPreferenceDialogOn(getSite().getShell(), AiPreferencePage.ID,
                new String[] { AiPreferencePage.ID }, null).open();
    }

    private void onInputKey(Event event) {
        boolean enter = event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR;
        if (enter && (event.stateMask & SWT.MOD1) != 0) {
            event.doit = false;
            send();
        } else if (event.keyCode == SWT.ESC && session.isBusy()) {
            event.doit = false;
            session.cancel();
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
        input.setText("");
        session.send(text);
    }

    private void retry() {
        if (session.canRetry()) {
            session.retry();
        }
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
        boolean canRetry = session.canRetry();
        int first = 0;
        while (first < rendered.size() && first < messages.size()
                && rendered.get(first).message().equals(messages.get(first))
                && rendered.get(first).retry() == showRetry(messages, first, canRetry)) {
            first++;
        }
        if (first < rendered.size() || first < messages.size()) {
            boolean atBottom = isScrolledToBottom();
            int start = first < rendered.size() ? rendered.get(first).start() : history.getCharCount();
            rendered.subList(first, rendered.size()).clear();

            StringBuilder text = new StringBuilder();
            List<StyleRange> styles = new ArrayList<>();
            for (int i = first; i < messages.size(); i++) {
                boolean retry = showRetry(messages, i, canRetry);
                rendered.add(new Rendered(messages.get(i), retry, start + text.length()));
                render(messages.get(i), i > 0, retry, start, text, styles);
            }
            history.replaceTextRange(start, history.getCharCount() - start, text.toString());
            for (StyleRange style : styles) {
                history.setStyleRange(style);
            }
            if (atBottom) {
                history.setTopPixel(Integer.MAX_VALUE);
            }
        }
        updateEmptyState();
        updateSendButton();
        updateStatus();
    }

    private static boolean showRetry(List<ChatMessage> messages, int index, boolean canRetry) {
        if (!canRetry || index != messages.size() - 1) {
            return false;
        }
        ChatMessage message = messages.get(index);
        return message.role() == ChatRole.ERROR || message.status() == Status.STOPPED;
    }

    private void render(ChatMessage message, boolean separator, boolean retry, int offset, StringBuilder text, List<StyleRange> styles) {
        if (separator) {
            text.append("\n\n");
        }
        String name;
        Color nameColor;
        switch (message.role()) {
            case USER -> {
                name = "Вы";
                nameColor = null;
            }
            case ASSISTANT -> {
                name = "Ассистент";
                nameColor = color(JFacePreferences.COUNTER_COLOR, SWT.COLOR_DARK_BLUE);
            }
            default -> {
                name = "Ошибка";
                nameColor = errorColor();
            }
        }
        styles.add(new StyleRange(offset + text.length(), name.length(), nameColor, null, SWT.BOLD));
        text.append(name);

        String meta = "  " + TIME.format(message.time()) + switch (message.status()) {
            case STREAMING -> " · пишет…";
            case STOPPED -> message.role() == ChatRole.ASSISTANT ? " · остановлено" : "";
            case DONE -> "";
        };
        styles.add(new StyleRange(offset + text.length(), meta.length(), metaColor(), null));
        text.append(meta).append('\n');

        String body = message.text();
        if (body.isEmpty() && message.role() == ChatRole.ASSISTANT) {
            String placeholder = switch (message.status()) {
                case STREAMING -> "…";
                case STOPPED -> "(ответ не получен)";
                case DONE -> "(пустой ответ)";
            };
            styles.add(new StyleRange(offset + text.length(), placeholder.length(), metaColor(), null, SWT.ITALIC));
            text.append(placeholder);
        } else {
            if (message.role() == ChatRole.ERROR) {
                styles.add(new StyleRange(offset + text.length(), body.length(), errorColor(), null));
            }
            text.append(body);
        }

        if (retry) {
            text.append('\n');
            StyleRange link = new StyleRange(offset + text.length(), RETRY.length(), linkColor(), null);
            link.underline = true;
            link.data = (Runnable) this::retry;
            styles.add(link);
            text.append(RETRY);
        }
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

    private Color color(String key, int fallback) {
        Color color = JFaceResources.getColorRegistry().get(key);
        return color != null ? color : history.getDisplay().getSystemColor(fallback);
    }

    private Color errorColor() {
        return color(JFacePreferences.ERROR_COLOR, SWT.COLOR_RED);
    }

    private Color metaColor() {
        return color(JFacePreferences.QUALIFIER_COLOR, SWT.COLOR_DARK_GRAY);
    }

    private Color linkColor() {
        return color(JFacePreferences.HYPERLINK_COLOR, SWT.COLOR_LINK_FOREGROUND);
    }

    // ---- состояние ----

    private void updateEmptyState() {
        boolean configured = settings.isConfigured();
        String title = configured ? "Чем помочь?" : "Модель не настроена";
        String text = configured
                ? "Задайте вопрос модели " + settings.model() + ". Ctrl+Enter — отправить, Esc — остановить ответ."
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
        if (!settings.isConfigured()) {
            text = "Модель не настроена";
        } else {
            text = settings.model() + " · " + host(settings.baseUrl()) + (session.isBusy() ? " · генерирует ответ…" : "");
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

    private static IEclipsePreferences preferences() {
        return InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER);
    }

    @Override
    public void setFocus() {
        input.setFocus();
    }

    @Override
    public void dispose() {
        session.removeListener(chatListener);
        session.cancel();
        preferences().removePreferenceChangeListener(preferenceListener);
        super.dispose();
    }

    // ---- для тестов ----

    ChatSession session() {
        return session;
    }

    Composite control() {
        return root;
    }

    Text input() {
        return input;
    }

    StyledText history() {
        return history;
    }

    Button sendButton() {
        return sendButton;
    }

    Composite emptyState() {
        return emptyState;
    }

    boolean isEmptyStateShown() {
        return topLayout.topControl == emptyState;
    }

    Label status() {
        return status;
    }

    /** Действие ссылки с текстом {@code text} в ленте или {@code null}. */
    Runnable linkAction(String text) {
        for (StyleRange style : history.getStyleRanges()) {
            if (style.data instanceof Runnable action && history.getTextRange(style.start, style.length).equals(text)) {
                return action;
            }
        }
        return null;
    }
}
