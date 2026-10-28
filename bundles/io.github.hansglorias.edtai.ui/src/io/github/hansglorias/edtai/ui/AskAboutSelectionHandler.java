package io.github.hansglorias.edtai.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * «Спросить AI о выделенном» (Ctrl+Alt+A и контекстное меню редактора):
 * прикрепляет к вопросу выделенные строки, без выделения — метод под
 * курсором, вне метода — модуль целиком. Ничего не отправляет: вопрос пишет
 * пользователь.
 */
public class AskAboutSelectionHandler extends AbstractHandler {

    public static final String COMMAND_ID = "io.github.hansglorias.edtai.ui.askAboutSelection";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
        if (page == null) {
            return null;
        }
        // Активный или последний активный редактор: команду могут вызвать и из вида чата.
        ITextEditor editor = EditorAccess.textEditor(page.getActiveEditor());
        AttachedContext context = editor == null ? null : AttachedContext.fromEditor(editor);
        ChatView view;
        try {
            view = (ChatView) page.showView(ChatView.ID);
        } catch (PartInitException e) {
            throw new ExecutionException("Не удалось открыть вид AI Chat", e);
        }
        if (editor == null) {
            view.showNotice("Откройте модуль в редакторе и выделите код, о котором спросить");
        } else if (context == null) {
            view.showNotice("Прикрепить можно только файл из проекта рабочей области");
        } else {
            view.attach(context);
        }
        return null;
    }
}
