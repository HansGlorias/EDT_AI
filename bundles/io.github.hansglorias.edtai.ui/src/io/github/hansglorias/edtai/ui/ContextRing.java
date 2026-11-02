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
 * Кольцо у кнопки модели: сколько окна модели занято. Дуга растёт по часовой
 * стрелке от верха, цвет — по уровню (зелёный, жёлтый, красный), цифры — в
 * подсказке. Окно неизвестно — кольцо скрыто.
 */
class ContextRing extends Canvas {

    static final RGB LOW = new RGB(46, 160, 67);
    static final RGB MEDIUM = new RGB(219, 154, 4);
    static final RGB HIGH = new RGB(218, 54, 51);

    private ContextGauge gauge;
    private int shownPercent = -1;
    private ContextGauge.Level shownLevel;

    ContextRing(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED);
        addListener(SWT.Paint, event -> paint(event.gc));
        setVisible(false);
    }

    ContextGauge gauge() {
        return gauge;
    }

    /** Новое значение; перерисовка — только если сменился процент или цвет. */
    void setGauge(ContextGauge value) {
        String tip = value == null ? null
                : value.describe() + (value.estimated() ? "\n≈ — оценка: точное число сервер сообщает после ответа" : "");
        if (!Objects.equals(tip, getToolTipText())) {
            setToolTipText(tip);
        }
        gauge = value;
        boolean visible = value != null;
        if (visible != getVisible()) {
            setVisible(visible);
            getParent().layout(true, true);
        }
        int percent = value == null ? -1 : value.percent();
        ContextGauge.Level level = value == null ? null : value.level();
        if (percent != shownPercent || level != shownLevel) {
            shownPercent = percent;
            shownLevel = level;
            redraw();
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
            int size = gc.getFontMetrics().getHeight() + 4;
            return new Point(size, size);
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
        int line = Math.max(2, size.y / 7);
        int diameter = Math.min(size.x, size.y) - line - 4;
        int x = (size.x - diameter) / 2;
        int y = (size.y - diameter) / 2;
        gc.setAntialias(SWT.ON);
        gc.setLineCap(SWT.CAP_ROUND);
        gc.setLineWidth(line);
        RGB fg = getParent().getForeground().getRGB();
        RGB bg = getParent().getBackground().getRGB();
        gc.setForeground(new Color(blend(fg, bg, 0.22)));
        gc.drawOval(x, y, diameter, diameter);
        int angle = (int) Math.round(360 * gauge.ratio());
        if (angle > 0) {
            gc.setForeground(new Color(color(gauge.level())));
            gc.drawArc(x, y, diameter, diameter, 90, -Math.max(angle, 8));
        }
    }

    private static RGB blend(RGB front, RGB back, double share) {
        return new RGB((int) Math.round(front.red * share + back.red * (1 - share)),
                (int) Math.round(front.green * share + back.green * (1 - share)),
                (int) Math.round(front.blue * share + back.blue * (1 - share)));
    }
}
