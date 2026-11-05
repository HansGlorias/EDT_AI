package io.github.hansglorias.edtai.ui;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import io.github.hansglorias.edtai.core.skills.Skill;
import io.github.hansglorias.edtai.core.skills.SkillLibrary;

/**
 * Окно команды {@code /skills}: все скиллы с галочками «включён», откуда
 * каждый, и текст выбранного внизу. Выключенный модель не видит, командой
 * {@code /имя} его не вызвать.
 */
class SkillsDialog extends TitleAreaDialog {

    private static final int FOLDER_ID = IDialogConstants.CLIENT_ID + 1;

    private final List<Skill> skills;
    private final Set<String> disabled;
    private final Runnable openFolder;
    private Table table;
    private Text details;
    private Set<String> result;

    SkillsDialog(Shell parent, List<Skill> skills, Set<String> disabled, Runnable openFolder) {
        super(parent);
        this.skills = List.copyOf(skills);
        this.disabled = new TreeSet<>(disabled);
        this.openFolder = openFolder;
        setShellStyle(getShellStyle() | SWT.RESIZE | SWT.MAX);
    }

    /** Открывает окно; выключенные скиллы после «Готово» или {@code null}, если окно закрыли. */
    Set<String> choose() {
        return open() == OK ? result : null;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Скиллы");
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        setTitle("Скиллы");
        setMessage("Галочка — скилл включён: модель берёт его, когда задача подходит, и его можно вызвать командой /имя. Свои и"
                + " скиллы проекта (" + SkillLibrary.PROJECT_DIRECTORY + ", общие для команды через git) заменяют встроенные"
                + " с тем же именем.");

        Composite body = new Composite(area, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(body);
        GridLayoutFactory.swtDefaults().margins(10, 10).spacing(0, 8).applyTo(body);

        table = new Table(body, SWT.CHECK | SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        GridDataFactory.fillDefaults().grab(true, true).hint(760, 300).applyTo(table);
        column("Скилл", 170);
        column("Откуда", 90);
        column("Когда применять", 480);
        for (Skill skill : skills) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(new String[] { skill.command(), skill.source().label(), skill.description() });
            item.setChecked(!disabled.contains(skill.name()));
            item.setData(skill);
        }
        table.addListener(SWT.Selection, event -> {
            if (event.item instanceof TableItem item && item.getData() instanceof Skill skill) {
                if (event.detail == SWT.CHECK) {
                    if (item.getChecked()) {
                        disabled.remove(skill.name());
                    } else {
                        disabled.add(skill.name());
                    }
                }
                show(skill);
            }
        });

        details = new Text(body, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        GridDataFactory.fillDefaults().grab(true, false).hint(760, 180).applyTo(details);
        if (!skills.isEmpty()) {
            table.select(0);
            show(skills.get(0));
        }
        return area;
    }

    private void column(String title, int width) {
        TableColumn column = new TableColumn(table, SWT.LEFT);
        column.setText(title);
        column.setWidth(width);
    }

    private void show(Skill skill) {
        String where = skill.source() == Skill.Source.BUILT_IN ? "встроенный" : skill.location();
        details.setText(skill.command() + " — " + where + "\n\n" + skill.body());
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, FOLDER_ID, "Открыть папку своих…", false);
        createButton(parent, IDialogConstants.OK_ID, "Готово", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Отмена", false);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == FOLDER_ID) {
            openFolder.run();
        } else {
            super.buttonPressed(buttonId);
        }
    }

    @Override
    protected void okPressed() {
        result = Set.copyOf(disabled);
        super.okPressed();
    }

    // ---- для тестов ----

    Table table() {
        return table;
    }

    Text details() {
        return details;
    }
}
