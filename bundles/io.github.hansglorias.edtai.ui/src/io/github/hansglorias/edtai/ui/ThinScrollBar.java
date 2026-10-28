package io.github.hansglorias.edtai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Event;

/**
 * Узкая полоса прокрутки поверх правого края ленты вместо системной: на
 * Windows та шириной 17 px и не меняется. Ползунок тянется мышью, щелчок
 * мимо него — прыжок к этому месту, колесо прокручивает ленту. Полосы нет,
 * когда всё помещается.
 */
final class ThinScrollBar {

    static final int WIDTH = 8;
    private static final int THUMB = 5;
    private static final int MIN_THUMB = 24;
    /** Строк на одно деление колеса, если система не сказала. */
    private static final int WHEEL_LINES = 3;

    private final StyledText text;
    private final Canvas bar;
    private Color thumbColor;
    private Color hoverColor;
    private RGB colorsFrom;
    private boolean hover;
    /** Где внутри ползунка взяли мышью; -1 — не тянем. */
    private int grab = -1;
    private Rectangle thumb = new Rectangle(0, 0, 0, 0);

    ThinScrollBar(StyledText text) {
        this.text = text;
        bar = new Canvas(text, SWT.NO_FOCUS | SWT.DOUBLE_BUFFERED);
        bar.setVisible(false);
        bar.addListener(SWT.Paint, this::paint);
        bar.addListener(SWT.MouseDown, this::mouseDown);
        bar.addListener(SWT.MouseMove, this::mouseMove);
        bar.addListener(SWT.MouseUp, event -> grab = -1);
        bar.addListener(SWT.MouseEnter, event -> setHover(true));
        bar.addListener(SWT.MouseExit, event -> setHover(false));
        bar.addListener(SWT.MouseVerticalWheel, this::wheel);
        bar.addListener(SWT.Dispose, event -> disposeColors());
        text.addListener(SWT.MouseVerticalWheel, this::wheel);
        text.addListener(SWT.Resize, event -> update());
        // Лента перерисовывается при прокрутке и при каждом новом тексте — тогда и пересчитываем ползунок.
        text.addListener(SWT.Paint, event -> update());
    }

    /** Пересчитывает ползунок: высота ленты или прокрутка изменились. */
    void update() {
        if (bar.isDisposed()) {
            return;
        }
        Rectangle area = text.getClientArea();
        int total = total();
        boolean needed = total > area.height && area.height > 0;
        Rectangle bounds = new Rectangle(area.x + area.width - WIDTH, area.y, WIDTH, area.height);
        if (!bar.getBounds().equals(bounds)) {
            bar.setBounds(bounds);
        }
        Rectangle next = needed ? thumb(area.height, total) : new Rectangle(0, 0, 0, 0);
        if (bar.getVisible() != needed) {
            bar.setVisible(needed);
        }
        if (!next.equals(thumb)) {
            thumb = next;
            bar.redraw();
        }
    }

    /** Высота всей ленты в пикселях. */
    private int total() {
        return text.getTopPixel() + text.getLinePixel(text.getLineCount()) + text.getBottomMargin();
    }

    private Rectangle thumb(int height, int total) {
        int size = Math.max(MIN_THUMB, (int) ((long) height * height / total));
        int range = Math.max(1, total - height);
        int y = (int) ((long) Math.min(text.getTopPixel(), range) * (height - size) / range);
        return new Rectangle((WIDTH - THUMB) / 2, y, THUMB, size);
    }

    private void paint(Event event) {
        GC gc = event.gc;
        gc.setBackground(text.getBackground());
        gc.fillRectangle(bar.getClientArea());
        if (thumb.height > 0) {
            gc.setAntialias(SWT.ON);
            gc.setBackground(hover || grab >= 0 ? hoverColor() : thumbColor());
            gc.fillRoundRectangle(thumb.x, thumb.y, thumb.width, thumb.height, THUMB, THUMB);
        }
    }

    private void mouseDown(Event event) {
        if (event.button != 1 || thumb.height == 0) {
            return;
        }
        if (event.y >= thumb.y && event.y < thumb.y + thumb.height) {
            grab = event.y - thumb.y;
        } else {
            // Щелчок мимо ползунка: середина ползунка встаёт туда, где щёлкнули, дальше можно тянуть.
            grab = thumb.height / 2;
            scrollTo(event.y);
        }
        bar.redraw();
    }

    private void mouseMove(Event event) {
        if (grab >= 0) {
            scrollTo(event.y);
        }
    }

    private void scrollTo(int y) {
        int height = text.getClientArea().height;
        int range = total() - height;
        int track = height - thumb.height;
        if (range <= 0 || track <= 0) {
            return;
        }
        int top = (int) ((long) Math.max(0, Math.min(track, y - grab)) * range / track);
        text.setTopPixel(top);
        update();
    }

    private void wheel(Event event) {
        if (event.count == 0) {
            return;
        }
        int lines = Math.abs(event.count) >= WHEEL_LINES ? Math.abs(event.count) : WHEEL_LINES;
        text.setTopPixel(text.getTopPixel() - Integer.signum(event.count) * lines * text.getLineHeight());
        event.doit = false;
        update();
    }

    private void setHover(boolean value) {
        if (hover != value) {
            hover = value;
            bar.redraw();
        }
    }

    private Color thumbColor() {
        colors();
        return thumbColor;
    }

    private Color hoverColor() {
        colors();
        return hoverColor;
    }

    /** Ползунок — смесь фона и текста ленты: виден и в светлой, и в тёмной теме. */
    private void colors() {
        RGB background = text.getBackground().getRGB();
        if (thumbColor != null && background.equals(colorsFrom)) {
            return;
        }
        disposeColors();
        RGB foreground = text.getForeground().getRGB();
        thumbColor = new Color(mix(background, foreground, 0.30));
        hoverColor = new Color(mix(background, foreground, 0.50));
        colorsFrom = background;
    }

    private void disposeColors() {
        thumbColor = null;
        hoverColor = null;
    }

    private static RGB mix(RGB from, RGB to, double share) {
        return new RGB((int) Math.round(from.red + (to.red - from.red) * share),
                (int) Math.round(from.green + (to.green - from.green) * share),
                (int) Math.round(from.blue + (to.blue - from.blue) * share));
    }

    // ---- для тестов ----

    boolean isShown() {
        return bar.getVisible();
    }

    Rectangle thumbBounds() {
        return thumb;
    }

    Canvas control() {
        return bar;
    }
}
