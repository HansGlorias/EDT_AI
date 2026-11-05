package io.github.hansglorias.edtai.ui;

import java.util.Objects;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

import io.github.hansglorias.edtai.core.chat.ContextGauge;

/**
 * Статичный процент занятого окна у кнопки модели, подробности — в подсказке.
 * Без колец и таймеров. Окно неизвестно — счётчик скрыт.
 */
class ContextUsage extends Canvas {

    static final RGB LOW = new RGB(46, 160, 67);
    static final RGB MEDIUM = new RGB(219, 154, 4);
    static final RGB HIGH = new RGB(218, 54, 51);

    private ContextGauge gauge;
    private boolean compacting;

    ContextUsage(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.NO_FOCUS);
        addListener(SWT.Paint, event -> paint(event.gc));
        setVisible(false);
    }

    ContextGauge gauge() {
        return gauge;
    }

    boolean isCompacting() {
        return compacting;
    }

    /** Новое значение отображается сразу, без фонового таймера. */
    void setGauge(ContextGauge value) {
        String tip = value == null ? null
                : value.describe() + "\nПоследний запрос к модели: история, системный промпт, инструменты и результаты"
                        + " вызовов текущего ответа" + (value.estimated() ? "\n≈ — оценка: точное число сервер сообщает после ответа" : "")
                        + (compacting ? "\nИстория сжимается…" : "");
        if (!Objects.equals(tip, getToolTipText())) {
            setToolTipText(tip);
        }
        boolean changed = !Objects.equals(gauge, value);
        gauge = value;
        boolean visible = value != null;
        if (visible != getVisible()) {
            setVisible(visible);
            getParent().layout(true, true);
        }
        if (changed) redraw();
    }

    /** Сжатие отражается только в подсказке. */
    void setCompacting(boolean value) {
        if (value != compacting) {
            compacting = value;
            if (gauge != null) {
                setGauge(gauge);
            }
        }
    }

    static RGB color(ContextGauge.Level level) {
        return switch (level) {
            case LOW -> LOW;
            case MEDIUM -> MEDIUM;
            case HIGH -> HIGH;
        };
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        GC gc = new GC(this);
        try {
            Point size = gc.textExtent("100%");
            return new Point(size.x + 4, size.y + 4);
        } finally {
            gc.dispose();
        }
    }

    private void paint(GC gc) {
        if (gauge == null) {
            return;
        }
        Point size = getSize();
        gc.setBackground(getParent().getBackground());
        gc.fillRectangle(0, 0, size.x, size.y);
        String text = Math.round(gauge.ratio() * 100) + "%";
        Point extent = gc.textExtent(text);
        gc.setForeground(new Color(color(gauge.level())));
        gc.drawText(text, (size.x - extent.x) / 2, (size.y - extent.y) / 2, true);
    }
}
