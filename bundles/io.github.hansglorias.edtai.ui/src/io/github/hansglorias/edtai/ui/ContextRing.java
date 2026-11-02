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
 * подсказке. Значение меняется плавно; пока история сжимается, по кольцу
 * бежит дуга, потом заполнение плавно уменьшается. Окно неизвестно — кольцо скрыто.
 */
class ContextRing extends Canvas {

    static final RGB LOW = new RGB(46, 160, 67);
    static final RGB MEDIUM = new RGB(219, 154, 4);
    static final RGB HIGH = new RGB(218, 54, 51);

    /** Кадр анимации, мс: около 60 кадров в секунду. */
    static final int FRAME_MS = 16;
    /** Доля оставшегося пути за кадр: плавное замедление к цели, ~0,3 с. */
    private static final double EASE = 0.18;
    /** Шаг бегущей дуги за кадр, градусов: оборот примерно за секунду. */
    private static final int SPIN_STEP = 6;
    private static final int SPIN_LENGTH = 70;

    private ContextGauge gauge;
    /** Показанная доля окна: догоняет {@link #gauge}. */
    private double shown = -1;
    private boolean compacting;
    private int spin;
    private boolean animating;

    ContextRing(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED);
        addListener(SWT.Paint, event -> paint(event.gc));
        setVisible(false);
    }

    ContextGauge gauge() {
        return gauge;
    }

    /** Показанная сейчас доля окна; равна доле {@link #gauge()}, когда анимация закончилась. */
    double shownRatio() {
        return shown;
    }

    boolean isCompacting() {
        return compacting;
    }

    boolean isAnimating() {
        return animating;
    }

    /** Новое значение; кольцо дойдёт до него плавно. */
    void setGauge(ContextGauge value) {
        String tip = value == null ? null
                : value.describe() + "\nПоследний запрос к модели: история, системный промпт, инструменты и результаты"
                        + " вызовов текущего ответа" + (value.estimated() ? "\n≈ — оценка: точное число сервер сообщает после ответа" : "")
                        + (compacting ? "\nИстория сжимается…" : "");
        if (!Objects.equals(tip, getToolTipText())) {
            setToolTipText(tip);
        }
        gauge = value;
        boolean visible = value != null;
        if (visible != getVisible()) {
            setVisible(visible);
            getParent().layout(true, true);
        }
        if (value == null) {
            shown = -1;
            return;
        }
        if (shown < 0) {
            // Первое значение — сразу, без роста от нуля.
            shown = value.ratio();
            redraw();
        } else if (Math.abs(shown - value.ratio()) > 0.001) {
            animate();
        }
    }

    /** Идёт сжатие истории: бегущая дуга. */
    void setCompacting(boolean value) {
        if (value != compacting) {
            compacting = value;
            if (gauge != null) {
                setGauge(gauge);
            }
            animate();
        }
    }

    private void animate() {
        if (!animating && !isDisposed()) {
            animating = true;
            getDisplay().timerExec(FRAME_MS, this::frame);
        }
    }

    private void frame() {
        if (isDisposed()) {
            return;
        }
        boolean settled = true;
        if (gauge != null && shown >= 0) {
            double target = gauge.ratio();
            double step = (target - shown) * EASE;
            if (Math.abs(target - shown) < 0.002) {
                shown = target;
            } else {
                shown += step;
                settled = false;
            }
        }
        if (compacting) {
            spin = (spin + SPIN_STEP) % 360;
            settled = false;
        }
        redraw();
        if (settled) {
            animating = false;
        } else {
            getDisplay().timerExec(FRAME_MS, this::frame);
        }
    }

    static RGB color(ContextGauge.Level level) {
        return switch (level) {
            case LOW -> LOW;
            case MEDIUM -> MEDIUM;
            case HIGH -> HIGH;
        };
    }

    /** Уровень по показанной доле: цвет меняется вместе с анимацией. */
    static ContextGauge.Level level(double ratio) {
        return ratio >= ContextGauge.HIGH_FROM ? ContextGauge.Level.HIGH
                : ratio >= ContextGauge.MEDIUM_FROM ? ContextGauge.Level.MEDIUM : ContextGauge.Level.LOW;
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
        if (gauge == null || shown < 0) {
            return;
        }
        Point size = getSize();
        RGB fg = getParent().getForeground().getRGB();
        RGB bg = getParent().getBackground().getRGB();
        gc.setBackground(getParent().getBackground());
        gc.fillRectangle(0, 0, size.x, size.y);
        int line = Math.max(2, size.y / 7);
        int diameter = Math.min(size.x, size.y) - line - 4;
        int x = (size.x - diameter) / 2;
        int y = (size.y - diameter) / 2;
        gc.setAntialias(SWT.ON);
        gc.setLineCap(SWT.CAP_ROUND);
        gc.setLineWidth(line);
        gc.setForeground(new Color(blend(fg, bg, 0.22)));
        gc.drawOval(x, y, diameter, diameter);
        RGB levelColor = color(level(shown));
        int angle = (int) Math.round(360 * shown);
        if (angle > 0) {
            // Во время сжатия заполнение приглушено: внимание — бегущей дуге.
            gc.setForeground(new Color(compacting ? blend(levelColor, bg, 0.45) : levelColor));
            gc.drawArc(x, y, diameter, diameter, 90, -Math.max(angle, 8));
        }
        if (compacting) {
            gc.setForeground(new Color(levelColor));
            gc.drawArc(x, y, diameter, diameter, 90 - spin, -SPIN_LENGTH);
        }
    }

    private static RGB blend(RGB front, RGB back, double share) {
        return new RGB((int) Math.round(front.red * share + back.red * (1 - share)),
                (int) Math.round(front.green * share + back.green * (1 - share)),
                (int) Math.round(front.blue * share + back.blue * (1 - share)));
    }
}
