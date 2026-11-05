package io.github.hansglorias.edtai.ui;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import io.github.hansglorias.edtai.core.chat.ChatAnalysisExport.Feedback;

/** Явный выбор содержимого перед сохранением; разрешение не запоминается между экспортами. */
final class AnalysisExportDialog extends TitleAreaDialog {
    record Options(boolean includeContent, Feedback feedback) { }

    private Button include;
    private Text expected;
    private Text actual;
    private Text problem;
    private Options result;

    AnalysisExportDialog(Shell parent) {
        super(parent);
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Выгрузка выбранного чата для разбора");
        setMessage("ZIP сохраняется только на ваш компьютер. Автоматической отправки нет.");
        Composite area = (Composite) super.createDialogArea(parent);
        Composite body = new Composite(area, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(body);
        GridLayoutFactory.swtDefaults().margins(12, 8).spacing(8, 8).applyTo(body);
        Label description = new Label(body, SWT.WRAP);
        description.setText("По умолчанию — счётчики, состояния и текущие настройки модели, без текста разговора и кода. "
                + "Полных аргументов, результатов инструментов и метрик запросов в истории нет; пробелы будут отмечены в архиве.");
        GridDataFactory.fillDefaults().grab(true, false).hint(540, SWT.DEFAULT).applyTo(description);
        include = new Button(body, SWT.CHECK);
        include.setText("Включить разговор, код, подробности ошибок и отзыв");
        include.setSelection(false);
        Label warning = new Label(body, SWT.WRAP);
        warning.setText("Содержимое может быть конфиденциальным. Известные секреты, адреса и поля авторизации очищаются, "
                + "но полная анонимизация произвольного текста не гарантируется. Перед передачей другому человеку или ИИ проверьте архив.");
        GridDataFactory.fillDefaults().grab(true, false).hint(540, SWT.DEFAULT).applyTo(warning);
        expected = field(body, "Ожидал");
        actual = field(body, "Получил");
        problem = field(body, "Что не так");
        enableFeedback();
        include.addListener(SWT.Selection, event -> enableFeedback());
        return area;
    }

    private static Text field(Composite body, String label) {
        new Label(body, SWT.NONE).setText(label + " (необязательно)");
        Text text = new Text(body, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        text.setTextLimit(4000);
        GridDataFactory.fillDefaults().grab(true, true).hint(540, 50).applyTo(text);
        return text;
    }

    private void enableFeedback() {
        expected.setEnabled(include.getSelection());
        actual.setEnabled(include.getSelection());
        problem.setEnabled(include.getSelection());
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Выбрать файл…", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Отмена", false);
    }

    @Override
    protected Point getInitialSize() { return new Point(660, 650); }

    @Override
    protected void okPressed() {
        result = new Options(include.getSelection(), include.getSelection()
                ? new Feedback(expected.getText(), actual.getText(), problem.getText()) : Feedback.empty());
        super.okPressed();
    }

    Options result() { return result; }

    // Для UI-проверки выбора и конфиденциального значения по умолчанию.
    Button includeButton() { return include; }
    Text expectedField() { return expected; }
}
