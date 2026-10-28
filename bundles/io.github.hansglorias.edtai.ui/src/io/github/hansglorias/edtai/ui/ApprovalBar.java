package io.github.hansglorias.edtai.ui;

import java.util.concurrent.CompletableFuture;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.tools.ToolApprover.Decision;

/**
 * Панель над полем ввода: модель хочет вызвать инструмент MCP-сервера —
 * «Разрешить», «Разрешать в этом чате» или «Отклонить». Видна, только
 * пока есть вопрос.
 */
final class ApprovalBar {

    static final String ALLOW = "Разрешить";
    static final String ALLOW_IN_CHAT = "Разрешать в этом чате";
    static final String DENY = "Отклонить";
    private static final int MAX_ARGUMENTS = 2000;

    /** Решение принято или вопрос снят: нужно перекомпоновать вид. */
    interface Callbacks {
        void changed();
    }

    private final Composite bar;
    private final Label message;
    private final Text arguments;
    private final Button allow;
    private final Button allowInChat;
    private final Button deny;
    private final Callbacks callbacks;
    private CompletableFuture<Decision> pending;

    ApprovalBar(Composite parent, Callbacks callbacks) {
        this.callbacks = callbacks;
        bar = new Composite(parent, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(bar);
        GridLayoutFactory.swtDefaults().margins(6, 6).applyTo(bar);

        message = new Label(bar, SWT.WRAP);
        message.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        GridDataFactory.fillDefaults().grab(true, false).hint(200, SWT.DEFAULT).applyTo(message);

        arguments = new Text(bar, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        arguments.setFont(JFaceResources.getTextFont());
        GridDataFactory.fillDefaults().grab(true, false).hint(200, arguments.getLineHeight() * 3).applyTo(arguments);

        Composite buttons = new Composite(bar, SWT.NONE);
        RowLayout row = new RowLayout(SWT.HORIZONTAL);
        row.marginLeft = 0;
        row.marginTop = 0;
        row.marginBottom = 0;
        buttons.setLayout(row);
        allow = button(buttons, ALLOW, Decision.ALLOW);
        allowInChat = button(buttons, ALLOW_IN_CHAT, Decision.ALLOW_IN_CHAT);
        deny = button(buttons, DENY, Decision.DENY);
        setVisible(false);
    }

    private Button button(Composite parent, String text, Decision decision) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.addListener(SWT.Selection, event -> decide(decision));
        return button;
    }

    /** Показывает вопрос; прежний неотвеченный считается отказом. */
    void ask(ToolUse use, String json, CompletableFuture<Decision> decision) {
        if (pending != null && !pending.isDone()) {
            pending.complete(Decision.DENY);
        }
        pending = decision;
        message.setText("Модель вызывает инструмент: " + use.summary());
        message.setToolTipText(use.name());
        arguments.setText(json.length() > MAX_ARGUMENTS ? json.substring(0, MAX_ARGUMENTS) + "…" : json);
        setVisible(true);
        allow.setFocus();
        decision.whenComplete((value, error) -> bar.getDisplay().asyncExec(() -> {
            if (!bar.isDisposed() && pending == decision) {
                pending = null;
                setVisible(false);
            }
        }));
    }

    /** Снимает вопрос: ответ остановлен. */
    void dismiss() {
        if (pending != null) {
            pending.complete(Decision.DENY);
        }
    }

    boolean isShown() {
        return bar.getVisible();
    }

    private void decide(Decision decision) {
        if (pending != null) {
            pending.complete(decision);
        }
    }

    private void setVisible(boolean visible) {
        if (bar.isDisposed()) {
            return;
        }
        bar.setVisible(visible);
        ((GridData) bar.getLayoutData()).exclude = !visible;
        callbacks.changed();
    }

    // ---- для тестов ----

    Composite control() {
        return bar;
    }

    String messageText() {
        return message.getText();
    }

    String argumentsText() {
        return arguments.getText();
    }

    Button allowButton() {
        return allow;
    }

    Button allowInChatButton() {
        return allowInChat;
    }

    Button denyButton() {
        return deny;
    }
}
