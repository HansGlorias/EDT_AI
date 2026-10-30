package io.github.hansglorias.edtai.ui;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import io.github.hansglorias.edtai.core.chat.ChatStore;

/**
 * Боковая панель вида чата: «Новый чат», поиск, список диалогов, последний изменённый —
 * сверху, открытый выделен. Щелчок открывает диалог, F2 — переименовать,
 * контекстное меню — «Открыть», «Переименовать…», «Экспорт в Markdown…»,
 * «Удалить».
 */
final class ChatHistoryPanel {

    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter EARLIER = DateTimeFormatter.ofPattern("dd.MM");
    /** Пауза после набора в поиске: каждый поиск читает все диалоги. */
    private static final int SEARCH_DELAY_MS = 250;

    interface Callbacks {
        void open(String id);

        void rename(String id);

        void export(String id);

        void delete(String id);

        /** Запрос поиска изменился — показать найденное. */
        void searchChanged();

        /** Состояние живого чата: точка или пусто. */
        String state(String id);

        /** Подсказка к точке состояния. */
        String stateTooltip(String id);

        void newChat();
    }

    private final Composite panel;
    private final Text search;
    private final ToolItem newChat;
    private final Label nothing;
    private final Table table;
    private final TableColumn title;
    private final TableColumn time;
    private final Callbacks callbacks;
    private final List<String> ids = new ArrayList<>();
    private boolean filling;
    private int searchGeneration;

    ChatHistoryPanel(Composite parent, Callbacks callbacks) {
        this.callbacks = callbacks;
        panel = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().spacing(0, 4).applyTo(panel);
        Composite top = new Composite(panel, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(top);
        GridLayoutFactory.fillDefaults().numColumns(2).applyTo(top);
        Label header = new Label(top, SWT.NONE);
        header.setText("Чаты");
        header.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        GridDataFactory.fillDefaults().grab(true, false).align(SWT.FILL, SWT.CENTER).indent(4, 0).applyTo(header);
        ToolBar tools = new ToolBar(top, SWT.FLAT);
        ToolItem newChat = new ToolItem(tools, SWT.PUSH);
        newChat.setImage(Icons.image(Icons.NEW_CHAT));
        newChat.setToolTipText("Новый чат");
        newChat.addListener(SWT.Selection, event -> callbacks.newChat());
        this.newChat = newChat;
        search = new Text(panel, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        search.setMessage("Поиск по чатам");
        search.setToolTipText("Все слова — в названии или сообщениях чата");
        GridDataFactory.fillDefaults().grab(true, false).applyTo(search);
        search.addListener(SWT.Modify, event -> {
            int generation = ++searchGeneration;
            search.getDisplay().timerExec(SEARCH_DELAY_MS, () -> {
                if (!search.isDisposed() && generation == searchGeneration) {
                    callbacks.searchChanged();
                }
            });
        });
        search.addListener(SWT.DefaultSelection, event -> {
            if (event.detail == SWT.ICON_CANCEL) {
                search.setText("");
            }
        });
        nothing = new Label(panel, SWT.WRAP);
        nothing.setText("Ничего не найдено");
        GridDataFactory.fillDefaults().grab(true, false).indent(4, 0).exclude(true).applyTo(nothing);
        nothing.setVisible(false);

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
            // Отступы ячейки на Windows больше, чем кажется: с запасом, иначе время обрезается до «19:…».
            int text = Math.max(gc.textExtent("00:00").x, gc.textExtent("00.00").x);
            columns.setColumnData(time, new ColumnPixelData(text + 3 * gc.textExtent("0").x + 12));
        } finally {
            gc.dispose();
        }
        table.addListener(SWT.Selection, event -> {
            String id = selected();
            if (!filling && id != null) {
                callbacks.open(id);
            }
        });

        table.addListener(SWT.MouseHover, event -> {
            TableItem item = table.getItem(new org.eclipse.swt.graphics.Point(event.x, event.y));
            int index = item == null ? -1 : table.indexOf(item);
            table.setToolTipText(index >= 0 && index < ids.size() ? callbacks.stateTooltip(ids.get(index)) : null);
        });

        table.addListener(SWT.KeyDown, event -> {
            if (event.keyCode == SWT.F2 && selected() != null) {
                callbacks.rename(selected());
            }
        });

        Menu menu = new Menu(table);
        MenuItem create = new MenuItem(menu, SWT.PUSH);
        create.setText("Новый чат");
        create.setImage(Icons.image(Icons.NEW_CHAT));
        create.addListener(SWT.Selection, event -> callbacks.newChat());
        new MenuItem(menu, SWT.SEPARATOR);
        List<MenuItem> items = List.of(item(menu, "Открыть", callbacks::open),
                item(menu, "Переименовать…\tF2", callbacks::rename),
                item(menu, "Экспорт в Markdown…", callbacks::export), item(menu, "Удалить", callbacks::delete));
        menu.addListener(SWT.Show, event -> items.forEach(item -> item.setEnabled(selected() != null)));
        table.setMenu(menu);
    }

    private MenuItem item(Menu menu, String text, Consumer<String> action) {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(text);
        item.addListener(SWT.Selection, event -> {
            if (selected() != null) {
                action.accept(selected());
            }
        });
        return item;
    }

    /** Что набрано в поиске. */
    String query() {
        return search.getText();
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
                String state = callbacks.state(entry.id());
                String name = state.isEmpty() ? entry.title() : state + " " + entry.title();
                item.setText(new String[] { name,
                        (updated.toLocalDate().equals(today) ? TODAY : EARLIER).format(updated) });
                ids.add(entry.id());
                if (entry.id().equals(current)) {
                    table.setSelection(item);
                }
            }
        } finally {
            filling = false;
        }
        boolean empty = entries.isEmpty() && !query().isBlank();
        if (nothing.getVisible() != empty) {
            nothing.setVisible(empty);
            ((GridData) nothing.getLayoutData()).exclude = !empty;
            panel.layout(true);
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

    /** «Новый чат» в заголовке панели, как щелчок. */
    void clickNewChat() {
        newChat.notifyListeners(SWT.Selection, new Event());
    }

    void search(String query) {
        search.setText(query);
    }

    boolean isNothingFound() {
        return nothing.getVisible();
    }

    /** Действие меню строки {@code index}, как щелчок по пункту. */
    void runMenuItem(int index, String text) {
        table.setSelection(index);
        for (MenuItem item : table.getMenu().getItems()) {
            if (item.getText().startsWith(text)) {
                item.notifyListeners(SWT.Selection, new Event());
                return;
            }
        }
        throw new IllegalArgumentException(text);
    }

    void select(int index) {
        table.setSelection(index);
        table.notifyListeners(SWT.Selection, null);
    }
}
