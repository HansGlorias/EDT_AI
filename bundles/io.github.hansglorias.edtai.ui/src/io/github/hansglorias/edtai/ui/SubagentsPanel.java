package io.github.hansglorias.edtai.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.JFacePreferences;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Text;

import io.github.hansglorias.edtai.core.chat.Subagents;
import io.github.hansglorias.edtai.core.chat.Subagents.Run;
import io.github.hansglorias.edtai.core.chat.ToolUse;

/**
 * Панель специалистов (субагентов) последнего ответа над полем ввода: по строке на запуск — значок
 * состояния, роль, задача, вызовы, время и токены. Щелчок по строке раскрывает шаги и отчёт.
 * «Скрыть» убирает панель до следующего ответа со специалистами.
 */
final class SubagentsPanel {

    static final String HIDE = "скрыть";
    /** Сколько последних шагов специалиста видно в раскрытой строке. */
    private static final int STEPS = 12;
    private static final int ICON = 14;

    private final Composite bar;
    private final Label title;
    private final Composite rows;
    private final Runnable changed;
    /** Что показано: при тех же запусках меняются только метрики, без пересоздания строк. */
    private List<Run> shown = List.of();
    private final List<Label> metrics = new ArrayList<>();
    private final List<Canvas> icons = new ArrayList<>();
    /** Раскрытые запуски по id. */
    private final Set<String> expanded = new HashSet<>();
    /** Ответ, для которого панель скрыли: до ответа с другими специалистами она не появляется. */
    private Object hiddenFor;
    private Object answer;

