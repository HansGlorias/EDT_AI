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
import org.eclipse.swt.custom.ScrolledComposite;
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
 * Боковая панель специалистов последнего ответа: карточка на запуск — значок
 * состояния, роль, задача, вызовы, время и токены. Щелчок по строке раскрывает шаги и отчёт.
 * Сворачивается в узкую полосу, раскрывается той же кнопкой, не занимая место над вводом.
 */
final class SubagentsPanel {

    /** Сколько последних шагов специалиста видно в раскрытой строке. */
    private static final int STEPS = 12;
    private static final int ICON = 14;

    private final Composite bar;
    private final Label title;
    private final Composite header;
    private final Chip toggle;
    private final Label count;
    private final ScrolledComposite scroll;
    private final Composite rows;
    private final Runnable changed;
    /** Что показано: при тех же запусках меняются только метрики, без пересоздания строк. */
    private List<Run> shown = List.of();
    private final List<Label> metrics = new ArrayList<>();
    private final List<Canvas> icons = new ArrayList<>();
    /** Раскрытые запуски по id. */
    private final Set<String> expanded = new HashSet<>();
    private boolean collapsed;
    private Object answer;

    SubagentsPanel(Composite parent, Runnable changed) {
        this.changed = changed;
        bar = new Composite(parent, SWT.NONE);
        GridDataFactory.fillDefaults().grab(false, true).hint(240, SWT.DEFAULT).applyTo(bar);
        GridLayoutFactory.fillDefaults().margins(6, 8).spacing(0, 8).applyTo(bar);
        bar.addListener(SWT.Paint, event -> {
            event.gc.setForeground(meta(bar));
            event.gc.setAlpha(40);
            event.gc.drawLine(0, 0, 0, bar.getSize().y);
        });
        header = new Composite(bar, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(header);
        GridLayoutFactory.fillDefaults().numColumns(2).applyTo(header);
        title = new Label(header, SWT.WRAP);
        title.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        GridDataFactory.fillDefaults().grab(true, false).hint(150, SWT.DEFAULT).applyTo(title);
        toggle = new Chip(header, false);
        toggle.setText("›");
        toggle.setToolTipText("Свернуть специалистов");
        toggle.addListener(SWT.Selection, event -> setCollapsed(!collapsed));
        count = new Label(bar, SWT.CENTER);
        count.setForeground(meta(bar));
        GridDataFactory.fillDefaults().grab(true, false).exclude(true).applyTo(count);
        count.setVisible(false);
        scroll = new ScrolledComposite(bar, SWT.V_SCROLL);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(scroll);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);
        rows = new Composite(scroll, SWT.NONE);
        GridLayoutFactory.fillDefaults().spacing(0, 12).applyTo(rows);
        scroll.setContent(rows);
        scroll.addListener(SWT.Resize, event -> resize());
        visible(false);
    }

    /**
     * Показывает специалистов ответа; пустой список — панель скрыта.
     *
     * @param owner ответ, которому они принадлежат: раскрытые карточки не смешиваются между чатами
     */
    void show(Object owner, List<Run> runs) {
        boolean newAnswer = !Objects.equals(answer, owner);
        answer = owner;
        if (newAnswer) expanded.clear();
        if (runs.isEmpty()) {
            if (!shown.isEmpty()) rebuild(List.of());
            shown = List.of();
            visible(false);
            return;
        }
        if (newAnswer || !sameShape(shown, runs)) {
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
        String heading = title(runs);
        if (!heading.equals(title.getText())) {
            title.setText(heading);
            bar.layout(true, true);
        }
        toggle.setToolTipText((collapsed ? "Показать" : "Свернуть") + " специалистов\n" + heading);
        count.setText(Integer.toString(runs.size()));
        count.setToolTipText(heading);
        visible(true);
        resize();
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
        rows.layout(true, true);
        resize();
        changed.run();
    }

    private void row(Run run) {
        Composite card = new Composite(rows, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(card);
        GridLayoutFactory.fillDefaults().spacing(0, 4).applyTo(card);
        Composite line = new Composite(card, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(line);
        GridLayoutFactory.fillDefaults().numColumns(3).spacing(8, 0).applyTo(line);
        Canvas icon = new Canvas(line, SWT.DOUBLE_BUFFERED);
        icon.addListener(SWT.Paint, event -> paintState(event.gc, (Canvas) event.widget, run.state()));
        GridDataFactory.swtDefaults().hint(ICON, ICON).align(SWT.CENTER, SWT.CENTER).applyTo(icon);
        icon.setToolTipText(run.state().label());
        icons.add(icon);
        boolean open = expanded.contains(run.id());
        Label role = new Label(line, SWT.NONE);
        role.setText((open ? "▾ " : "▸ ") + run.role());
        role.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        GridDataFactory.fillDefaults().grab(true, false).applyTo(role);
        Label state = new Label(line, SWT.NONE);
        state.setText(run.state().label());
        state.setForeground(meta(line));
        Label task = new Label(card, SWT.WRAP);
        task.setText(run.task());
        task.setToolTipText(run.task());
        GridDataFactory.fillDefaults().grab(true, false).hint(60, SWT.DEFAULT).applyTo(task);
        Label numbers = new Label(card, SWT.WRAP);
        numbers.setText(run.metrics());
        numbers.setForeground(meta(line));
        GridDataFactory.fillDefaults().grab(true, false).hint(100, SWT.DEFAULT).applyTo(numbers);
        metrics.add(numbers);
        for (Control control : new Control[] { line, icon, role, state, task, numbers }) {
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
        GridDataFactory.fillDefaults().grab(true, false).indent(6, 0).applyTo(box);
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
        Label label = new Label(parent, SWT.WRAP);
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

    private void resize() {
        if (scroll.isDisposed() || collapsed) return;
        int height = rows.computeSize(Math.max(1, scroll.getClientArea().width), SWT.DEFAULT).y;
        if (scroll.getMinHeight() != height) scroll.setMinHeight(height);
    }

    void setCollapsed(boolean value) {
        collapsed = value;
        title.setVisible(!value);
        ((GridData) title.getLayoutData()).exclude = value;
        scroll.setVisible(!value);
        ((GridData) scroll.getLayoutData()).exclude = value;
        count.setVisible(value);
        ((GridData) count.getLayoutData()).exclude = !value;
        ((GridData) bar.getLayoutData()).widthHint = value ? 32 : 240;
        toggle.setText(value ? "‹" : "›");
        toggle.setToolTipText((value ? "Показать" : "Свернуть") + " специалистов\n" + title.getText());
        changed.run();
        resize();
    }

    boolean isCollapsed() { return collapsed; }
    Composite control() { return bar; }

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
