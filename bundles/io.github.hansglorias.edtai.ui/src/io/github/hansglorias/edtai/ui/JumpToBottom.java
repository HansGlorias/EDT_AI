package io.github.hansglorias.edtai.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Event;

/** Плавающая кнопка над нижним краем ленты, видна только при чтении выше конца. */
final class JumpToBottom extends Canvas {
    static final int SIZE = 30;
    private final StyledText text;
    private boolean hover;

    JumpToBottom(StyledText text) {
        super(text, SWT.DOUBLE_BUFFERED);
        this.text = text;
        setVisible(false);
        setCursor(getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        setToolTipText("К последнему сообщению");
        getAccessible().addAccessibleListener(new AccessibleAdapter() {
            @Override
            public void getName(AccessibleEvent event) { event.result = getToolTipText(); }
        });
        addListener(SWT.Paint, this::paint);
        addListener(SWT.MouseEnter, event -> { hover = true; redraw(); });
        addListener(SWT.MouseExit, event -> { hover = false; redraw(); });
        addListener(SWT.MouseUp, event -> {
            if (event.button == 1 && getClientArea().contains(event.x, event.y)) jump();
        });
        addListener(SWT.KeyDown, event -> {
            if (event.keyCode == SWT.CR || event.character == ' ') { jump(); event.doit = false; }
        });
    }

    void sync() {
        var area = text.getClientArea();
        var bounds = new org.eclipse.swt.graphics.Rectangle(Math.max(0, (area.width - SIZE) / 2),
                Math.max(0, area.height - SIZE - 12), SIZE, SIZE);
        if (!bounds.equals(getBounds())) setBounds(bounds);
        boolean shown = text.isVisible() && text.getCharCount() > 0 && area.height > SIZE + 24
                && text.getLinePixel(text.getLineCount()) + text.getBottomMargin() > area.height + text.getLineHeight();
        if (getVisible() != shown) {
            setVisible(shown);
            if (shown) moveAbove(null);
        }
    }

    void jump() {
        text.setTopPixel(Integer.MAX_VALUE);
        text.setFocus();
        sync();
    }

    private void paint(Event event) {
        var gc = event.gc;
        gc.setAntialias(SWT.ON);
        gc.setBackground(text.getBackground());
        gc.fillRectangle(getClientArea());
        RGB bg = text.getBackground().getRGB();
        RGB fg = text.getForeground().getRGB();
        gc.setBackground(new Color(mix(bg, fg, hover ? 0.16 : 0.08)));
        gc.fillOval(1, 1, SIZE - 3, SIZE - 3);
        gc.setForeground(new Color(mix(bg, fg, 0.24)));
        gc.drawOval(1, 1, SIZE - 3, SIZE - 3);
        gc.setForeground(text.getForeground());
        gc.setLineWidth(2);
        gc.setLineCap(SWT.CAP_ROUND);
        gc.setLineJoin(SWT.JOIN_ROUND);
        gc.drawLine(14, 8, 14, 20);
        gc.drawPolyline(new int[] { 9, 15, 14, 20, 19, 15 });
    }

    private static RGB mix(RGB a, RGB b, double share) {
        return new RGB((int) (a.red + (b.red - a.red) * share), (int) (a.green + (b.green - a.green) * share),
                (int) (a.blue + (b.blue - a.blue) * share));
    }
}
