package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.junit.jupiter.api.Test;

class ScrollFrameTest extends AbstractChatTest {
    @Test void wheelOverNestedFieldDoesNotAlsoScrollOuterForm() throws Exception {
        Shell shell = new Shell(page.getWorkbenchWindow().getShell(), SWT.SHELL_TRIM);
        try {
            shell.setLayout(new org.eclipse.swt.layout.FillLayout());
            ScrollArea outer = new ScrollArea(shell, SWT.V_SCROLL);
            outer.setExpandHorizontal(true);
            outer.setExpandVertical(true);
            Composite content = new Composite(outer, SWT.NONE);
            Text text = new Text(content, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
            text.setText("строка\n".repeat(100));
            ScrollFrame frame = ScrollFrame.wrap(text);
            frame.setBounds(10, 10, 200, 100);
            outer.setContent(content);
            outer.setMinSize(320, 1000);
            shell.setSize(350, 250);
            shell.open();
            settle();
            wheel(text, -3);
            settle();
            assertTrue(text.getTopIndex() > 0);
            assertEquals(0, outer.getOrigin().y, "внутреннее поле забирает своё колесо");
            wheel(content, -3);
            assertTrue(outer.getOrigin().y > 0, "фон формы прокручивает внешний контейнер");
        } finally { shell.dispose(); }
    }
    @Test void hoverUsesArrowAndDragContinuesOutsideTrack() throws Exception {
        Shell shell = new Shell(page.getWorkbenchWindow().getShell(), SWT.SHELL_TRIM);
        try {
            shell.setLayout(new org.eclipse.swt.layout.FillLayout());
            Text text = new Text(shell, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
            text.setText("строка\n".repeat(100));
            ScrollFrame frame = ScrollFrame.wrap(text);
            shell.setSize(300, 200);
            shell.open();
            settle();
            ThinScrollBar bar = frame.vertical();
            bar.control().notifyListeners(SWT.MouseExit, new Event());
            var before = thumbPixel(bar);
            bar.control().notifyListeners(SWT.MouseEnter, new Event());
            assertNotEquals(before, thumbPixel(bar), "наведение подсвечивает ползунок");
            assertEquals(shell.getDisplay().getSystemCursor(SWT.CURSOR_ARROW), bar.control().getCursor());
            Event down = new Event(); down.button = 1; down.y = bar.thumbBounds().y + 3;
            bar.control().notifyListeners(SWT.MouseDown, down);
            bar.control().notifyListeners(SWT.MouseExit, new Event());
            Event move = new Event(); move.y = bar.control().getSize().y + 50;
            bar.control().notifyListeners(SWT.MouseMove, move);
            bar.control().notifyListeners(SWT.MouseUp, new Event());
            assertTrue(text.getTopIndex() > 80, "перетаскивание за границу дорожки доходит до конца");
            assertEquals(before, thumbPixel(bar), "после отпускания вне дорожки подсветка снята");
            assertEquals(shell.getDisplay().getSystemCursor(SWT.CURSOR_ARROW), bar.control().getCursor());
        } finally { shell.dispose(); }
    }

    private static org.eclipse.swt.graphics.RGB thumbPixel(ThinScrollBar bar) {
        var size = bar.control().getSize();
        var image = new org.eclipse.swt.graphics.Image(bar.control().getDisplay(), size.x, size.y);
        var gc = new org.eclipse.swt.graphics.GC(image);
        try {
            assertTrue(bar.control().print(gc));
            var data = image.getImageData();
            var thumb = bar.thumbBounds();
            return data.palette.getRGB(data.getPixel(thumb.x + thumb.width / 2, thumb.y + thumb.height / 2));
        } finally { gc.dispose(); image.dispose(); }
    }

    @Test void longComposerHasThinBarAndNativeBarStaysHidden() throws Exception {
        ChatView view = showView();
        view.showHistoryPanel(false);
        view.input().setText("длинный вопрос\n".repeat(60));
        settle();
        ScrollFrame frame = assertInstanceOf(ScrollFrame.class, view.input().getParent());
        assertTrue(frame.vertical().isShown());
        assertFalse(view.input().getVerticalBar().getVisible());
        assertEquals(view.input().getDisplay().getSystemCursor(SWT.CURSOR_ARROW), frame.vertical().control().getCursor());
        wheel(view.input(), -3);
        assertTrue(view.input().getTopIndex() > 0);
        Screenshots.render(view.control(), "thin-composer");
    }

    @Test void chatRowsKeepDateAndScrollbarInsideAcrossResizes() throws Exception {
        Shell shell = new Shell(page.getWorkbenchWindow().getShell(), SWT.SHELL_TRIM);
        try {
            shell.setLayout(new org.eclipse.swt.layout.FillLayout());
            ChatHistoryPanel panel = new ChatHistoryPanel(shell, new ChatHistoryPanel.Callbacks() {
                public void open(String id) { }
                public void rename(String id) { }
                public void export(String id) { }
                public void exportAnalysis(String id) { }
                public void delete(String id) { }
                public void searchChanged() { }
                public void newChat() { }
            });
            var entries = java.util.stream.IntStream.range(0, 80).mapToObj(i -> new io.github.hansglorias.edtai.core.chat.ChatStore.Entry(
                    "chat-" + i, "Обсуждение длинного названия " + i, java.time.Instant.now())).toList();
            panel.show(entries, "chat-0", java.util.Set.of("chat-1"), java.util.Set.of("chat-2"));
            shell.setSize(260, 330);
            shell.open();
            Table table = findTable(shell);
            ScrollFrame frame = assertInstanceOf(ScrollFrame.class, ScrollFrame.layoutControl(table));
            for (int width : new int[] { 360, 180, 280, 160, 320, 190 }) {
                shell.setSize(width, 330);
                shell.layout(true, true);
                settle();
                waitUntil(() -> frame.vertical().isShown(), () -> "ширина=" + width
                        + ", frame=" + frame.getClientArea() + ", table=" + table.getClientArea()
                        + ", items=" + table.getItemCount() + ", itemHeight=" + table.getItemHeight());
                assertFalse(frame.horizontal().isShown(), "колонки подстраиваются под ширину " + width);
                var bar = frame.vertical().control().getBounds();
                assertTrue(bar.x >= 0 && bar.x + bar.width <= frame.getClientArea().width);
                assertTrue(table.getColumn(0).getWidth() + table.getColumn(1).getWidth() <= table.getClientArea().width + 2,
                        "дата остаётся внутри таблицы при ширине " + width);
                wheel(table, -100);
            }
            table.setSelection(79);
            table.showSelection();
            settle();
            int[] paints = { 0 };
            table.addListener(SWT.Paint, event -> paints[0]++);
            settle();
            assertTrue(paints[0] < 10, "конец истории не вызывает цикл перерисовки: " + paints[0]);
            table.setTopIndex(0);
            table.setSelection(0);
            Screenshots.render(panel.control(), "chat-rows-compact");
            panel.show(entries.subList(0, 1), "chat-0", java.util.Set.of(), java.util.Set.of());
            settle();
            assertEquals(0, table.getTopIndex());
            assertFalse(frame.vertical().isShown(), "после сокращения списка полоса скрыта");
            assertTrue(table.getColumn(0).getWidth() + table.getColumn(1).getWidth()
                    <= frame.getClientArea().width - ThinScrollBar.WIDTH + 2,
                    "дата остаётся внутри viewport после сокращения списка");
        } finally { shell.dispose(); }
    }
    @Test void fieldsListsAndTreesUseThinBars() throws Exception {
        Shell shell = new Shell(page.getWorkbenchWindow().getShell(), SWT.SHELL_TRIM);
        try {
            GridLayoutFactory.fillDefaults().numColumns(3).applyTo(shell);
            Text text = new Text(shell, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
            text.setText("строка\n".repeat(100));
            GridDataFactory.fillDefaults().grab(true, true).hint(180, 160).applyTo(text);
            ScrollFrame field = ScrollFrame.wrap(text);
            Table table = new Table(shell, SWT.NO_SCROLL | SWT.FULL_SELECTION);
            GridDataFactory.fillDefaults().grab(true, true).hint(180, 160).applyTo(table);
            ScrollFrame list = ScrollFrame.wrap(table);
            new TableColumn(table, SWT.NONE).setWidth(500);
            for (int i = 0; i < 80; i++) new TableItem(table, SWT.NONE).setText("диалог " + i);
            Tree tree = new Tree(shell, SWT.V_SCROLL);
            GridDataFactory.fillDefaults().grab(true, true).hint(180, 160).applyTo(tree);
            ScrollFrame hierarchy = ScrollFrame.wrap(tree);
            new TreeColumn(tree, SWT.NONE).setWidth(500);
            TreeItem root = new TreeItem(tree, SWT.NONE);
            root.setText("сервер");
            for (int i = 0; i < 80; i++) new TreeItem(root, SWT.NONE).setText("инструмент " + i);
            root.setExpanded(true);
            shell.setSize(600, 240);
            shell.open();
            shell.layout(true, true);
            settle();
            for (ScrollFrame scroll : new ScrollFrame[] { field, list, hierarchy }) {
                assertTrue(scroll.vertical().isShown());
                assertEquals(ThinScrollBar.WIDTH, scroll.vertical().control().getSize().x);
                assertEquals(shell.getDisplay().getSystemCursor(SWT.CURSOR_ARROW), scroll.vertical().control().getCursor());
            }
            assertFalse(text.getVerticalBar().getVisible());
            assertNull(table.getVerticalBar());
            assertTrue(tree.getBounds().x + tree.getSize().x - tree.getVerticalBar().getSize().x >= hierarchy.getSize().x - ThinScrollBar.WIDTH,
                    "штатная полоса дерева находится за видимой частью");
            wheel(text, -3);
            wheel(table, -3);
            wheel(tree, -3);
            assertTrue(text.getTopIndex() > 0);
            assertTrue(table.getTopIndex() > 0);
            assertNotEquals(root, tree.getTopItem());
            assertTrue(list.horizontal().isShown());
            Event down = new Event(); down.button = 1; down.x = 120;
            list.horizontal().control().notifyListeners(SWT.MouseDown, down);
            list.horizontal().control().notifyListeners(SWT.MouseUp, new Event());
            assertTrue(table.getLocation().x < 0, "горизонтальная прокрутка открывает правые колонки");
            Event horizontalWheel = new Event(); horizontalWheel.count = 100;
            table.notifyListeners(SWT.MouseHorizontalWheel, horizontalWheel);
            assertEquals(0, table.getLocation().x);
            horizontalWheel = new Event(); horizontalWheel.count = -3;
            table.notifyListeners(SWT.MouseHorizontalWheel, horizontalWheel);
            assertTrue(table.getLocation().x < 0, "горизонтальный жест работает и над самой таблицей");
            text.setText("коротко");
            settle();
            field.sync();
            assertFalse(field.vertical().isShown());
            text.setText("длинная строка без переводов ".repeat(100));
            // GTK пересчитывает adjustment после размещения перенесённых строк, в цикле событий.
            settle();
            field.sync();
            assertTrue(field.vertical().isShown());
            int caretY = text.getCaretLocation().y;
            wheel(text, -3);
            waitUntil(() -> text.getCaretLocation().y < caretY, () -> "прокрутка внутри одной перенесённой строки: максимум="
                    + text.getVerticalBar().getMaximum() + ", видимая часть=" + text.getVerticalBar().getThumb());
            int afterFirstWheel = text.getCaretLocation().y;
            wheel(text, -3);
            waitUntil(() -> text.getCaretLocation().y < afterFirstWheel, () -> "повторное колесо продолжает прокрутку перенесённой строки");
            Screenshots.capture(shell, "thin-scroll-windows");
        } finally { shell.dispose(); }
    }

    @Test void standardModelListKeepsFilteringAndThinScrolling() throws Exception {
        var dialog = new org.eclipse.ui.dialogs.ElementListSelectionDialog(page.getWorkbenchWindow().getShell(),
                new org.eclipse.jface.viewers.LabelProvider()) {
            @Override
            protected org.eclipse.ui.dialogs.FilteredList createFilteredList(Composite parent) {
                var list = super.createFilteredList(parent);
                ScrollFrame.installTables(list);
                return list;
            }
        };
        dialog.setElements(java.util.stream.Stream.concat(
                java.util.stream.IntStream.range(0, 100).mapToObj(i -> "model-" + i),
                java.util.stream.Stream.of("model-with-" + "very-long-name-".repeat(30))).toArray());
        dialog.setBlockOnOpen(false);
        dialog.open();
        dialog.getShell().setSize(480, 380);
        dialog.getShell().layout(true, true);
        try {
            Table table = findTable(dialog.getShell());
            assertNotNull(table);
            waitUntil(() -> table.getItemCount() == 101);
            var scroll = assertInstanceOf(ScrollFrame.class, table.getParent());
            waitUntil(() -> scroll.vertical().isShown());
            waitUntil(() -> scroll.horizontal().isShown(), () -> "длинное имя модели: frame="
                    + scroll.getClientArea() + ", table=" + table.getClientArea()
                    + ", preferred=" + table.computeSize(SWT.DEFAULT, SWT.DEFAULT));
            wheel(table, -3);
            assertTrue(table.getTopIndex() > 0);
            dialog.setFilter("model-99");
            waitUntil(() -> table.getItemCount() == 1);
            assertEquals("model-99", table.getItem(0).getText());
        } finally { dialog.close(); }
    }

    private static Table findTable(Composite parent) {
        for (var child : parent.getChildren()) {
            if (child instanceof Table table) return table;
            if (child instanceof Composite composite) {
                Table table = findTable(composite);
                if (table != null) return table;
            }
        }
        return null;
    }

    @Test void containerScrollsChildrenAndRevealsFocusedControl() throws Exception {
        Shell shell = new Shell(page.getWorkbenchWindow().getShell(), SWT.SHELL_TRIM);
        try {
            shell.setLayout(new org.eclipse.swt.layout.FillLayout());
            ScrollArea scroll = new ScrollArea(shell, SWT.V_SCROLL | SWT.H_SCROLL);
            Composite content = new Composite(scroll, SWT.NONE);
            Button last = new Button(content, SWT.CHECK);
            last.setText("Внизу");
            last.setBounds(420, 850, 100, 30);
            scroll.setContent(content);
            scroll.setExpandHorizontal(true);
            scroll.setExpandVertical(true);
            scroll.setMinSize(new Point(600, 1000));
            shell.setSize(320, 250);
            shell.open();
            settle();
            assertTrue(scroll.vertical().isShown());
            assertTrue(scroll.horizontal().isShown());
            scroll.showControl(last);
            assertTrue(scroll.getOrigin().y > 600);
            assertTrue(scroll.getOrigin().x > 0);
            scroll.setOrigin(0, 0);
            scroll.setShowFocusedControl(true);
            last.notifyListeners(SWT.FocusIn, new Event());
            assertTrue(scroll.getOrigin().x > 0 && scroll.getOrigin().y > 600, "фокус открывает поле по обеим осям");
            scroll.setOrigin(0, 0);
            Event across = new Event(); across.count = -3;
            last.notifyListeners(SWT.MouseHorizontalWheel, across);
            assertTrue(scroll.getOrigin().x > 0 && scroll.getOrigin().y == 0, "горизонтальный жест не двигает вертикаль");
            scroll.setOrigin(0, 0);
            wheel(last, -3);
            assertTrue(scroll.getOrigin().y > 0, "колесо над дочерней кнопкой прокручивает форму");
            scroll.setMinSize(100, 100);
            assertEquals(new Point(0, 0), scroll.getOrigin());
            assertFalse(scroll.vertical().isShown());
        } finally { shell.dispose(); }
    }

    private static void wheel(org.eclipse.swt.widgets.Control control, int count) {
        Event event = new Event(); event.count = count;
        control.notifyListeners(SWT.MouseVerticalWheel, event);
    }

    /** Таблица у конца списка с неполной последней строкой не перерисовывается по кругу. */
    @Test void tableScrolledToEndSettlesWithoutRepaintLoop() throws Exception {
        Shell shell = new Shell(page.getWorkbenchWindow().getShell(), SWT.SHELL_TRIM);
        try {
            shell.setLayout(new org.eclipse.swt.layout.FillLayout());
            Table table = new Table(shell, SWT.NO_SCROLL | SWT.FULL_SELECTION);
            ScrollFrame list = ScrollFrame.wrap(table);
            new TableColumn(table, SWT.NONE).setWidth(200);
            for (int i = 0; i < 40; i++) new TableItem(table, SWT.NONE).setText("диалог " + i);
            shell.open();
            int[] paints = { 0 };
            table.addListener(SWT.Paint, event -> paints[0]++);
            // Высоты, не кратные строке: последняя строка видна не целиком.
            for (int extra : new int[] { 7, 13, 19 }) {
                shell.setSize(260, table.getItemHeight() * 6 + extra + shell.computeTrim(0, 0, 0, 0).height);
                shell.layout(true, true);
                settle();
                table.setTopIndex(table.getItemCount() - 1);
                table.setSelection(table.getItemCount() - 1);
                table.showSelection();
                settle();
                int top = table.getTopIndex();
                paints[0] = 0;
                long end = System.currentTimeMillis() + 1000;
                while (System.currentTimeMillis() < end) {
                    if (!shell.getDisplay().readAndDispatch()) Thread.sleep(10);
                }
                assertTrue(paints[0] < 10, "таблица перерисовывается по кругу: " + paints[0] + " раз за секунду");
                assertEquals(top, table.getTopIndex(), "верхняя строка не прыгает");
                assertTrue(list.vertical().isShown());
            }
        } finally { shell.dispose(); }
    }
}
