package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatStore;

/** Размер окна и положение разделителя не меняют доступность истории и даты. */
class ChatHistoryResizeTest extends AbstractChatTest {
    @Test void columnsAndScrollbarFollowWindowAndSash() throws Exception {
        Shell shell = new Shell(page.getWorkbenchWindow().getShell(), SWT.SHELL_TRIM | SWT.RESIZE);
        try {
            shell.setLayout(new FillLayout());
            SashForm sash = new SashForm(shell, SWT.HORIZONTAL);
            ChatHistoryPanel panel = new ChatHistoryPanel(sash, callbacks());
            Composite chat = new Composite(sash, SWT.NONE);
            panel.show(entries(100), "chat-0", Set.of("chat-1"), Set.of("chat-2"));
            shell.setSize(900, 600);
            sash.setWeights(40, 60);
            shell.open();

            int[][] sizes = { { 900, 600, 40 }, { 1100, 650, 65 }, { 650, 400, 20 },
                    { 1000, 450, 32 }, { 600, 320, 18 }, { 800, 500, 48 }, { 700, 950, 27 } };
            for (int[] size : sizes) {
                shell.setSize(size[0], size[1]);
                sash.setWeights(size[2], 100 - size[2]);
                shell.layout(true, true);
                settle();
                assertLayout(panel.table());
                assertTrue(((Scrolls) panel.table().getParent()).vertical().isShown(), "длинная история требует прокрутки");
            }
            Screenshots.capture(panel.control(), "history-resized");

            sash.setMaximizedControl(chat);
            settle();
            sash.setMaximizedControl(null);
            shell.layout(true, true);
            settle();
            assertLayout(panel.table());
            Table table = panel.table();
            Event wheel = new Event(); wheel.count = -3;
            table.notifyListeners(SWT.MouseVerticalWheel, wheel);
            waitUntil(() -> table.getTopIndex() > 0);

            Scrolls scroll = (Scrolls) table.getParent();
            Event down = new Event(); down.button = 1; down.y = scroll.vertical().control().getSize().y - 1;
            scroll.vertical().control().notifyListeners(SWT.MouseDown, down);
            scroll.vertical().control().notifyListeners(SWT.MouseUp, new Event());
            waitUntil(() -> table.getTopIndex() > 50);

            panel.show(entries(20), "chat-0", Set.of(), Set.of());
            shell.setSize(700, 1000);
            shell.layout(true, true);
            settle();
            assertFalse(scroll.vertical().isShown(), "высокое окно вмещает короткую историю");
            shell.setSize(700, 250);
            shell.layout(true, true);
            settle();
            assertTrue(scroll.vertical().isShown(), "после уменьшения высоты полоса возвращается");
            assertLayout(table);
        } finally { shell.dispose(); }
    }

    private static void assertLayout(Table table) {
        Scrolls scroll = (Scrolls) table.getParent();
        Rectangle area = scroll.getClientArea();
        Rectangle track = scroll.vertical().control().getBounds();
        String geometry = "область=" + area + ", таблица=" + table.getBounds() + ", полоса=" + track;
        assertFalse(scroll.horizontal().isShown(), "истории не нужна горизонтальная прокрутка: " + geometry);
        assertEquals(area.width - ThinScrollBar.WIDTH, track.x, geometry);
        assertEquals(area.height, track.height, geometry);
        assertFalse(table.getBounds().intersects(track), "таблица не перекрывает полосу: " + geometry);
        assertEquals(table.getClientArea().width, table.getColumn(0).getWidth() + table.getColumn(1).getWidth(), geometry);
        Rectangle date = table.getItem(table.getTopIndex()).getBounds(1);
        assertTrue(date.x >= 0 && date.x + date.width <= table.getClientArea().width + 1,
                "дата внутри видимой области: " + date + "; " + geometry);
    }

    private static List<ChatStore.Entry> entries(int count) {
        return IntStream.range(0, count).mapToObj(i -> new ChatStore.Entry("chat-" + i,
                "Длинное название диалога " + i, Instant.now().minusSeconds(i * 86_400L))).toList();
    }

    private static ChatHistoryPanel.Callbacks callbacks() {
        return new ChatHistoryPanel.Callbacks() {
            public void open(String id) { }
            public void rename(String id) { }
            public void export(String id) { }
            public void exportAnalysis(String id) { }
            public void delete(String id) { }
            public void searchChanged() { }
            public void newChat() { }
        };
    }
}
