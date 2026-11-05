package io.github.hansglorias.edtai.ui;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.TableColumnLayout;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.ColumnWeightData;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
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

    /** Жёлтая точка у чата, который сейчас отвечает (в том числе в фоне). */
    static final String ANSWERING = "отвечает";
    /** Точка «ответ готов» у чата, который ответил в фоне. */
    static final String DONE = "готово";
    private static final int DOT_SIZE = 12;
    private static final DateTimeFormatter TODAY = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter EARLIER = DateTimeFormatter.ofPattern("dd.MM");
    /** Пауза после набора в поиске: каждый поиск читает все диалоги. */
    private static final int SEARCH_DELAY_MS = 250;

    interface Callbacks {
        void open(String id);

        void rename(String id);

        void export(String id);

        void exportAnalysis(String id);

        void delete(String id);

        /** Запрос поиска изменился — показать найденное. */
        void searchChanged();

        void newChat();
    }

    private final Composite panel;
    private final Text search;
    private final ToolItem newChat;
    private final Label nothing;
    private final Table table;
    private final TableColumn title;
    private final TableColumn time;
    private final Font dateFont;
    private TableItem hovered;
    private final Callbacks callbacks;
    private final List<String> ids = new ArrayList<>();
    private boolean filling;
    /** Точки состояния у названия: отвечает — жёлтая, ответил, пока чат не открывали, — зелёная. */
    private Image answeringDot;
    private Image doneDot;
    private int searchGeneration;

    ChatHistoryPanel(Composite parent, Callbacks callbacks) {
        this.callbacks = callbacks;
        panel = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().margins(8, 6).spacing(0, 6).applyTo(panel);
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
        table = new Table(holder, SWT.SINGLE | SWT.FULL_SELECTION | SWT.NO_SCROLL);
        FontData[] fontData = JFaceResources.getDialogFont().getFontData();
        for (FontData data : fontData) { data.setHeight(Math.max(9, data.getHeight() - 1)); data.setStyle(SWT.NORMAL); }
        Font font = new Font(table.getDisplay(), fontData);
        table.setFont(font);
        FontData[] small = font.getFontData();
        for (FontData data : small) data.setHeight(Math.max(8, data.getHeight() - 1));
        dateFont = new Font(table.getDisplay(), small);
        table.addListener(SWT.Dispose, event -> { font.dispose(); dateFont.dispose(); });
        table.setCursor(table.getDisplay().getSystemCursor(SWT.CURSOR_ARROW));
        table.addListener(SWT.MeasureItem, event -> event.height = Math.max(24, event.gc.textExtent("Аа").y + 8));
        table.addListener(SWT.EraseItem, this::eraseRow);
        table.addListener(SWT.PaintItem, this::paintRow);
        table.addListener(SWT.MouseMove, event -> hover(table.getItem(new Point(event.x, event.y))));
        table.addListener(SWT.MouseExit, event -> hover(null));
        title = new TableColumn(table, SWT.NONE);
        time = new TableColumn(table, SWT.RIGHT);
        columns.setColumnData(title, new ColumnWeightData(100, 32));
        GC gc = new GC(table);
        try {
            gc.setFont(dateFont);
            int text = Math.max(gc.textExtent("00:00").x, gc.textExtent("00.00").x);
            columns.setColumnData(time, new ColumnPixelData(text + 20));
            ScrollFrame.columns(table, 32 + text + 20);
        } finally {
            gc.dispose();
        }
        table.addListener(SWT.Selection, event -> {
            String id = selected();
            if (!filling && id != null) {
                callbacks.open(id);
            }
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
        List<MenuItem> items = List.of(item(menu, "Открыть", callbacks::open), item(menu, "Переименовать…\tF2", callbacks::rename),
                item(menu, "Экспорт в Markdown…", callbacks::export),
                item(menu, "Выгрузка для разбора (ZIP)…", callbacks::exportAnalysis), item(menu, "Удалить", callbacks::delete));
        menu.addListener(SWT.Show, event -> items.forEach(item -> item.setEnabled(selected() != null)));
        table.setMenu(menu);
    }

    private void hover(TableItem item) {
        if (hovered == item) return;
        TableItem previous = hovered;
        hovered = item;
        for (TableItem row : new TableItem[] { previous, item }) {
            if (row == null || row.isDisposed()) continue;
            Rectangle bounds = row.getBounds(0);
            table.redraw(0, bounds.y, table.getClientArea().width, table.getItemHeight(), false);
        }
    }

    private void eraseRow(Event event) {
        event.detail &= ~(SWT.SELECTED | SWT.HOT | SWT.FOCUSED | SWT.FOREGROUND | SWT.BACKGROUND);
        TableItem item = (TableItem) event.item;
        GC gc = event.gc;
        gc.setBackground(table.getBackground());
        gc.fillRectangle(event.x, event.y, event.width, event.height);
        boolean selected = table.indexOf(item) == table.getSelectionIndex();
        if (!selected && item != hovered) return;
        Color fill = new Color(blend(table.getBackground().getRGB(), table.getForeground().getRGB(), selected ? 0.09 : 0.045));
        try {
            gc.setAntialias(SWT.ON);
            gc.setBackground(fill);
            gc.fillRoundRectangle(6, event.y + 2, Math.max(0, table.getClientArea().width - 12),
                    Math.max(0, table.getItemHeight() - 4), 8, 8);
        } finally { fill.dispose(); }
    }

    private void paintRow(Event event) {
        TableItem item = (TableItem) event.item;
        GC gc = event.gc;
        Font previousFont = gc.getFont();
        Color previousColor = gc.getForeground();
        Color color = new Color(blend(table.getBackground().getRGB(), table.getForeground().getRGB(), event.index == 0 ? 0.9 : 0.55));
        try {
            gc.setForeground(color);
            gc.setFont(event.index == 0 ? table.getFont() : dateFont);
            int height = table.getItemHeight();
            String text = item.getText(event.index);
            int x;
            if (event.index == 0) {
                Image image = item.getImage(0);
                if (image != null) gc.drawImage(image, 14, event.y + (height - DOT_SIZE) / 2);
                x = 14 + DOT_SIZE + 4;
                text = shorten(gc, text, Math.max(0, title.getWidth() - x - 6));
            } else {
                x = table.getClientArea().width - 14 - gc.textExtent(text).x;
            }
            gc.drawText(text, x, event.y + (height - gc.textExtent(text).y) / 2, true);
        } finally {
            gc.setFont(previousFont);
            gc.setForeground(previousColor);
            color.dispose();
        }
    }

    private static String shorten(GC gc, String text, int width) {
        if (gc.textExtent(text).x <= width) return text;
        if (gc.textExtent("…").x > width) return "";
        int low = 0, high = text.codePointCount(0, text.length());
        while (low < high) {
            int middle = (low + high + 1) / 2;
            String part = text.substring(0, text.offsetByCodePoints(0, middle)) + "…";
            if (gc.textExtent(part).x <= width) low = middle; else high = middle - 1;
        }
        return text.substring(0, text.offsetByCodePoints(0, low)) + "…";
    }

    private static RGB blend(RGB from, RGB to, double share) {
        return new RGB((int) Math.round(from.red + (to.red - from.red) * share),
                (int) Math.round(from.green + (to.green - from.green) * share),
                (int) Math.round(from.blue + (to.blue - from.blue) * share));
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

    /**
     * Показывает диалоги; {@code current} — открытый, {@code answering} —
     * те, что сейчас отвечают, {@code unread} — ответили в фоне, ещё не открыты.
     */
    void show(List<ChatStore.Entry> entries, String current, Set<String> answering, Set<String> unread) {
        hovered = null;
        int top = table.getTopIndex();
        filling = true;
        try {
            table.removeAll();
            ids.clear();
            LocalDate today = LocalDate.now();
            for (ChatStore.Entry entry : entries) {
                LocalDateTime updated = LocalDateTime.ofInstant(entry.updated(), ZoneId.systemDefault());
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(new String[] { entry.title(), (updated.toLocalDate().equals(today) ? TODAY : EARLIER).format(updated) });
                item.setImage(0, answering.contains(entry.id()) ? dot(true) : unread.contains(entry.id()) ? dot(false) : null);
                item.setData(answering.contains(entry.id()) ? ANSWERING : unread.contains(entry.id()) ? DONE : null);
                ids.add(entry.id());
                if (entry.id().equals(current)) {
                    table.setSelection(item);
                }
            }
        } finally {
            filling = false;
        }
        // Только после замены данных, никогда из Paint: GTK может сохранить старый верхний индекс.
        if (table.getItemCount() > 0) table.setTopIndex(Math.min(top, table.getItemCount() - 1));
        boolean empty = entries.isEmpty() && !query().isBlank();
        if (nothing.getVisible() != empty) {
            nothing.setVisible(empty);
            ((GridData) nothing.getLayoutData()).exclude = !empty;
            panel.layout(true);
        }
    }

    /** Круглая точка со сглаживанием: прозрачный фон — и в светлой, и в тёмной теме. */
    private Image dot(boolean answering) {
        Image image = answering ? answeringDot : doneDot;
        if (image == null) {
            RGB color = answering ? new RGB(0xE3, 0xA0, 0x08) : new RGB(0x2D, 0xA4, 0x4E);
            int size = DOT_SIZE;
            ImageData data = new ImageData(size, size, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
            double center = (size - 1) / 2.0;
            double radius = size * 0.25;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double light = Math.max(0, 0.2 - (y - center) / size * 0.35);
                    data.setPixel(x, y, data.palette.getPixel(blend(color, new RGB(255, 255, 255), light)));
                    double distance = Math.hypot(x - center, y - center);
                    double core = Math.max(0, Math.min(1, radius + 0.5 - distance));
                    double halo = 0.12 * Math.max(0, 1 - distance / (size * 0.48));
                    data.setAlpha(x, y, (int) Math.round(255 * Math.max(core, halo)));
                }
            }
            image = new Image(panel.getDisplay(), data);
            if (answering) {
                answeringDot = image;
            } else {
                doneDot = image;
            }
            panel.addListener(SWT.Dispose, event -> {
                if (answeringDot != null) {
                    answeringDot.dispose();
                }
                if (doneDot != null) {
                    doneDot.dispose();
                }
            });
        }
        return image;
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

    /** Точки у названий: {@link #ANSWERING}, {@link #DONE} или {@code null}. */
    List<String> dots() {
        List<String> dots = new ArrayList<>();
        for (TableItem item : table.getItems()) {
            dots.add((String) item.getData());
        }
        return dots;
    }

    /** Вторая колонка: время или «отвечает…». */
    List<String> times() {
        List<String> times = new ArrayList<>();
        for (TableItem item : table.getItems()) {
            times.add(item.getText(1));
        }
        return times;
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
