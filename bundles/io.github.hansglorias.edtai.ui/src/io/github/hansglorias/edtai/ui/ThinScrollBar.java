package io.github.hansglorias.edtai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Composite;

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
    interface Model {
        int total();
        int page();
        int position();
        void scroll(int position);
        int increment();
    }
    private final Model model;
    private final boolean horizontal;
    private Runnable changed = () -> { };
    private final Canvas bar;
    private Color thumbColor;
    private Color hoverColor;
    private RGB colorsFrom;
    private RGB colorsTo;
    private boolean hover;
    /** Где внутри ползунка взяли мышью; -1 — не тянем. */
    private int grab = -1;
    private Rectangle thumb = new Rectangle(0, 0, 0, 0);

    ThinScrollBar(StyledText text) {
        this(text, false, new Model() {
            public int total() { return text.getTopPixel() + text.getLinePixel(text.getLineCount()) + text.getBottomMargin(); }
            public int page() { return text.getClientArea().height; }
            public int position() { return text.getTopPixel(); }
            public void scroll(int position) { text.setTopPixel(position); }
            public int increment() { return text.getLineHeight(); }
        });
        text.addListener(SWT.MouseVerticalWheel, this::wheel);
        text.addListener(SWT.Resize, event -> update());
        text.addListener(SWT.Paint, event -> update());
    }

    ThinScrollBar(Composite parent, boolean horizontal, Model model) {
        this.text = parent instanceof StyledText styled ? styled : null;
        this.model = model;
        this.horizontal = horizontal;
        bar = new Canvas(parent, SWT.NO_FOCUS | SWT.DOUBLE_BUFFERED);
        bar.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_ARROW));
        bar.setVisible(false);
        bar.addListener(SWT.Paint, this::paint);
        bar.addListener(SWT.MouseDown, this::mouseDown);
        bar.addListener(SWT.MouseMove, this::mouseMove);
        bar.addListener(SWT.MouseUp, event -> {
            grab = -1;
            bar.setCapture(false);
            bar.redraw();
        });
        bar.addListener(SWT.MouseEnter, event -> setHover(true));
        bar.addListener(SWT.MouseExit, event -> setHover(false));
        bar.addListener(SWT.MouseVerticalWheel, this::wheel);
        bar.addListener(SWT.Dispose, event -> disposeColors());
    }

    void onChange(Runnable listener) { changed = listener; }

    /** Пересчитывает ползунок: высота ленты или прокрутка изменились. */
    void update() {
        if (bar.isDisposed()) {
            return;
        }
        Rectangle area = text != null ? text.getClientArea() : bar.getBounds();
        int total = model.total();
        // Скрытая лента (пока чат пуст, видна заглушка) ещё не разложена: её размер ничего не значит.
        boolean needed = (text != null ? text.isVisible() && text.getCharCount() > 0 : bar.getParent().getVisible())
                && total > model.page() && model.page() > 0;
        if (text != null) {
            Rectangle bounds = new Rectangle(area.x + area.width - WIDTH, area.y, WIDTH, area.height);
            if (!bar.getBounds().equals(bounds)) bar.setBounds(bounds);
        }
        Rectangle next = needed ? thumb(horizontal ? area.width : area.height, total) : new Rectangle(0, 0, 0, 0);
        if (bar.getVisible() != needed) {
            bar.setVisible(needed);
        }
        if (!next.equals(thumb)) {
            thumb = next;
            bar.redraw();
        }
        if (!bar.getParent().getBackground().getRGB().equals(colorsFrom)
                || !bar.getParent().getForeground().getRGB().equals(colorsTo)) bar.redraw();
        changed.run();
    }

    /** Высота всей ленты в пикселях. */
    private Rectangle thumb(int height, int total) {
        int size = Math.min(height, Math.max(MIN_THUMB, (int) ((long) height * model.page() / Math.max(1, total))));
        int range = Math.max(1, total - model.page());
        int y = (int) ((long) Math.min(model.position(), range) * (height - size) / range);
        return horizontal ? new Rectangle(y, (WIDTH - THUMB) / 2, size, THUMB)
                : new Rectangle((WIDTH - THUMB) / 2, y, THUMB, size);
    }

    private void paint(Event event) {
        GC gc = event.gc;
        gc.setBackground(bar.getParent().getBackground());
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
        int at = horizontal ? event.x : event.y;
        int start = horizontal ? thumb.x : thumb.y;
        int length = horizontal ? thumb.width : thumb.height;
        bar.setCapture(true);
        if (at >= start && at < start + length) {
            grab = at - start;
        } else {
            // Щелчок мимо ползунка: середина ползунка встаёт туда, где щёлкнули, дальше можно тянуть.
            grab = length / 2;
            scrollTo(at);
        }
        bar.redraw();
    }

    private void mouseMove(Event event) {
        if (grab >= 0) {
            scrollTo(horizontal ? event.x : event.y);
        }
    }

    private void scrollTo(int y) {
        int height = horizontal ? bar.getSize().x : bar.getSize().y;
        int range = model.total() - model.page();
        int track = height - (horizontal ? thumb.width : thumb.height);
        if (range <= 0 || track <= 0) {
            return;
        }
        int top = (int) ((long) Math.max(0, Math.min(track, y - grab)) * range / track);
        model.scroll(top);
        update();
    }

    private void wheel(Event event) {
        if (event.count == 0) {
            return;
        }
        int lines = Math.abs(event.count) >= WHEEL_LINES ? Math.abs(event.count) : WHEEL_LINES;
        model.scroll(model.position() - Integer.signum(event.count) * lines * model.increment());
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
        RGB background = bar.getParent().getBackground().getRGB();
        RGB foreground = bar.getParent().getForeground().getRGB();
        if (thumbColor != null && background.equals(colorsFrom) && foreground.equals(colorsTo)) {
            return;
        }
        disposeColors();
        thumbColor = new Color(mix(background, foreground, 0.30));
        hoverColor = new Color(mix(background, foreground, 0.50));
        colorsFrom = background;
        colorsTo = foreground;
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
