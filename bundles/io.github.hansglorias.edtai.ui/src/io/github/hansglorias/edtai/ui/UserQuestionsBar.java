package io.github.hansglorias.edtai.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import io.github.hansglorias.edtai.core.tools.UserQuestions;
import io.github.hansglorias.edtai.core.tools.UserQuestions.Answer;
import io.github.hansglorias.edtai.core.tools.UserQuestions.Request;

/** Одна форма для открытого чата. Черновики принадлежат Pending своего Live. */
final class UserQuestionsBar {
    static final class Pending {
        final Request request;
        final CompletableFuture<List<Answer>> future = new CompletableFuture<>();
        final Map<String, String> texts = new HashMap<>();
        final Map<String, String> selected = new HashMap<>();

        Pending(Request request) { this.request = request; }
    }

    private final Composite bar;
    private final ScrolledComposite scroll;
    private final Composite fields;
    private final Button submit;
    private final Runnable changed;
    private Pending pending;
    private final List<Text> inputs = new ArrayList<>();
    private final List<Button> options = new ArrayList<>();

    UserQuestionsBar(Composite parent, Runnable changed) {
        this.changed = changed;
        bar = new Composite(parent, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(bar);
        GridLayoutFactory.swtDefaults().margins(6, 6).applyTo(bar);
        Label title = new Label(bar, SWT.WRAP);
        title.setText("Уточнения модели — ответьте на каждый вопрос. Свой текст заменяет выбранный вариант.");
        GridDataFactory.fillDefaults().grab(true, false).hint(200, SWT.DEFAULT).applyTo(title);
        scroll = new ScrolledComposite(bar, SWT.V_SCROLL);
        GridDataFactory.fillDefaults().grab(true, false).hint(200, 260).applyTo(scroll);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        fields = new Composite(scroll, SWT.NONE);
        GridLayoutFactory.fillDefaults().applyTo(fields);
        scroll.setContent(fields);
        scroll.addListener(SWT.Resize, event -> resize());
        Composite actions = new Composite(bar, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(2).applyTo(actions);
        submit = new Button(actions, SWT.PUSH);
        submit.setText("Отправить ответы");
        submit.addListener(SWT.Selection, event -> submit());
        Button cancel = new Button(actions, SWT.PUSH);
        cancel.setText("Остановить");
        cancel.addListener(SWT.Selection, event -> { if (pending != null) pending.future.cancel(true); });
        visible(false);
    }

    void show(Pending value) {
        pending = null;
        for (Control child : fields.getChildren()) child.dispose();
        inputs.clear();
        options.clear();
        if (value == null || value.future.isDone()) {
            visible(false);
            return;
        }
        pending = value;
        int number = 0;
        for (var question : value.request.questions()) {
            Composite group = new Composite(fields, SWT.NONE);
            GridDataFactory.fillDefaults().grab(true, false).applyTo(group);
            GridLayoutFactory.fillDefaults().applyTo(group);
            Label label = new Label(group, SWT.WRAP);
            label.setText(++number + ". " + question.text());
            GridDataFactory.fillDefaults().grab(true, false).hint(200, SWT.DEFAULT).applyTo(label);
            for (var option : question.options()) {
                Button radio = new Button(group, SWT.RADIO | SWT.WRAP);
                radio.setText(option.text());
                GridDataFactory.fillDefaults().grab(true, false).hint(200, SWT.DEFAULT).applyTo(radio);
                radio.setSelection(option.id().equals(value.selected.get(question.id())));
                radio.addListener(SWT.Selection, event -> {
                    if (pending == value && radio.getSelection()) {
                        value.selected.put(question.id(), option.id());
                        updateSubmit();
                    }
                });
                options.add(radio);
            }
            Label own = new Label(group, SWT.NONE);
            own.setText(question.options().isEmpty() ? "Ответ:" : "Свой ответ (вместо варианта):");
            Text text = new Text(group, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
            text.setTextLimit(4000);
            text.setMessage("Свой ответ");
            GridDataFactory.fillDefaults().grab(true, false).hint(200, text.getLineHeight() * 2).applyTo(text);
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

    private void updateSubmit() { submit.setEnabled(!answers().isEmpty()); }

    private void submit() {
        Pending value = pending;
        List<Answer> answers = answers();
        if (value == null || answers.isEmpty()) return;
        // Забираем владение формой до продолжения агента: повторное нажатие ничего не отправит.
        show(null);
        value.future.complete(answers);
    }

    private void resize() {
        if (!scroll.isDisposed()) scroll.setMinSize(fields.computeSize(Math.max(100, scroll.getClientArea().width), SWT.DEFAULT));
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
