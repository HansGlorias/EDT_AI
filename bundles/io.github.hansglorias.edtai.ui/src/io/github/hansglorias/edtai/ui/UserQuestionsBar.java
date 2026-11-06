package io.github.hansglorias.edtai.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import io.github.hansglorias.edtai.core.tools.UserQuestions;
import io.github.hansglorias.edtai.core.tools.UserQuestions.Answer;
import io.github.hansglorias.edtai.core.tools.UserQuestions.Request;

/**
 * Форма уточнений модели над полем ввода. Каждый вопрос — карточкой с цветной полосой слева (синяя —
 * ждёт ответа, зелёная — отвечен): номер, вопрос, варианты и поле своего ответа. В заголовке —
 * сколько вопросов уже отвечено. Ctrl+Enter отправляет, Esc останавливает. Одна форма для
 * открытого чата; черновики принадлежат Pending своего Live.
 */
final class UserQuestionsBar {
    static final String TITLE = "Модель уточняет задачу";
    static final String SUBMIT = "Отправить ответы";

    static final class Pending {
        final Request request;
        final CompletableFuture<List<Answer>> future = new CompletableFuture<>();
        final Map<String, String> texts = new HashMap<>();
        final Map<String, String> selected = new HashMap<>();

        Pending(Request request) { this.request = request; }
    }

    private static final int BADGE = 18;
    private static final RGB ANSWERED = new RGB(46, 160, 67);

    private final Composite bar;
    private final Label progress;
    private final ScrollArea scroll;
    private final Composite fields;
    private final Button submit;
    private final Runnable changed;
    private Pending pending;
    private final List<Text> inputs = new ArrayList<>();
    private final List<Button> options = new ArrayList<>();
    /** Полосы карточек вопросов: перекрашиваются, когда вопрос отвечен. */
    private final List<Canvas> strips = new ArrayList<>();

