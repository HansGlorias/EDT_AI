package io.github.hansglorias.edtai.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Кнопка «Написать сообщение коммита» на панели окна коммита EGit (Git
 * Staging): модель смотрит незакоммиченное по скиллу commit-message, ответ
 * виден в чате, а текст из блока кода встаёт в поле сообщения коммита.
 * Зависимости от EGit нет: поле — первое редактируемое {@link StyledText}
 * вида, из которого вызвана команда.
 */
public class CommitMessageHandler extends AbstractHandler {

    public static final String COMMAND_ID = "io.github.hansglorias.edtai.ui.commitMessage";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
        if (page == null) {
            return null;
        }
        StyledText target = messageArea(HandlerUtil.getActivePart(event));
        ChatView view;
        try {
            view = ChatView.freeOnPage(page);
            if (view == null) {
                view = (ChatView) page.showView(ChatView.ID, "commit:" + System.nanoTime(), IWorkbenchPage.VIEW_VISIBLE);
            } else {
                page.activate(view);
            }
        } catch (PartInitException e) {
            throw new ExecutionException("Не удалось открыть EDT AI", e);
        }
        view.requestCommitMessage(message -> {
            if (target == null || target.isDisposed()) {
                return false;
            }
            target.setText(message);
            target.setFocus();
            return true;
        });
        return null;
    }

    /** Поле сообщения коммита: первое редактируемое многострочное поле вида; не вид или нет такого — {@code null}. */
    static StyledText messageArea(IWorkbenchPart part) {
        if (part == null || part instanceof ChatView) {
            return null;
        }
        MPart model = part.getSite().getService(MPart.class);
        return model != null && model.getWidget() instanceof Composite root ? firstEditable(root) : null;
    }

    static StyledText firstEditable(Composite parent) {
        for (Control child : parent.getChildren()) {
            if (child instanceof StyledText text && text.getEditable() && (text.getStyle() & org.eclipse.swt.SWT.MULTI) != 0) {
                return text;
            }
            if (child instanceof Composite composite) {
                StyledText found = firstEditable(composite);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
