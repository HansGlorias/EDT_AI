package io.github.hansglorias.edtai.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Scrollable;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/** Обёртка стандартных полей и списков: один стиль прокрутки во всех окнах плагина. */
final class ScrollFrame extends Composite {
    private static final String KEY = ScrollFrame.class.getName();
    private final Scrollable content;
    private final Control layoutChild;
    private final int minimumWidth;
    private final ThinScrollBar vertical;
    private final ThinScrollBar horizontal;
    private int x;
    private boolean syncing;
    private boolean queued;
    private int textHeight;
    private int measuredWidth = -1;
    private int measuredNativeWidth = -1;

    /** Вызывать после задания layoutData; оно остаётся общим с обёрткой. */
    static ScrollFrame wrap(Scrollable control) {
        if (control.getData(KEY) instanceof ScrollFrame scroll) return scroll;
        return new ScrollFrame(control, control, 0);
    }

    /** Контейнер TableColumnLayout остаётся целиком штатным, включая расчёт колонок. */
    static ScrollFrame columns(Table table, int minimumWidth) {
        return new ScrollFrame(table, table.getParent(), minimumWidth);
    }

    static Control layoutControl(Control control) {
        return control.getData(KEY) instanceof ScrollFrame scroll ? scroll : control;
    }

    /** Таблицы стандартных диалогов JFace, которыми владеет наш плагин. */
    static void installTables(Composite parent) {
        for (Control child : parent.getChildren()) {
            if (child instanceof ScrollFrame) continue;
            if (child instanceof Table table) wrap(table);
            else if (child instanceof Composite composite) installTables(composite);
        }
    }

    private ScrollFrame(Scrollable control, Control layoutChild, int minimumWidth) {
        super(layoutChild.getParent(), SWT.NONE);
        content = control;
        this.layoutChild = layoutChild;
        this.minimumWidth = minimumWidth;
        moveAbove(layoutChild);
        setLayoutData(layoutChild.getLayoutData());
        layoutChild.setParent(this);
        control.setData(KEY, this);
        setBackground(control.getBackground());
        setForeground(control.getForeground());
        vertical = new ThinScrollBar(this, false, new ThinScrollBar.Model() {
            public int total() { return totalHeight(); }
            public int page() { return pageHeight(); }
            public int position() { return top(); }
            public void scroll(int value) { scrollTop(value); }
            public int increment() { return lineHeight(); }
        });
        horizontal = new ThinScrollBar(this, true, new ThinScrollBar.Model() {
            public int total() { return contentWidth(); }
            public int page() { return getClientArea().width - ThinScrollBar.WIDTH; }
            public int position() { return x; }
            public void scroll(int value) {
                x = Math.max(0, Math.min(value, total() - page()));
                layout();
            }
            public int increment() { return 24; }
        });
        setLayout(new Layout() {
            protected Point computeSize(Composite parent, int w, int h, boolean changed) {
                Point size = layoutChild.computeSize(w, h, changed);
                return new Point(w == SWT.DEFAULT ? size.x + ThinScrollBar.WIDTH : w, size.y);
            }
            protected void layout(Composite parent, boolean changed) { arrange(); }
        });
        // Из Paint только читаем состояние. Ни layout, ни setTopIndex, ни скрытия native-полос здесь нет.
        control.addListener(SWT.Paint, event -> sync());
        control.addListener(SWT.Resize, event -> queueArrange());
        control.addListener(SWT.KeyUp, event -> sync());
        control.addListener(SWT.MouseUp, event -> sync());
        if (control instanceof Text) control.addListener(SWT.Modify, event -> queueArrange());
        control.addListener(SWT.MouseVerticalWheel, event -> {
            if (event.count == 0 || !event.doit) return;
            scrollTop(top() - event.count * lineHeight());
            event.doit = false;
        });
        org.eclipse.swt.widgets.Listener across = event -> {
            if (event.count == 0 || !event.doit || contentWidth() <= getClientArea().width - ThinScrollBar.WIDTH) return;
            x = Math.max(0, Math.min(contentWidth() - getClientArea().width + ThinScrollBar.WIDTH, x - event.count * 24));
            layout();
            event.doit = false;
        };
        control.addListener(SWT.MouseHorizontalWheel, across);
        vertical.control().addListener(SWT.MouseHorizontalWheel, across);
        if (control instanceof Tree) {
            control.addListener(SWT.Expand, event -> getDisplay().asyncExec(() -> { if (!isDisposed()) sync(); }));
            control.addListener(SWT.Collapse, event -> getDisplay().asyncExec(() -> { if (!isDisposed()) sync(); }));
        }
        addListener(SWT.Resize, event -> arrange());
    }

    private void queueArrange() {
        if (queued) return;
        queued = true;
        getDisplay().asyncExec(() -> {
            queued = false;
            if (!isDisposed() && !content.isDisposed()) arrange();
        });
    }