    UserQuestionsBar(Composite parent, Runnable changed) {
        this.changed = changed;
        bar = new Composite(parent, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(bar);
        GridLayoutFactory.swtDefaults().margins(8, 6).spacing(0, 6).applyTo(bar);

        Composite header = new Composite(bar, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(header);
        GridLayoutFactory.fillDefaults().numColumns(3).spacing(8, 0).applyTo(header);
        Canvas badge = new Canvas(header, SWT.DOUBLE_BUFFERED);
        GridDataFactory.swtDefaults().hint(BADGE, BADGE).align(SWT.CENTER, SWT.CENTER).applyTo(badge);
        badge.addListener(SWT.Paint, event -> paintBadge(event.gc, badge));
        Label title = new Label(header, SWT.NONE);
        title.setText(TITLE);
        title.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        GridDataFactory.fillDefaults().grab(true, false).align(SWT.FILL, SWT.CENTER).applyTo(title);
        progress = new Label(header, SWT.NONE);
        progress.setForeground(meta(header));
        GridDataFactory.swtDefaults().align(SWT.END, SWT.CENTER).applyTo(progress);
        Label hint = new Label(bar, SWT.WRAP);
        hint.setText("Выберите вариант или напишите свой ответ — он заменит выбранный вариант.");
        hint.setForeground(meta(bar));
        GridDataFactory.fillDefaults().grab(true, false).hint(200, SWT.DEFAULT).applyTo(hint);

        scroll = new ScrollArea(bar, SWT.V_SCROLL);
        GridDataFactory.fillDefaults().grab(true, false).hint(200, 300).applyTo(scroll);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        fields = new Composite(scroll, SWT.NONE);
        GridLayoutFactory.fillDefaults().spacing(0, 10).applyTo(fields);
        scroll.setContent(fields);
        scroll.addListener(SWT.Resize, event -> resize());

        Composite actions = new Composite(bar, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(actions);
        GridLayoutFactory.fillDefaults().numColumns(3).spacing(8, 0).applyTo(actions);
        submit = new Button(actions, SWT.PUSH);
        submit.setText(SUBMIT);
        submit.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        submit.addListener(SWT.Selection, event -> submit());
        Button cancel = new Button(actions, SWT.PUSH);
        cancel.setText("Остановить");
        cancel.setToolTipText("Остановить ответ модели без уточнений (Esc)");
        cancel.addListener(SWT.Selection, event -> { if (pending != null) pending.future.cancel(true); });
        Label keys = new Label(actions, SWT.NONE);
        keys.setText("Ctrl+Enter — отправить · Esc — остановить");
        keys.setForeground(meta(actions));
        GridDataFactory.fillDefaults().grab(true, false).align(SWT.END, SWT.CENTER).applyTo(keys);
        visible(false);
    }

    void show(Pending value) {
        pending = null;
        for (Control child : fields.getChildren()) child.dispose();
        inputs.clear();
        options.clear();
        strips.clear();
        if (value == null || value.future.isDone()) {
            visible(false);
            return;
        }
        pending = value;
        int number = 0;
        for (var question : value.request.questions()) {
            int index = number++;
            Composite card = new Composite(fields, SWT.NONE);
            GridDataFactory.fillDefaults().grab(true, false).applyTo(card);
            GridLayoutFactory.fillDefaults().numColumns(2).spacing(8, 0).applyTo(card);
            Canvas strip = new Canvas(card, SWT.DOUBLE_BUFFERED);
            GridDataFactory.fillDefaults().hint(3, SWT.DEFAULT).grab(false, true).applyTo(strip);
            strip.addListener(SWT.Paint, event -> {
                event.gc.setBackground(answered(value, index) ? new Color(ANSWERED) : accent(strip));
                event.gc.fillRectangle(strip.getClientArea());
            });
            strips.add(strip);
            Composite group = new Composite(card, SWT.NONE);
            GridDataFactory.fillDefaults().grab(true, false).applyTo(group);
            GridLayoutFactory.fillDefaults().spacing(0, 3).applyTo(group);
            Label label = new Label(group, SWT.WRAP);
            label.setText((index + 1) + ". " + question.text());
            label.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
            GridDataFactory.fillDefaults().grab(true, false).hint(200, SWT.DEFAULT).applyTo(label);
            for (var option : question.options()) {
                Button radio = new Button(group, SWT.RADIO | SWT.WRAP);
                radio.setText(option.text());
                GridDataFactory.fillDefaults().grab(true, false).indent(4, 0).hint(200, SWT.DEFAULT).applyTo(radio);
                radio.setSelection(option.id().equals(value.selected.get(question.id())));
                radio.addListener(SWT.Selection, event -> {
                    if (pending == value && radio.getSelection()) {
                        value.selected.put(question.id(), option.id());
                        updateSubmit();
                    }
                });
                options.add(radio);
            }
            Text text = new Text(group, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
            text.setTextLimit(4000);
            text.setMessage(question.options().isEmpty() ? "Ваш ответ…" : "Свой вариант ответа…");
            GridDataFactory.fillDefaults().grab(true, false).indent(4, 2).hint(200, text.getLineHeight() * 2).applyTo(text);
            ScrollFrame.wrap(text);
            text.setText(value.texts.getOrDefault(question.id(), ""));
            text.addModifyListener(event -> {
                if (pending == value) {
                    value.texts.put(question.id(), text.getText());
                    updateSubmit();
                }
            });
            text.addListener(SWT.Traverse, event -> {
                if (event.detail == SWT.TRAVERSE_ESCAPE) { event.doit = false; value.future.cancel(true); }
            });
            text.addListener(SWT.KeyDown, this::submitOnCtrlEnter);
            inputs.add(text);
        }
        value.future.whenComplete((answers, error) -> {
            if (!bar.isDisposed()) bar.getDisplay().asyncExec(() -> {
                if (!bar.isDisposed() && pending == value) show(null);
            });
        });
        updateSubmit();
        visible(true);
        resize();
    }

    /** Ctrl+Enter (на Mac — Cmd+Enter) в поле ответа — отправить, если ответы на все вопросы есть. */
    private void submitOnCtrlEnter(Event event) {
        if ((event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR) && (event.stateMask & SWT.MOD1) != 0) {
            event.doit = false;
            submit();
        }
    }

    private static boolean answered(Pending value, int index) {
        var question = value.request.questions().get(index);
        return !value.texts.getOrDefault(question.id(), "").isBlank()
                || question.options().stream().anyMatch(option -> option.id().equals(value.selected.get(question.id())));
    }

    private List<Answer> answers() {
        if (pending == null || pending.future.isDone()) return List.of();
        List<Answer> answers = new ArrayList<>();
        for (var question : pending.request.questions()) {
            String text = pending.texts.getOrDefault(question.id(), "").strip();
            String selected = pending.selected.getOrDefault(question.id(), "");
            if (!text.isEmpty()) {
                answers.add(new Answer(question.id(), "", text));
            } else {
                var option = question.options().stream().filter(item -> item.id().equals(selected)).findFirst();
                if (option.isEmpty()) return List.of();
                answers.add(new Answer(question.id(), selected, option.get().text()));
            }
        }
        return answers;
    }

    private void updateSubmit() {
        submit.setEnabled(!answers().isEmpty());
        if (pending != null) {
            int total = pending.request.questions().size();
            int done = 0;
            for (int i = 0; i < total; i++) {
                if (answered(pending, i)) done++;
            }
            progress.setText(total == 1 ? (done == 1 ? "ответ готов" : "нужен ответ") : "отвечено " + done + " из " + total);
            progress.getParent().layout(new Control[] { progress });
        }
        strips.forEach(Canvas::redraw);
    }

    private void submit() {
        Pending value = pending;
        List<Answer> answers = answers();
        if (value == null || answers.isEmpty()) return;
        // Забираем владение формой до продолжения агента: повторное нажатие ничего не отправит.
        show(null);
        value.future.complete(answers);
    }

    private void resize() {
        if (scroll.isDisposed()) return;
        Point size = fields.computeSize(Math.max(100, scroll.viewport().width), SWT.DEFAULT);
        scroll.setMinSize(size);
        // Короткая форма не занимает лишнего места, длинная — не выше 300 пикселей с прокруткой.
        GridData data = (GridData) scroll.getLayoutData();
        int height = Math.min(300, size.y);
        if (data.heightHint != height) {
            data.heightHint = height;
            changed.run();
        }
    }

    /** Значок «?» в круге цвета ссылки. */
    private static void paintBadge(GC gc, Canvas canvas) {
        gc.setBackground(canvas.getParent().getBackground());
        gc.fillRectangle(canvas.getClientArea());
        gc.setAntialias(SWT.ON);
        gc.setBackground(accent(canvas));
        gc.fillOval(0, 0, BADGE, BADGE);
        gc.setForeground(canvas.getDisplay().getSystemColor(SWT.COLOR_WHITE));
        gc.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        Point size = gc.textExtent("?");
        gc.drawText("?", (BADGE - 1 - size.x) / 2, (BADGE - 1 - size.y) / 2, true);
    }

    private static Color accent(Control control) {
        Color color = JFaceResources.getColorRegistry().get(JFacePreferences.HYPERLINK_COLOR);
        return color != null ? color : control.getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND);
    }

    private static Color meta(Control control) {
        Color color = JFaceResources.getColorRegistry().get(JFacePreferences.QUALIFIER_COLOR);
        return color != null ? color : control.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);
    }

    private void visible(boolean value) {
        bar.setVisible(value);
        ((GridData) bar.getLayoutData()).exclude = !value;
        changed.run();
    }

    boolean isShown() { return bar.getVisible(); }
    Button submitButton() { return submit; }
    List<Text> inputs() { return List.copyOf(inputs); }
    List<Button> options() { return List.copyOf(options); }
}
