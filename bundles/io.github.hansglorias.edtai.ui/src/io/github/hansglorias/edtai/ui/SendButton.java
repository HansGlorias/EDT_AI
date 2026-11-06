package io.github.hansglorias.edtai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;

/** Кнопка отправки и остановки в том же стиле и размере, что остальные кнопки ввода. */
final class SendButton extends Chip {

    private boolean busy;
    private boolean queue;
    private boolean editing;

    SendButton(Composite parent) {
        super(parent, false);
        updateTooltip();
    }

    /** Идёт ответ: квадрат «Стоп». */
    void setBusy(boolean value) {
        if (busy != value) {
            busy = value;
            updateTooltip();
            redraw();
        }
    }

    boolean isBusy() {
        return busy;
    }

    /** Идёт ответ, а в поле набран текст: стрелка ставит его в очередь. */
    void setQueue(boolean value) {
        if (queue != value) {
            queue = value;
            updateTooltip();
        }
    }

    void setEditing(boolean value) {
        if (editing != value) {
            editing = value;
            updateTooltip();
        }
    }

    /** Что делает кнопка сейчас: «Отправить» или «Стоп». */
    String getText() {
        return busy ? ChatView.STOP : ChatView.SEND;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled != getEnabled()) {
            super.setEnabled(enabled);
            redraw();
        }
    }

    private void updateTooltip() {
        setToolTipText(busy ? "Остановить ответ (Esc)" : queue ? "В очередь: уйдёт после ответа (Enter). Остановить ответ — Esc"
                : editing ? "Изменить сообщение и отправить заново (Enter)" : "Отправить (Enter)");
    }

    @Override
    protected boolean iconOnly() {
        return true;
    }

    @Override
    protected void paintIcon(GC gc, Point size) {
        Icons.paint(gc, busy ? "stop" : "send", size);
    }
}
