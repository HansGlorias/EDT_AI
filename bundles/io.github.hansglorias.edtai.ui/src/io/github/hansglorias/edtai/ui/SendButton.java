package io.github.hansglorias.edtai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;

/** Кнопка отправки и остановки в том же стиле и размере, что остальные кнопки ввода. */
final class SendButton extends Chip {

    private boolean busy;
    private boolean queue;

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
                : "Отправить (Enter)");
    }

    @Override
    protected boolean iconOnly() {
        return true;
    }

    @Override
    protected void paintIcon(GC gc, Point size) {
        int cx = size.x / 2;
        int cy = size.y / 2;
        int half = Math.max(3, size.y / 4);
        if (busy) {
            gc.setBackground(gc.getForeground());
            gc.fillRoundRectangle(cx - half, cy - half, 2 * half, 2 * half, 2, 2);
        } else {
            gc.setLineWidth(Math.max(1, size.y / 12));
            gc.setLineCap(SWT.CAP_ROUND);
            gc.setLineJoin(SWT.JOIN_ROUND);
            gc.drawLine(cx, cy + half, cx, cy - half);
            gc.drawPolyline(new int[] { cx - half, cy, cx, cy - half, cx + half, cy });
        }
    }
}
