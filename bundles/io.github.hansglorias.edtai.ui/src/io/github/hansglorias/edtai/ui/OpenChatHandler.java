package io.github.hansglorias.edtai.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * «EDT AI» в строке состояния внизу окна (рядом с виджетами других
 * плагинов): открывает вид чата и ставит курсор в поле ввода.
 */
public class OpenChatHandler extends AbstractHandler {

    public static final String COMMAND_ID = "io.github.hansglorias.edtai.ui.openChat";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
        if (page == null) {
            return null;
        }
        try {
            ChatView view = (ChatView) page.showView(ChatView.ID);
            view.setFocus();
        } catch (PartInitException e) {
            throw new ExecutionException("Не удалось открыть EDT AI", e);
        }
        return null;
    }
}
