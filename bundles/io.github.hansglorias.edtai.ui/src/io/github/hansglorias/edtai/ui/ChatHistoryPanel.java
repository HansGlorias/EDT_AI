package io.github.hansglorias.edtai.ui;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import io.github.hansglorias.edtai.core.chat.ChatStore;

/**
 * Боковая панель вида чата: список диалогов, последний изменённый —
 * сверху, открытый выделен. Щелчок открывает диалог, контекстное меню —
 * «Открыть», «Удалить».
 */
final class ChatHistoryPanel {

    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter EARLIER = DateTimeFormatter.ofPattern("dd.MM");

    interface Callbacks {
        void open(String id);

        void delete(String id);
    }

    private final Composite panel;
    private final Table table;
    private final TableColumn title;
    private final TableColumn time;
    private final Callbacks callbacks;
    private final List<String> ids = new ArrayList<>();
    private boolean filling;

    ChatHistoryPanel(Composite parent, Callbacks callbacks) {
        this.callbacks = callbacks;
        panel = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().spacing(0, 4).applyTo(panel);
        Label header = new Label(panel, SWT.NONE);
        header.setText("Чаты");
        header.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        GridDataFactory.fillDefaults().indent(4, 0).applyTo(header);

        // Таблица — единственный ребёнок своего контейнера: так TableColumnLayout сам делит ширину
        // и не даёт горизонтальной прокрутки (на Windows иначе колонки «плывут»).
        Composite holder = new Composite(panel, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(holder);
        TableColumnLayout columns = new TableColumnLayout();
        holder.setLayout(columns);
        table = new Table(holder, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.BORDER);
        title = new TableColumn(table, SWT.NONE);
        time = new TableColumn(table, SWT.RIGHT);
        columns.setColumnData(title, new ColumnWeightData(100, 60));
        GC gc = new GC(table);
        try {
            columns.setColumnData(time, new ColumnPixelData(gc.textExtent("00.00 ").x + 8));
        } finally {
            gc.dispose();
        }
        table.addListener(SWT.Selection, event -> {
            String id = selected();
            if (!filling && id != null) {
                callbacks.open(id);
            }
        });

        Menu menu = new Menu(table);
        MenuItem open = new MenuItem(menu, SWT.PUSH);
        open.setText("Открыть");
        open.addListener(SWT.Selection, event -> {
            if (selected() != null) {
                callbacks.open(selected());
            }
        });
        MenuItem delete = new MenuItem(menu, SWT.PUSH);
        delete.setText("Удалить");
        delete.addListener(SWT.Selection, event -> {
            if (selected() != null) {
                callbacks.delete(selected());
            }
        });
        menu.addListener(SWT.Show, event -> {
            open.setEnabled(selected() != null);
            delete.setEnabled(selected() != null);
        });
        table.setMenu(menu);
    }

    /** Показывает диалоги; {@code current} — открытый. */
    void show(List<ChatStore.Entry> entries, String current) {
        filling = true;
        try {
            table.removeAll();
            ids.clear();
            LocalDate today = LocalDate.now();
            for (ChatStore.Entry entry : entries) {
                LocalDateTime updated = LocalDateTime.ofInstant(entry.updated(), ZoneId.systemDefault());
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(new String[] { entry.title(), (updated.toLocalDate().equals(today) ? TODAY : EARLIER).format(updated) });
                ids.add(entry.id());
                if (entry.id().equals(current)) {
                    table.setSelection(item);
                }
            }
        } finally {
            filling = false;
        }
    }

    private String selected() {
        int index = table.getSelectionIndex();
        return index >= 0 && index < ids.size() ? ids.get(index) : null;
    }

    Control control() {
        return panel;
    }

    // ---- для тестов ----

    List<String> titles() {
        List<String> titles = new ArrayList<>();
        for (TableItem item : table.getItems()) {
            titles.add(item.getText(0));
        }
        return titles;
    }

    void select(int index) {
        table.setSelection(index);
        table.notifyListeners(SWT.Selection, null);
    }
}