    SubagentsPanel(Composite parent, Runnable changed) {
        this.changed = changed;
        bar = new Composite(parent, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(bar);
        GridLayoutFactory.swtDefaults().margins(6, 4).spacing(0, 2).applyTo(bar);
        Composite header = new Composite(bar, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(header);
        GridLayoutFactory.fillDefaults().numColumns(2).applyTo(header);
        title = new Label(header, SWT.NONE);
        title.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        GridDataFactory.fillDefaults().grab(true, false).applyTo(title);
        Link hide = new Link(header, SWT.NONE);
        hide.setText("<a>" + HIDE + "</a>");
        hide.setToolTipText("Скрыть панель до следующего ответа со специалистами");
        hide.addListener(SWT.Selection, event -> {
            hiddenFor = answer;
            visible(false);
        });
        rows = new Composite(bar, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(rows);
        GridLayoutFactory.fillDefaults().spacing(0, 2).applyTo(rows);
        visible(false);
    }

    /**
     * Показывает специалистов ответа; пустой список — панель скрыта.
     *
     * @param owner ответ, которому они принадлежат: по нему помнится «скрыть»
     */
    void show(Object owner, List<Run> runs) {
        answer = owner;
        if (runs.isEmpty() || owner != null && Objects.equals(owner, hiddenFor)) {
            shown = List.of();
            visible(false);
            return;
        }
        if (!sameShape(shown, runs)) {
            rebuild(runs);
        } else {
            for (int i = 0; i < runs.size(); i++) {
                String text = runs.get(i).metrics();
                if (!metrics.get(i).getText().equals(text)) {
                    metrics.get(i).setText(text);
                    metrics.get(i).getParent().layout(new Control[] { metrics.get(i) });
                }
            }
        }
        shown = runs;
        title.setText(title(runs));
        visible(true);
    }

    /** «Специалисты · 2 — 1 работает, 1 готово». */
    static String title(List<Run> runs) {
        StringBuilder text = new StringBuilder("Специалисты · " + runs.size());
        List<String> states = new ArrayList<>();
        for (Subagents.State state : Subagents.State.values()) {
            long count = runs.stream().filter(run -> run.state() == state).count();
            if (count > 0) {
                states.add(count + " " + state.label());
            }
        }
        return text.append(" — ").append(String.join(", ", states)).toString();
    }

    /** Те же запуски в тех же состояниях, шагах и отчётах: отличаются разве что метрики. */
    private static boolean sameShape(List<Run> before, List<Run> after) {
        if (before.size() != after.size()) {
            return false;
        }
        for (int i = 0; i < before.size(); i++) {
            Run a = before.get(i);
            Run b = after.get(i);
            if (!a.id().equals(b.id()) || a.state() != b.state() || !a.steps().equals(b.steps()) || !a.report().equals(b.report())) {
                return false;
            }
        }
        return true;
    }

    private void rebuild(List<Run> runs) {
        for (Control child : rows.getChildren()) {
            child.dispose();
        }
        metrics.clear();
        icons.clear();
        for (Run run : runs) {
            row(run);
        }
        changed.run();
    }

    private void row(Run run) {
        Composite line = new Composite(rows, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(line);
        GridLayoutFactory.fillDefaults().numColumns(5).spacing(6, 0).applyTo(line);
        Canvas icon = new Canvas(line, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        GridDataFactory.swtDefaults().hint(ICON, ICON).align(SWT.CENTER, SWT.CENTER).applyTo(icon);
        icon.addListener(SWT.Paint, event -> paintState(event.gc, icon, run.state()));
        icon.setToolTipText(run.state().label());
        icons.add(icon);
        boolean open = expanded.contains(run.id());
        Label arrow = new Label(line, SWT.NONE);
        arrow.setText(open ? "▾" : "▸");
        arrow.setForeground(meta(line));
        Label role = new Label(line, SWT.NONE);
        role.setText(run.role());
        role.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        Label task = new Label(line, SWT.NONE);
        task.setText(run.task());
        task.setToolTipText(run.task());
        GridDataFactory.fillDefaults().grab(true, false).hint(60, SWT.DEFAULT).applyTo(task);
        Label numbers = new Label(line, SWT.NONE);
        numbers.setText(run.metrics());
        numbers.setForeground(meta(line));
        metrics.add(numbers);
        for (Control control : new Control[] { line, arrow, role, task, numbers }) {
            control.setCursor(line.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
            control.addListener(SWT.MouseUp, event -> toggle(run));
        }
        if (open) {
            details(run);
        }
    }

    private void toggle(Run run) {
        if (!expanded.remove(run.id())) {
            expanded.add(run.id());
        }
        rebuild(shown);
    }

    /** Шаги специалиста и его отчёт под строкой. */
    private void details(Run run) {
        Composite box = new Composite(rows, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).indent(ICON + 18, 0).applyTo(box);
        GridLayoutFactory.fillDefaults().spacing(0, 1).applyTo(box);
        List<ToolUse> steps = run.steps();
        if (steps.isEmpty()) {
            label(box, run.state() == Subagents.State.RUNNING ? "Готовит первый запрос…" : "Вызовов не было", false);
        }
        if (steps.size() > STEPS) {
            label(box, "… ещё " + (steps.size() - STEPS) + " раньше", false);
        }
        for (ToolUse step : steps.subList(Math.max(0, steps.size() - STEPS), steps.size())) {
            Label label = label(box, (step.status() == ToolUse.Status.FAILED ? "✕ " : "→ ") + Run.step(step), false);
            if (step.status() == ToolUse.Status.FAILED) {
                label.setForeground(error(box));
            }
        }
        if (!run.report().isBlank()) {
            Composite head = new Composite(box, SWT.NONE);
            GridDataFactory.fillDefaults().grab(true, false).applyTo(head);
            GridLayoutFactory.fillDefaults().numColumns(2).applyTo(head);
            Label caption = new Label(head, SWT.NONE);
            caption.setText(run.state() == Subagents.State.FAILED ? "Причина" : "Отчёт");
            caption.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
            GridDataFactory.fillDefaults().grab(true, false).applyTo(caption);
            Link copy = new Link(head, SWT.NONE);
            copy.setText("<a>копировать</a>");
            copy.addListener(SWT.Selection, event -> copy(box, run.report()));
            Text report = new Text(box, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
            report.setText(run.report());
            int lines = (int) Math.min(8, Math.max(2, run.report().lines().count()));
            GridDataFactory.fillDefaults().grab(true, false).hint(100, report.getLineHeight() * lines).applyTo(report);
        }
    }

    private Label label(Composite parent, String text, boolean bold) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
        label.setForeground(meta(parent));
        if (bold) {
            label.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        }
        GridDataFactory.fillDefaults().grab(true, false).hint(60, SWT.DEFAULT).applyTo(label);
        return label;
    }

    private static void copy(Control control, String text) {
        Clipboard clipboard = new Clipboard(control.getDisplay());
        try {
            clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
    }

    /** Значок состояния: кольцо — работает, круг с галочкой — готово, с крестом — ошибка. */
    private static void paintState(GC gc, Canvas canvas, Subagents.State state) {
        gc.setBackground(canvas.getParent().getBackground());
        gc.fillRectangle(canvas.getClientArea());
        gc.setAntialias(SWT.ON);
        Color color = new Color(switch (state) {
            case RUNNING -> new RGB(52, 120, 220);
            case DONE -> new RGB(46, 160, 67);
            case PARTIAL -> new RGB(214, 140, 20);
            case FAILED -> new RGB(210, 60, 50);
            case STOPPED -> new RGB(140, 140, 140);
        });
        int size = ICON - 2;
        gc.setLineWidth(2);
        if (state == Subagents.State.RUNNING) {
            gc.setForeground(color);
            gc.drawOval(1, 1, size - 1, size - 1);
            gc.setBackground(color);
            gc.fillOval(size / 2 - 1, size / 2 - 1, 4, 4);
            return;
        }
        gc.setBackground(color);
        gc.fillOval(0, 0, size + 1, size + 1);
        gc.setForeground(canvas.getDisplay().getSystemColor(SWT.COLOR_WHITE));
        switch (state) {
            case DONE -> gc.drawPolyline(new int[] { 3, 7, 6, 10, 11, 4 });
            case PARTIAL -> gc.drawLine(3, 7, 10, 7);
            default -> {
                gc.drawLine(4, 4, 9, 9);
                gc.drawLine(9, 4, 4, 9);
            }
        }
    }

    private static Color meta(Control control) {
        Color color = JFaceResources.getColorRegistry().get(JFacePreferences.QUALIFIER_COLOR);
        return color != null ? color : control.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);
    }

    private static Color error(Control control) {
        Color color = JFaceResources.getColorRegistry().get(JFacePreferences.ERROR_COLOR);
        return color != null ? color : control.getDisplay().getSystemColor(SWT.COLOR_RED);
    }

    private void visible(boolean value) {
        if (bar.getVisible() == value && ((GridData) bar.getLayoutData()).exclude == !value) {
            return;
        }
        bar.setVisible(value);
        ((GridData) bar.getLayoutData()).exclude = !value;
        changed.run();
    }

    boolean isShown() {
        return bar.getVisible();
    }

    /** Заголовок панели: для тестов. */
    String titleText() {
        return title.getText();
    }

    /** Раскрыть запуск, как щелчком по строке: для тестов. */
    void expand(String id) {
        if (expanded.add(id)) {
            rebuild(shown);
        }
    }
}
