package io.github.hansglorias.edtai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;

/** Ядро EDT AI на левом поле активного ответа. Кадр меняет только собственный Canvas. */
final class ReplyIndicator extends Canvas {
    static final int SIZE = 20;
    static final int GUTTER = 24;
    private static final int FRAME_MS = 40;
    private static final long PERIOD_NANOS = 2_400_000_000L;
    private final StyledText history;
    private final Runnable frame = this::frame;
    private final long started = System.nanoTime();
    private int anchor = -1;
    private boolean scheduled;
    private boolean positionQueued;
    private double phase;
    private int frames;
    private int logoY;

    ReplyIndicator(StyledText history) {
        super(history.getParent(), SWT.DOUBLE_BUFFERED | SWT.NO_FOCUS);
        this.history = history;
        moveAbove(history);
        setVisible(false);
        setToolTipText("Модель работает");
        addListener(SWT.Paint, event -> {
            event.gc.setBackground(history.getBackground());
            event.gc.fillRectangle(getClientArea());
            if (anchor >= 0) paintLogo(event.gc, new Rectangle(0, logoY, SIZE, SIZE), phase);
        });
        addListener(SWT.Dispose, event -> getDisplay().timerExec(-1, frame));
        history.addListener(SWT.Resize, event -> queuePosition());
        history.addListener(SWT.Show, event -> queuePosition());
        history.addListener(SWT.Hide, event -> queuePosition());
        history.addListener(SWT.KeyUp, event -> queuePosition());
        history.addCaretListener(event -> queuePosition());
    }

    /** Смещение строки состояния активного ответа, -1 — работа закончена или ждёт пользователя. */
    void follow(int offset) {
        anchor = offset;
        reposition();
    }

    private void queuePosition() {
        if (positionQueued) return;
        positionQueued = true;
        getDisplay().asyncExec(() -> {
            positionQueued = false;
            if (!isDisposed()) reposition();
        });
    }

    void reposition() {
        if (isDisposed()) return;
        boolean visible = anchor >= 0 && anchor < history.getCharCount() && history.isVisible();
        int previousY = logoY;
        if (visible) {
            Point at = history.getLocationAtOffset(anchor);
            logoY = at.y + (history.getLineHeight(anchor) - SIZE) / 2;
            visible = logoY >= 0 && logoY + SIZE <= history.getClientArea().height;
        }
        if (previousY != logoY) { redraw(0, previousY, GUTTER, SIZE, false); redraw(0, logoY, GUTTER, SIZE, false); }
        if (getVisible() != visible) setVisible(visible);
        if (visible && !scheduled) {
            scheduled = true;
            phase = phaseNow();
            redraw(0, logoY, GUTTER, SIZE, false);
            getDisplay().timerExec(FRAME_MS, frame);
        } else if (!visible && scheduled) {
            scheduled = false;
            getDisplay().timerExec(-1, frame);
        }
    }

    private double phaseNow() {
        return (System.nanoTime() - started) % PERIOD_NANOS / (double) PERIOD_NANOS;
    }

    private void frame() {
        if (isDisposed() || !scheduled) return;
        phase = phaseNow();
        frames++;
        redraw(0, logoY, GUTTER, SIZE, false);
        getDisplay().timerExec(FRAME_MS, frame);
    }

    /** Та же геометрия, что tools/GenerateIcons.java: четыре угла и ромб внутри. */
    static void paintLogo(GC gc, Rectangle area, double phase) {
        float scale = Math.min(area.width, area.height) / 16f;
        float x = area.x + (area.width - 16 * scale) / 2;
        float y = area.y + (area.height - 16 * scale) / 2;
        gc.setAntialias(SWT.ON);
        gc.setLineWidth(Math.max(1, Math.round(1.35f * scale)));
        gc.setLineCap(SWT.CAP_ROUND);
        gc.setLineJoin(SWT.JOIN_ROUND);
        Color brand = new Color(0x5B, 0x7B, 0xD5);
        gc.setForeground(brand);
        gc.setBackground(brand);
        Path corners = new Path(gc.getDevice());
        Path core = new Path(gc.getDevice());
        try {
            corner(corners, x, y, scale, 5.3f, 1.9f, 1.9f, 1.9f, 1.9f, 5.3f);
            corner(corners, x, y, scale, 10.7f, 1.9f, 14.1f, 1.9f, 14.1f, 5.3f);
            corner(corners, x, y, scale, 1.9f, 10.7f, 1.9f, 14.1f, 5.3f, 14.1f);
            corner(corners, x, y, scale, 10.7f, 14.1f, 14.1f, 14.1f, 14.1f, 10.7f);
            gc.drawPath(corners);
            float radius = 3.5f * scale * (float) (0.91 + 0.09 * Math.cos(2 * Math.PI * phase));
            float cx = x + 8 * scale;
            float cy = y + 8 * scale;
            core.moveTo(cx, cy - radius);
            core.lineTo(cx + radius, cy);
            core.lineTo(cx, cy + radius);
            core.lineTo(cx - radius, cy);
            core.close();
            gc.fillPath(core);
        } finally {
            corners.dispose();
            core.dispose();
            brand.dispose();
        }
    }

    private static void corner(Path path, float x, float y, float scale, float... points) {
        path.moveTo(x + points[0] * scale, y + points[1] * scale);
        for (int i = 2; i < points.length; i += 2) path.lineTo(x + points[i] * scale, y + points[i + 1] * scale);
    }

    boolean isAnimating() { return scheduled; }
    int frames() { return frames; }
}
