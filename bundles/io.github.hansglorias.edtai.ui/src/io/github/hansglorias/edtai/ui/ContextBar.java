package io.github.hansglorias.edtai.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;

import io.github.hansglorias.edtai.core.context.ContextItem;
import io.github.hansglorias.edtai.core.context.ContextPrompt;

/**
 * Панель над полем ввода: прикреплённые фрагменты, по одному в строку
 * (переносятся при узком виде). Подпись — ссылка, открывает файл на этих
 * строках; «×» — убрать. Панели нет, когда фрагментов нет.
 */
final class ContextBar {

    static final String REMOVE = "×";
    private static final String HREF_OPEN = "open";
    private static final String HREF_REMOVE = "remove";

    interface Callbacks {
        void open(AttachedContext context);

        /** Состав фрагментов изменился: нужно перекомпоновать вид. */
        void changed();
    }

    private final Composite bar;
    private final Callbacks callbacks;
    private final List<AttachedContext> items = new ArrayList<>();
    private final List<Link> chips = new ArrayList<>();
    private int limit;

    ContextBar(Composite parent, Callbacks callbacks) {
        this.callbacks = callbacks;
        bar = new Composite(parent, SWT.NONE);
        RowLayout layout = new RowLayout(SWT.HORIZONTAL);
        layout.wrap = true;
        layout.center = true;
        layout.marginLeft = 4;
        layout.marginRight = 4;
        layout.marginTop = 2;
        layout.marginBottom = 0;
        layout.spacing = 12;
        bar.setLayout(layout);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(bar);

        Label title = new Label(bar, SWT.NONE);
        title.setText("Контекст:");
        Color meta = JFaceResources.getColorRegistry().get(JFacePreferences.QUALIFIER_COLOR);
        if (meta != null) {
            title.setForeground(meta);
        }
        updateVisibility();
    }

    Composite control() {
        return bar;
    }

    /** Лимит контекста из настроек: по нему видно, что не влезет. */
    void setLimit(int limit) {
        if (this.limit != limit) {
            this.limit = limit;
            refresh();
        }
    }

    /** @return {@code false}, если этот фрагмент уже прикреплён */
    boolean add(AttachedContext context) {
        for (AttachedContext item : items) {
            if (item.sameAs(context)) {
                context.dispose();
                return false;
            }
        }
        items.add(context);
        refresh();
        return true;
    }

    void remove(AttachedContext context) {
        if (items.remove(context)) {
            context.dispose();
            refresh();
        }
    }

    void clear() {
        items.forEach(AttachedContext::dispose);
        items.clear();
        refresh();
    }

    List<AttachedContext> items() {
        return List.copyOf(items);
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    /** Фрагменты сейчас, обрезанные по лимиту. */
    List<ContextItem> snapshot() {
        List<ContextItem> snapshot = new ArrayList<>();
        for (AttachedContext item : items) {
            ContextItem current = item.snapshot();
            if (current != null) {
                snapshot.add(current);
            }
        }
        return ContextPrompt.fit(snapshot, limit);
    }

    /** Перерисовывает подписи: строки могли сдвинуться от правок в редакторе. */
    void refresh() {
        if (bar.isDisposed()) {
            return;
        }
        chips.forEach(Link::dispose);
        chips.clear();
        List<ContextItem> fitted = new ArrayList<>();
        for (AttachedContext item : items) {
            ContextItem current = item.snapshot();
            fitted.add(current);
        }
        List<ContextItem> present = fitted.stream().filter(item -> item != null).toList();
        List<ContextItem> cut = ContextPrompt.fit(present, limit);
        int next = 0;
        for (int i = 0; i < items.size(); i++) {
            AttachedContext item = items.get(i);
            ContextItem shown = fitted.get(i) == null ? null : cut.get(next++);
            String label = shown != null ? shown.label() : item.file().getProjectRelativePath() + " (файл не найден)";
            Link chip = new Link(bar, SWT.NONE);
            chip.setText("<a href=\"" + HREF_OPEN + "\">" + escape(label) + "</a> <a href=\"" + HREF_REMOVE + "\">" + REMOVE + "</a>");
            chip.setToolTipText(shown != null ? shown.description() : item.file().getFullPath().toString());
            chip.addListener(SWT.Selection, event -> {
                if (HREF_REMOVE.equals(event.text)) {
                    // Не уничтожаем ссылку внутри её же обработчика.
                    chip.getDisplay().asyncExec(() -> remove(item));
                } else {
                    callbacks.open(item);
                }
            });
            chip.setData(label);
            chips.add(chip);
        }
        updateVisibility();
        callbacks.changed();
    }

    private void updateVisibility() {
        boolean visible = !items.isEmpty();
        bar.setVisible(visible);
        ((GridData) bar.getLayoutData()).exclude = !visible;
    }

    /** {@code &} в тексте {@link Link} — мнемоника, его нужно удвоить. */
    private static String escape(String text) {
        return text.replace("&", "&&");
    }

    // ---- для тестов ----

    List<Link> chips() {
        return List.copyOf(chips);
    }

    /** Подписи фрагментов на панели. */
    List<String> labels() {
        return chips.stream().map(chip -> (String) chip.getData()).toList();
    }
}
