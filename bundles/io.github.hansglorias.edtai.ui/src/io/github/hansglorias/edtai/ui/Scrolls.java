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
final class Scrolls extends Composite {
    private static final String KEY = Scrolls.class.getName();
    private final Scrollable content;
    private final ThinScrollBar vertical;
    private final ThinScrollBar horizontal;
    private int x;
    private boolean syncing;
    private boolean fitWidth;
    private java.util.function.IntConsumer columns;
    private boolean syncQueued;

    /** Вызывать после задания layoutData; оно остаётся общим с обёрткой. */
    static Scrolls wrap(Scrollable control) {
        if (control.getData(KEY) instanceof Scrolls scroll) return scroll;
        return new Scrolls(control);
    }

    static Control layoutControl(Control control) {
        return control.getData(KEY) instanceof Scrolls scroll ? scroll : control;
    }

    /** Таблицы стандартных диалогов JFace, которыми владеет наш плагин. */
    static void installTables(Composite parent) {
        for (Control child : parent.getChildren()) {
            if (child instanceof Scrolls) continue;
            if (child instanceof Table table) wrap(table);
            else if (child instanceof Composite composite) installTables(composite);
        }
    }

    private Scrolls(Scrollable control) {
        super(control.getParent(), SWT.NONE);
        content = control;
        moveAbove(control);
        setLayoutData(control.getLayoutData());
        control.setParent(this);
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
            public int total() { return fitWidth ? Math.max(0, page()) : contentWidth(); }
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
                Point size = content.computeSize(w, h, changed);
                return new Point(size.x + ThinScrollBar.WIDTH, size.y);
            }
            protected void layout(Composite parent, boolean changed) { arrange(); }
        });
        control.addListener(SWT.Paint, event -> { sync(); syncAfterLayout(); });
        control.addListener(SWT.Resize, event -> { sync(); syncAfterLayout(); });
        control.addListener(SWT.Show, event -> syncAfterLayout());
        control.addListener(SWT.KeyUp, event -> sync());
        control.addListener(SWT.MouseUp, event -> sync());
        if (control instanceof Text) control.addListener(SWT.Modify, event -> sync());
        control.addListener(SWT.MouseVerticalWheel, event -> {
            if (event.count == 0 || !event.doit) return;
            scrollTop(top() - event.count * lineHeight());
            event.doit = false;
        });
        if (control instanceof Tree) {
            control.addListener(SWT.Expand, event -> getDisplay().asyncExec(() -> { if (!isDisposed()) sync(); }));
            control.addListener(SWT.Collapse, event -> getDisplay().asyncExec(() -> { if (!isDisposed()) sync(); }));
        }
        addListener(SWT.Resize, event -> arrange());
    }

    private void arrange() {
        Rectangle area = getClientArea();
        int width = Math.max(0, area.width - ThinScrollBar.WIDTH);
        boolean wide = !fitWidth && contentWidth() > width;
        int height = Math.max(0, area.height - (wide ? ThinScrollBar.WIDTH : 0));
        x = fitWidth ? 0 : Math.max(0, Math.min(x, contentWidth() - width));
        // Колонки — до setBounds: иначе GTK раскладывает таблицу по прежней
        // сумме колонок, и последняя колонка уходит за видимый край.
        if (fitWidth) columns.accept(width);
        // Win32 Tree с NO_SCROLL не выполняет setTopItem. Его штатная полоса
        // остаётся рабочей, но находится за границей обёртки и закрыта нашим Canvas.
        int nativeWidth = content instanceof Tree && content.getVerticalBar() != null ? content.getVerticalBar().getSize().x : 0;
        content.setBounds(-x, 0, (fitWidth ? width : Math.max(width, contentWidth())) + nativeWidth, height);
        vertical.control().setBounds(area.width - ThinScrollBar.WIDTH, 0, ThinScrollBar.WIDTH, height);
        horizontal.control().setBounds(0, height, width, ThinScrollBar.WIDTH);
        vertical.control().moveAbove(null);
        horizontal.control().moveAbove(null);
        sync();
    }

    /** Таблица занимает ровно видимую ширину; columns распределяет её между колонками. */
    void fitWidth(java.util.function.IntConsumer columns) {
        this.columns = columns;
        fitWidth = true;
        layout();
    }

    private void syncAfterLayout() {
        if (syncQueued) return;
        syncQueued = true;
        getDisplay().asyncExec(() -> {
            syncQueued = false;
            // GTK измеряет строки таблицы во время отрисовки: берём окончательную высоту после неё.
            if (!isDisposed()) sync();
        });
    }

    private int contentWidth() {
        if (content instanceof Table table) return java.util.Arrays.stream(table.getColumns()).mapToInt(c -> c.getWidth()).sum();
        if (content instanceof Tree tree) return java.util.Arrays.stream(tree.getColumns()).mapToInt(c -> c.getWidth()).sum();
        return 0;
    }

    void sync() {
        if (isDisposed() || syncing) return;
        syncing = true;
        try {
            // У Text полоса нужна внутренней реализации прокрутки, но рисует её общий Canvas.
            if (!(content instanceof Tree)) {
                if (content.getVerticalBar() != null && content.getVerticalBar().getVisible()) content.getVerticalBar().setVisible(false);
                if (content.getHorizontalBar() != null && content.getHorizontalBar().getVisible()) content.getHorizontalBar().setVisible(false);
            }
            if (content instanceof Table table) {
                // GTK после замены строк на более короткий список оставляет прежний
                // верхний индекс: таблица выглядит пустой. Win32 ограничивает сам.
                int max = Math.max(0, totalHeight() - pageHeight());
                if (top() > max) table.setTopIndex(max / Math.max(1, lineHeight()));
            }
            if (!getBackground().equals(content.getBackground())) setBackground(content.getBackground());
            if (!getForeground().equals(content.getForeground())) setForeground(content.getForeground());
            vertical.update();
            horizontal.update();
        } finally { syncing = false; }
    }

    private int lineHeight() {
        if (content instanceof Text text) return text.getLineHeight();
        if (content instanceof Table table) return table.getItemHeight();
        return ((Tree) content).getItemHeight();
    }

    private int pageHeight() {
        // GTK Table/Tree с NO_SCROLL после изменения размера может вернуть
        // клиентскую область на всю высоту строк: видимая часть не больше виджета.
        int height = Math.min(content.getClientArea().height, content.getSize().y);
        if (content instanceof Table table) height -= table.getHeaderHeight();
        if (content instanceof Tree tree) height -= tree.getHeaderHeight();
        return Math.max(0, height);
    }

    private int totalHeight() {
        if (content instanceof Text text) {
            int width = Math.max(1, text.getClientArea().width);
            return Math.max(text.getLineCount() * text.getLineHeight(),
                    text.computeSize(width, SWT.DEFAULT).y - text.computeTrim(0, 0, 0, 0).height);
        }
        if (content instanceof Table table) return table.getItemCount() * table.getItemHeight();
        return visibleItems((Tree) content).size() * lineHeight();
    }

    private int top() {
        if (content instanceof Text text) {
            var bar = text.getVerticalBar();
            if ("gtk".equals(SWT.getPlatform()) && bar != null) {
                // GTK Text.getTopPixel возвращает начало логической строки,
                // даже когда её перенесённые части уже ушли выше окна.
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
