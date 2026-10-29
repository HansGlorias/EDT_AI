package io.github.hansglorias.edtai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;

/**
 * Круглая кнопка у поля ввода, как в чатах: стрелка вверх — отправить,
 * квадрат — остановить ответ. Без текста, подпись — в подсказке. Цвет —
 * фирменный синий EDT AI, без текста для отправки — серый круг.
 */
final class SendButton extends Canvas {

    /** Диаметр, в точках экрана. */
    static final int SIZE = 28;
    private static final RGB ACCENT = new RGB(52, 104, 214);
    private static final RGB ACCENT_HOVER = new RGB(38, 84, 186);

    private final Color accent;
    private final Color accentHover;
    private Color disabled;
    private RGB disabledFrom;
    private boolean busy;
    private boolean hover;

    SendButton(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.NO_FOCUS);
        accent = new Color(ACCENT);
        accentHover = new Color(ACCENT_HOVER);
        addListener(SWT.Paint, this::paint);
        addListener(SWT.MouseEnter, event -> setHover(true));
        addListener(SWT.MouseExit, event -> setHover(false));
        addListener(SWT.MouseUp, event -> {
            if (event.button == 1 && isEnabled() && getClientArea().contains(event.x, event.y)) {
                notifyListeners(SWT.Selection, new Event());
            }
        });
        addListener(SWT.Dispose, event -> {
            accent.dispose();
            accentHover.dispose();
            if (disabled != null) {
                disabled.dispose();
            }
        });
        setCursor(getDisplay().getSystemCursor(SWT.CURSOR_HAND));
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

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        return new Point(SIZE, SIZE);
    }

    private void updateTooltip() {
        setToolTipText(busy ? "Остановить ответ (Esc)" : "Отправить (Enter)");
    }

    private void setHover(boolean value) {
        hover = value;
        redraw();
    }

    private void paint(Event event) {
        GC gc = event.gc;
        gc.setAntialias(SWT.ON);
        gc.setBackground(getParent().getBackground());
        gc.fillRectangle(getClientArea());
        boolean active = isEnabled();
        gc.setBackground(!active ? disabledColor() : hover ? accentHover : accent);
        gc.fillOval(0, 0, SIZE - 1, SIZE - 1);
        Color white = getDisplay().getSystemColor(SWT.COLOR_WHITE);
        gc.setBackground(white);
        gc.setForeground(white);
        int c = SIZE / 2;
        if (busy) {
            gc.fillRoundRectangle(c - 5, c - 5, 10, 10, 3, 3);
        } else {
            // Стрелка вверх: стержень и наконечник.
            gc.setLineWidth(2);
            gc.setLineCap(SWT.CAP_ROUND);
            gc.setLineJoin(SWT.JOIN_ROUND);
            gc.drawLine(c, c + 6, c, c - 5);
            gc.drawPolyline(new int[] { c - 5, c, c, c - 6, c + 5, c });
        }
    }

    /** Серый круг: текст подмешан к фону — виден и в тёмной теме. */
    private Color disabledColor() {
        RGB background = getParent().getBackground().getRGB();
        if (disabled == null || !background.equals(disabledFrom)) {
            if (disabled != null) {
                disabled.dispose();
            }
            RGB foreground = getParent().getForeground().getRGB();
            disabled = new Color((background.red * 3 + foreground.red) / 4, (background.green * 3 + foreground.green) / 4,
                    (background.blue * 3 + foreground.blue) / 4);
            disabledFrom = background;
        }
        return disabled;
    }
}
