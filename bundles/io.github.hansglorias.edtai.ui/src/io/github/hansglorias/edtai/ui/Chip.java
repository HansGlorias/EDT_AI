package io.github.hansglorias.edtai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;

/**
 * Небольшая кнопка-надпись у поля ввода: текст мельче обычного, у меню —
 * треугольник, подсветка под указателем. Ниже и уже, чем {@code ToolItem}:
 * ряд под полем ввода не спорит с ним за внимание. Щелчок — {@code SWT.Selection}.
 */
final class Chip extends Canvas {

    private static final int PAD_X = 6;
    private static final int PAD_Y = 2;
    private static final int ARROW = 7;

    private final boolean arrow;
    private final Font font;
    private String text = "";
    private RGB color;
    private boolean hover;

    Chip(Composite parent, boolean arrow) {
        super(parent, SWT.DOUBLE_BUFFERED);
        this.arrow = arrow;
        FontData[] data = parent.getFont().getFontData();
        for (FontData each : data) {
            each.setHeight(Math.max(7, each.getHeight() - 1));
        }
        font = new Font(getDisplay(), data);
        setCursor(getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        addListener(SWT.Paint, this::paint);
        addListener(SWT.MouseEnter, event -> setHover(true));
        addListener(SWT.MouseExit, event -> setHover(false));
        addListener(SWT.MouseUp, event -> {
            Point size = getSize();
            if (event.button == 1 && event.x >= 0 && event.y >= 0 && event.x < size.x && event.y < size.y) {
                notifyListeners(SWT.Selection, new Event());
            }
        });
        addListener(SWT.Dispose, event -> font.dispose());
    }

    String getText() {
        return text;
    }

    void setText(String value) {
        if (!value.equals(text)) {
            text = value;
            redraw();
        }
    }

    /** Цвет надписи; {@code null} — обычный. */
    void setColor(RGB value) {
        color = value;
        redraw();
    }

    private void setHover(boolean value) {
        hover = value;
        redraw();
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        GC gc = new GC(this);
        try {
            gc.setFont(font);
            Point extent = gc.textExtent(text.isEmpty() ? " " : text);
            return new Point(extent.x + 2 * PAD_X + (arrow ? ARROW + 3 : 0), extent.y + 2 * PAD_Y);
        } finally {
            gc.dispose();
        }
    }

    private void paint(Event event) {
        GC gc = event.gc;
        Point size = getSize();
        gc.setAntialias(SWT.ON);
        RGB back = getBackground().getRGB();
        RGB fore = getForeground().getRGB();
        if (hover) {
            gc.setBackground(new Color(mix(back, fore, 0.1)));
            gc.fillRoundRectangle(0, 0, size.x, size.y, 6, 6);
        } else {
            gc.setBackground(getBackground());
        }
        RGB textColor = color != null ? color : mix(back, fore, 0.75);
        gc.setForeground(new Color(textColor));
        gc.setFont(font);
        Point extent = gc.textExtent(text.isEmpty() ? " " : text);
        int y = (size.y - extent.y) / 2;
        gc.drawText(text, PAD_X, y, true);
        if (arrow) {
            int x = PAD_X + extent.x + 3;
            int mid = size.y / 2;
            gc.setBackground(new Color(textColor));
            gc.fillPolygon(new int[] { x, mid - 1, x + ARROW - 1, mid - 1, x + (ARROW - 1) / 2, mid + 2 });
        }
    }

    private static RGB mix(RGB from, RGB to, double share) {
        return new RGB((int) Math.round(from.red + (to.red - from.red) * share),
                (int) Math.round(from.green + (to.green - from.green) * share),
                (int) Math.round(from.blue + (to.blue - from.blue) * share));
    }
}
