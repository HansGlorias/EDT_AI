package io.github.hansglorias.edtai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

/** Небольшая бегущая дуга: меняется только рисунок, текст и компоновка неподвижны. */
final class ActivityIndicator extends Canvas {
    private static final int FRAME_MS = 60;
    private boolean active;
    private int phase;
    private final Runnable frame = this::frame;
    private java.util.function.IntConsumer changed = value -> { };

    ActivityIndicator(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.NO_FOCUS);
        addListener(SWT.Paint, event -> {
            int diameter = Math.max(4, Math.min(12, getSize().y - 4));
            if (active) paint(event.gc, (getSize().x - diameter) / 2, (getSize().y - diameter) / 2, diameter,
                    getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION), phase);
        });
        addListener(SWT.Dispose, event -> getDisplay().timerExec(-1, frame));
    }

    void onFrame(java.util.function.IntConsumer listener) { changed = listener; }

    void setActive(boolean value) {
        if (value == active) return;
        active = value;
        getDisplay().timerExec(-1, frame);
        if (value) getDisplay().timerExec(FRAME_MS, frame);
        redraw();
    }

    private void frame() {
        if (isDisposed() || !active) return;
        if (isVisible()) {
            phase = (phase + 18) % 360;
            redraw();
            changed.accept(phase);
        }
        getDisplay().timerExec(FRAME_MS, frame);
    }

    static void paint(GC gc, int x, int y, int size, Color color, int phase) {
        gc.setAntialias(SWT.ON);
        gc.setLineWidth(2);
        gc.setLineCap(SWT.CAP_ROUND);
        gc.setForeground(color);
        gc.setAlpha(45);
        gc.drawOval(x, y, size, size);
        gc.setAlpha(230);
        gc.drawArc(x, y, size, size, 90 - phase, -90);
        gc.setAlpha(255);
    }

    @Override
    public Point computeSize(int w, int h, boolean changed) { return new Point(18, 18); }
    boolean isActive() { return active; }
    int phase() { return phase; }
}
