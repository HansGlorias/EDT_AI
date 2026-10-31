package io.github.hansglorias.edtai.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * «Объяснить», «Ревью», «Рефакторинг» в подменю «EDT AI» редактора: как
 * «Спросить AI о выделенном» прикрепляет выделенные строки (без выделения —
 * метод под курсором) и сразу отправляет готовый вопрос. Идёт ответ —
 * вопрос встаёт в очередь.
 */
public class CodeActionHandler extends AbstractHandler {

    public static final String EXPLAIN_ID = "io.github.hansglorias.edtai.ui.explainSelection";
    public static final String REVIEW_ID = "io.github.hansglorias.edtai.ui.reviewSelection";
    public static final String REFACTOR_ID = "io.github.hansglorias.edtai.ui.refactorSelection";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        String prompt = switch (event.getCommand().getId()) {
            case EXPLAIN_ID -> ChatView.EXPLAIN_PROMPT;
            case REVIEW_ID -> ChatView.REVIEW_CODE_PROMPT;
            case REFACTOR_ID -> ChatView.REFACTOR_PROMPT;
            default -> throw new ExecutionException("Неизвестная команда " + event.getCommand().getId());
        };
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
        if (page == null) {
            return null;
        }
        ITextEditor editor = EditorAccess.textEditor(page.getActiveEditor());
        AttachedContext context = editor == null ? null : AttachedContext.fromEditor(editor);
        ChatView view;
        try {
            view = (ChatView) page.showView(ChatView.ID);
        } catch (PartInitException e) {
            throw new ExecutionException("Не удалось открыть EDT AI", e);
        }
        if (context == null) {
            view.showNotice(editor == null ? "Откройте модуль в редакторе и выделите код" : "Можно только файл из проекта рабочей области");
        } else {
            view.askAboutCode(context, prompt);
        }
        return null;
    }
}