    private void arrange() {
        if (content.isDisposed()) return;
        // Text сохраняет штатный механизм прокрутки; меняем видимость только вне Paint.
        if (content instanceof Text) {
            if (content.getVerticalBar() != null && content.getVerticalBar().getVisible()) content.getVerticalBar().setVisible(false);
            if (content.getHorizontalBar() != null && content.getHorizontalBar().getVisible()) content.getHorizontalBar().setVisible(false);
        }
        Rectangle area = getClientArea();
        int width = Math.max(0, area.width - ThinScrollBar.WIDTH);
        boolean wide = contentWidth() > width;
        int height = Math.max(0, area.height - (wide ? ThinScrollBar.WIDTH : 0));
        x = Math.max(0, Math.min(x, contentWidth() - width));
        // Win32 Tree с NO_SCROLL не выполняет setTopItem. Его штатная полоса
        // остаётся рабочей, но находится за границей обёртки и закрыта нашим Canvas.
        int nativeWidth = nativeWidth();
        Rectangle bounds = new Rectangle(-x, 0, Math.max(width, contentWidth()) + nativeWidth, height);
        if (!layoutChild.getBounds().equals(bounds)) layoutChild.setBounds(bounds);
        if (layoutChild instanceof Composite composite && layoutChild != content) composite.layout();
        if (content instanceof Text text) {
            int clientWidth = Math.max(1, text.getClientArea().width);
            textHeight = Math.max(text.getLineCount() * text.getLineHeight(),
                    text.computeSize(clientWidth, SWT.DEFAULT).y - text.computeTrim(0, 0, 0, 0).height);
        }
        vertical.control().setBounds(area.width - ThinScrollBar.WIDTH, 0, ThinScrollBar.WIDTH, height);
        horizontal.control().setBounds(0, height, width, ThinScrollBar.WIDTH);
        sync();
    }

    private int contentWidth() {
        if (layoutChild != content) return minimumWidth;
        if (content instanceof Table table) return table.getColumnCount() == 0 ? table.computeSize(SWT.DEFAULT, SWT.DEFAULT).x
                : 2 * table.getBorderWidth() + java.util.Arrays.stream(table.getColumns()).mapToInt(c -> c.getWidth()).sum();
        if (content instanceof Tree tree) return 2 * tree.getBorderWidth() + java.util.Arrays.stream(tree.getColumns()).mapToInt(c -> c.getWidth()).sum();
        return 0;
    }

    void sync() {
        if (isDisposed() || syncing) return;
        syncing = true;
        try {
            int width = contentWidth();
            int nativeWidth = nativeWidth();
            if (measuredWidth != width || measuredNativeWidth != nativeWidth) {
                measuredWidth = width;
                measuredNativeWidth = nativeWidth;
                queueArrange();
            }
            vertical.update();
            horizontal.update();
        } finally { syncing = false; }
    }

    /** GTK может рисовать overlay-полосу: её размер не равен занятой части viewport. */
    private int nativeWidth() {
        if (content instanceof Text || content.getVerticalBar() == null) return 0;
        return Math.max(0, content.getSize().x - content.getClientArea().width - 2 * content.getBorderWidth());
    }

    private int lineHeight() {
        if (content instanceof Text text) return text.getLineHeight();
        if (content instanceof Table table) return table.getItemHeight();
        return ((Tree) content).getItemHeight();
    }

    private int pageHeight() {
        int height = content.getClientArea().height;
        if (content instanceof Table table) height -= table.getHeaderHeight();
        if (content instanceof Tree tree) height -= tree.getHeaderHeight();
        return Math.max(0, height);
    }

    private int totalHeight() {
        if (content instanceof Text) return textHeight;
        if (content instanceof Table table) return table.getItemCount() * table.getItemHeight();
        return visibleItems((Tree) content).size() * lineHeight();
    }

    private int top() {
        if (content instanceof Text text) {
            var bar = text.getVerticalBar();
            if ("gtk".equals(SWT.getPlatform()) && bar != null) {
                // У GTK getTopPixel даёт начало логической строки, а не её перенесённой части.
                int range = Math.max(1, bar.getMaximum() - bar.getThumb());
                return (int) ((long) bar.getSelection() * Math.max(0, totalHeight() - pageHeight()) / range);
            }
            return text.getTopPixel();
        }
        if (content instanceof Table table) return table.getTopIndex() * table.getItemHeight();
        Tree tree = (Tree) content;
        return Math.max(0, visibleItems(tree).indexOf(tree.getTopItem())) * tree.getItemHeight();
    }

    private void scrollTop(int value) {
        int position = Math.max(0, Math.min(value, totalHeight() - pageHeight()));
        int line = position / Math.max(1, lineHeight());
        if (content instanceof Text text) {
            // На GTK getTopIndex относится к логическим строкам: внутри длинной
            // перенесённой строки прокручиваем пиксельную adjustment штатной полосы.
            var bar = text.getVerticalBar();
            if ("gtk".equals(SWT.getPlatform()) && bar != null) {
                int range = Math.max(0, bar.getMaximum() - bar.getThumb());
                bar.setSelection((int) ((long) position * range / Math.max(1, totalHeight() - pageHeight())));
            } else {
                text.setTopIndex(line);
            }
        }
        else if (content instanceof Table table) table.setTopIndex(line);
        else {
            List<TreeItem> items = visibleItems((Tree) content);
            if (!items.isEmpty()) ((Tree) content).setTopItem(items.get(Math.min(line, items.size() - 1)));
        }
        sync();
    }

    private static List<TreeItem> visibleItems(Tree tree) {
        List<TreeItem> result = new ArrayList<>();
        collect(tree.getItems(), result);
        return result;
    }

    private static void collect(TreeItem[] items, List<TreeItem> result) {
        for (TreeItem item : items) {
            result.add(item);
            if (item.getExpanded()) collect(item.getItems(), result);
        }
    }

    ThinScrollBar vertical() { return vertical; }
    ThinScrollBar horizontal() { return horizontal; }

    boolean canScroll(int count) {
        return count > 0 ? top() > 0 : top() < totalHeight() - pageHeight();
    }
}
