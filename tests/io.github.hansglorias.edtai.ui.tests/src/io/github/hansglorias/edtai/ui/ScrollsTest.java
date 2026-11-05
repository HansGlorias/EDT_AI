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

class ScrollsTest extends AbstractChatTest {
    @Test void fieldsListsAndTreesUseThinBars() throws Exception {
        Shell shell = new Shell(page.getWorkbenchWindow().getShell(), SWT.SHELL_TRIM);
        try {
            GridLayoutFactory.fillDefaults().numColumns(3).applyTo(shell);
            Text text = new Text(shell, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
            text.setText("строка\n".repeat(100));
            GridDataFactory.fillDefaults().grab(true, true).hint(180, 160).applyTo(text);
            Scrolls field = Scrolls.wrap(text);
            Table table = new Table(shell, SWT.NO_SCROLL | SWT.FULL_SELECTION);
            GridDataFactory.fillDefaults().grab(true, true).hint(180, 160).applyTo(table);
            Scrolls list = Scrolls.wrap(table);
            new TableColumn(table, SWT.NONE).setWidth(500);
            for (int i = 0; i < 80; i++) new TableItem(table, SWT.NONE).setText("диалог " + i);
            Tree tree = new Tree(shell, SWT.V_SCROLL);
            GridDataFactory.fillDefaults().grab(true, true).hint(180, 160).applyTo(tree);
            Scrolls hierarchy = Scrolls.wrap(tree);
            new TreeColumn(tree, SWT.NONE).setWidth(500);
            TreeItem root = new TreeItem(tree, SWT.NONE);
            root.setText("сервер");
            for (int i = 0; i < 80; i++) new TreeItem(root, SWT.NONE).setText("инструмент " + i);
            root.setExpanded(true);
            shell.setSize(600, 240);
            shell.open();
            shell.layout(true, true);
            settle();
            for (Scrolls scroll : new Scrolls[] { field, list, hierarchy }) {
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
            text.setText("коротко");
            field.sync();
            assertFalse(field.vertical().isShown());
            text.setText("длинная строка без переводов ".repeat(100));
            field.sync();
            assertTrue(field.vertical().isShown());
            wheel(text, -3);
            assertTrue(text.getTopPixel() > 0, "прокрутка внутри одной перенесённой строки");
            Screenshots.capture(shell, "thin-scroll-windows");
        } finally { shell.dispose(); }
    }

    @Test void standardModelListKeepsFilteringAndThinScrolling() throws Exception {
        var dialog = new org.eclipse.ui.dialogs.ElementListSelectionDialog(page.getWorkbenchWindow().getShell(),
                new org.eclipse.jface.viewers.LabelProvider()) {
            @Override
            protected org.eclipse.ui.dialogs.FilteredList createFilteredList(Composite parent) {
                var list = super.createFilteredList(parent);
                Scrolls.installTables(list);
                return list;
            }
        };
        dialog.setElements(java.util.stream.IntStream.range(0, 100).mapToObj(i -> "model-" + i).toArray());
        dialog.setBlockOnOpen(false);
        dialog.open();
        try {
            Table table = findTable(dialog.getShell());
            assertNotNull(table);
            waitUntil(() -> table.getItemCount() == 100);
            var scroll = assertInstanceOf(Scrolls.class, table.getParent());
            waitUntil(() -> scroll.vertical().isShown());
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
            ThinScrolledComposite scroll = new ThinScrolledComposite(shell, SWT.V_SCROLL | SWT.H_SCROLL);
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
}
