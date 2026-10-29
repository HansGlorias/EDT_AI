package io.github.hansglorias.edtai.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;

import io.github.hansglorias.edtai.core.chat.Attachment;

/**
 * Сообщения, набранные, пока модель отвечает: уйдут по очереди после
 * ответа. Строка на сообщение: начало текста, «изменить» — вернуть в поле
 * ввода, «×» — убрать. Панели нет, когда очередь пуста.
 */
final class QueueBar {

    static final String EDIT = "изменить";
    static final String REMOVE = "×";
    private static final int PREVIEW = 70;

    /** Сообщение в очереди: текст как набран, прикреплённое к нему. */
    record Queued(String text, List<AttachedContext> context, List<Attachment> files) {

        Queued {
            context = List.copyOf(context);
            files = List.copyOf(files);
        }

        /** Освобождает прикреплённые фрагменты (они следят за правками в редакторе). */
        void dispose() {
            context.forEach(AttachedContext::dispose);
        }
    }

    interface Callbacks {
        /** «изменить»: сообщение уже убрано из очереди — вернуть его в поле ввода. */
        void edit(Queued message);

        /** Состав очереди изменился: перекомпоновать вид. */
        void changed();
    }

    private final Composite bar;
    private final Callbacks callbacks;
    private final List<Queued> queue = new ArrayList<>();
    private final List<Link> rows = new ArrayList<>();

    QueueBar(Composite parent, Callbacks callbacks) {
        this.callbacks = callbacks;
        bar = new Composite(parent, SWT.NONE);
        GridLayoutFactory.fillDefaults().margins(4, 0).spacing(0, 2).applyTo(bar);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(bar);
        Label title = new Label(bar, SWT.NONE);
        title.setText("В очереди — уйдёт после ответа:");
        Color meta = JFaceResources.getColorRegistry().get(JFacePreferences.QUALIFIER_COLOR);
        if (meta != null) {
            title.setForeground(meta);
        }
        updateVisibility();
    }

    void add(Queued message) {
        queue.add(message);
        refresh();
    }

    boolean isEmpty() {
        return queue.isEmpty();
    }

    /** Первое сообщение очереди — забрать для отправки. */
    Queued poll() {
        if (queue.isEmpty()) {
            return null;
        }
        Queued first = queue.remove(0);
        refresh();
        return first;
    }

    /** Все сообщения — забрать (ответ остановлен: вернуть их в поле ввода). */
    List<Queued> drain() {
        List<Queued> all = List.copyOf(queue);
        queue.clear();
        refresh();
        return all;
    }

    List<Queued> messages() {
        return List.copyOf(queue);
    }

    private void refresh() {
        if (bar.isDisposed()) {
            return;
        }
        rows.forEach(Link::dispose);
        rows.clear();
        for (Queued message : queue) {
            Link row = new Link(bar, SWT.NONE);
            row.setText(escape(preview(message)) + "   <a href=\"edit\">" + EDIT + "</a>  <a href=\"remove\">" + REMOVE + "</a>");
            row.setToolTipText(message.text());
            GridDataFactory.fillDefaults().grab(true, false).applyTo(row);
            row.addListener(SWT.Selection, event -> row.getDisplay().asyncExec(() -> {
                if (!queue.remove(message)) {
                    return;
                }
                refresh();
                if ("edit".equals(event.text)) {
                    callbacks.edit(message);
                } else {
                    message.dispose();
                }
            }));
            row.setData(message);
            rows.add(row);
        }
        updateVisibility();
        callbacks.changed();
    }

    /** Начало текста одной строкой и что приложено: «Проверь ещё… (+2 файла)». */
    static String preview(Queued message) {
        String line = message.text().strip().replaceAll("\\s+", " ");
        String text = "«" + (line.length() <= PREVIEW ? line : line.substring(0, PREVIEW - 1) + "…") + "»";
        int attached = message.context().size() + message.files().size();
        return attached == 0 ? text : text + " + вложений: " + attached;
    }

    private void updateVisibility() {
        boolean visible = !queue.isEmpty();
        bar.setVisible(visible);
        ((GridData) bar.getLayoutData()).exclude = !visible;
    }

    private static String escape(String text) {
        return text.replace("&", "&&");
    }

    // ---- для тестов ----

    List<Link> rows() {
        return List.copyOf(rows);
    }
}
