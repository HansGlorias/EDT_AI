package io.github.hansglorias.edtai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;

/** Ядро EDT AI внутри строки состояния. Кадр меняет только собственный Canvas. */
final class ReplyIndicator extends Canvas {
    static final int SIZE = 14;
    static final int GAP = 8;
    private static final int FRAME_MS = 40;
    private static final long PERIOD_NANOS = 2_600_000_000L;
    private final StyledText history;
    private final Runnable frame = this::frame;
    private final long started = System.nanoTime();
    private int anchor = -1;
    private boolean scheduled;
    private boolean positionQueued;
    private double phase;
    private int frames;

    ReplyIndicator(StyledText history) {
        super(history.getParent(), SWT.DOUBLE_BUFFERED | SWT.NO_FOCUS);
        this.history = history;
        GridData layout = new GridData();
        layout.exclude = true;
        setLayoutData(layout);
        setSize(SIZE, SIZE);
        moveAbove(history);
        setVisible(false);
        setToolTipText("Модель работает");
        addListener(SWT.Paint, event -> {
            event.gc.setBackground(history.getBackground());
            event.gc.fillRectangle(getClientArea());
            if (anchor >= 0) paintLogo(event.gc, logoBounds(), phase);
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
        if (visible) {
            Rectangle glyph = history.getTextBounds(anchor, anchor);
            int y = glyph.y + (glyph.height - SIZE) / 2;
            visible = y >= history.getTopMargin() && y + SIZE <= history.getClientArea().height;
            Rectangle bounds = new Rectangle(history.getBounds().x + glyph.x, history.getBounds().y + y, SIZE, SIZE);
            if (!getBounds().equals(bounds)) setBounds(bounds);
        }
        if (getVisible() != visible) setVisible(visible);
        if (visible && !scheduled) {
            scheduled = true;
            phase = phaseNow();
            redraw();
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
        redraw();
        getDisplay().timerExec(FRAME_MS, frame);
    }

    /** Та же геометрия, что tools/GenerateIcons.java: четыре угла и ромб внутри. */
    static void paintLogo(GC gc, Rectangle area, double phase) {
        int alpha = gc.getAlpha();
        int interpolation = gc.getInterpolation();
        try {
            gc.setInterpolation(SWT.HIGH);
            // Оба слоя из одного вектора: SWT смещает нечётный stroke относительно fill на полпикселя.
            layer(gc, Icons.image("edtai_frame"), area);
            // Плавное затухание и возвращение: ядро всегда видно, геометрия неподвижна.
            double breath = 0.3 + 0.7 * (0.5 + 0.5 * Math.cos(2 * Math.PI * phase));
            gc.setAlpha((int) Math.round(alpha * breath));
            layer(gc, Icons.image("edtai_core"), area);
        } finally {
            gc.setAlpha(alpha);
            gc.setInterpolation(interpolation);
        }
    }

    private static void layer(GC gc, Image image, Rectangle area) {
        Rectangle source = image.getBounds();
        int side = Math.min(area.width, area.height);
        gc.drawImage(image, 0, 0, source.width, source.height,
                area.x + (area.width - side) / 2, area.y + (area.height - side) / 2, side, side);
    }

    boolean isAnimating() { return scheduled; }
    int frames() { return frames; }
    Rectangle logoBounds() { return new Rectangle(0, 0, SIZE, SIZE); }
}
