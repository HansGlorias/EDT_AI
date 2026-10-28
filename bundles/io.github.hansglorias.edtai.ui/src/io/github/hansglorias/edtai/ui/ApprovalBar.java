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
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.tools.ToolApprover.Decision;

/**
 * Панель над полем ввода: модель хочет вызвать инструмент MCP-сервера —
 * «Разрешить», «Разрешать в этом чате», «Авторежим» (больше не спрашивать)
 * или «Отклонить» (Esc). Видна,
 * только пока есть вопрос. Фокус на «Разрешить» переходит, только если он
 * уже был в виде чата: иначе случайный пробел в редакторе разрешил бы
 * вызов, которого пользователь не видел.
 */
final class ApprovalBar {

    static final String ALLOW = "Разрешить";
    static final String ALLOW_IN_CHAT = "Разрешать в этом чате";
    static final String ALWAYS = "Авторежим";
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
    private final Button always;
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
        always = button(buttons, ALWAYS, Decision.ALWAYS);
        always.setToolTipText("Разрешить и вызывать инструменты дальше без вопроса во всех чатах; выключается кнопкой"
                + " «Авторежим» на панели вида или командой /auto");
        deny = button(buttons, DENY, Decision.DENY);
        setVisible(false);
    }

    private Button button(Composite parent, String text, Decision decision) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.addListener(SWT.Selection, event -> decide(decision));
        button.addListener(SWT.Traverse, event -> {
            if (event.detail == SWT.TRAVERSE_ESCAPE) {
                event.doit = false;
                decide(Decision.DENY);
            }
        });
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
        String shown = json == null ? "" : json.strip();
        boolean empty = shown.isEmpty() || shown.matches("\\{\\s*}");
        arguments.setText(empty ? "" : shown.length() > MAX_ARGUMENTS ? shown.substring(0, MAX_ARGUMENTS) + "…" : shown);
        arguments.setVisible(!empty);
        ((GridData) arguments.getLayoutData()).exclude = empty;
        boolean focusHere = isInside(bar.getDisplay().getFocusControl(), bar.getParent());
        setVisible(true);
        if (focusHere) {
            allow.setFocus();
        }
        Display display = bar.getDisplay();
        decision.whenComplete((value, error) -> display.asyncExec(() -> {
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

    /** «Отклонить»: Esc в поле ввода, пока есть вопрос. */
    void deny() {
        decide(Decision.DENY);
    }

    private static boolean isInside(Control control, Composite parent) {
        for (Control current = control; current != null; current = current.getParent()) {
            if (current == parent) {
                return true;
            }
        }
        return false;
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

    boolean argumentsShown() {
        return arguments.getVisible();
    }

    Button allowButton() {
        return allow;
    }

    Button allowInChatButton() {
        return allowInChat;
    }

    Button alwaysButton() {
        return always;
    }

    Button denyButton() {
        return deny;
    }
}
