package io.github.hansglorias.edtai.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;

import io.github.hansglorias.edtai.core.chat.ChatListener;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatSession;
import io.github.hansglorias.edtai.core.chat.EchoChatProvider;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.SettingsStore;

/**
 * Вид «AI Chat»: история диалога, поле ввода, отправка по кнопке или
 * Ctrl+Enter. Пока вместо модели отвечает {@link EchoChatProvider}.
 */
public class ChatView extends ViewPart {

    public static final String ID = "io.github.hansglorias.edtai.ui.chatView";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ChatSession session = new ChatSession(new EchoChatProvider());

    private StyledText history;
    private Text input;
    private Button sendButton;
    private Label status;

    private final ChatListener chatListener = new ChatListener() {
        @Override
        public void messageAdded(ChatMessage message) {
            inUi(() -> append(message));
        }

        @Override
        public void cleared() {
            inUi(() -> history.setText(""));
        }

        @Override
        public void busyChanged(boolean busy) {
            inUi(ChatView.this::updateSendButton);
        }
    };

    private final IPreferenceChangeListener preferenceListener = event -> inUi(this::updateStatus);

    @Override
    public void createPartControl(Composite parent) {
        GridLayoutFactory.fillDefaults().spacing(0, 4).applyTo(parent);

        SashForm sash = new SashForm(parent, SWT.VERTICAL);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(sash);

        history = new StyledText(sash, SWT.MULTI | SWT.V_SCROLL | SWT.WRAP | SWT.READ_ONLY | SWT.BORDER);
        history.setMargins(6, 6, 6, 6);

        Composite bottom = new Composite(sash, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).applyTo(bottom);

        input = new Text(bottom, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        input.setMessage("Сообщение (Ctrl+Enter — отправить)");
        GridDataFactory.fillDefaults().grab(true, true).applyTo(input);
        input.addListener(SWT.KeyDown, event -> {
            boolean enter = event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR;
            if (enter && (event.stateMask & SWT.MOD1) != 0) {
                event.doit = false;
                send();
            }
        });
        input.addModifyListener(event -> updateSendButton());

        sendButton = new Button(bottom, SWT.PUSH);
        sendButton.setText("Отправить");
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.END).applyTo(sendButton);
        sendButton.addListener(SWT.Selection, event -> send());

        sash.setWeights(new int[] { 75, 25 });

        status = new Label(parent, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).indent(4, 0).applyTo(status);

        createActions();

        session.addListener(chatListener);
        preferences().addPreferenceChangeListener(preferenceListener);
        updateStatus();
        updateSendButton();
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

        Action settings = new Action("Настройки EDT AI") {
            @Override
            public void run() {
                PreferencesUtil.createPreferenceDialogOn(getSite().getShell(), AiPreferencePage.ID,
                        new String[] { AiPreferencePage.ID }, null).open();
            }
        };
        settings.setImageDescriptor(images.getImageDescriptor(ISharedImages.IMG_DEF_VIEW));
        toolBar.add(settings);
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

    private void append(ChatMessage message) {
        String header;
        Color color;
        switch (message.role()) {
            case USER -> {
                header = "Вы";
                color = null;
            }
            case ASSISTANT -> {
                header = "Ассистент";
                color = history.getDisplay().getSystemColor(SWT.COLOR_DARK_BLUE);
            }
            default -> {
                header = "Ошибка";
                color = history.getDisplay().getSystemColor(SWT.COLOR_RED);
            }
        }
        if (history.getCharCount() > 0) {
            history.append("\n\n");
        }
        int start = history.getCharCount();
        history.append(header + "  " + TIME.format(message.time()) + "\n");
        StyleRange headerStyle = new StyleRange(start, header.length(), color, null, SWT.BOLD);
        history.setStyleRange(headerStyle);
        history.append(message.text());
        history.setTopIndex(history.getLineCount() - 1);
    }

    private void updateSendButton() {
        if (!sendButton.isDisposed()) {
            sendButton.setEnabled(!session.isBusy() && !input.getText().isBlank());
        }
    }

    private void updateStatus() {
        AiSettings settings = SettingsStore.workspace().load();
        String model = settings.model().isEmpty() ? "не задана" : settings.model();
        status.setText("Режим: " + session.provider().name() + " (модель не подключается) · "
                + settings.protocol().label() + " · модель: " + model);
        status.getParent().layout(true);
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
        preferences().removePreferenceChangeListener(preferenceListener);
        super.dispose();
    }

    ChatSession session() {
        return session;
    }

    Text input() {
        return input;
    }

    StyledText history() {
        return history;
    }

    Label status() {
        return status;
    }
}
