package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.Test;

/** Кнопка «Написать сообщение коммита» в окне коммита EGit. */
class ChatCommitTest extends AbstractChatTest {

    @Test
    void answerGoesToCommitMessageField() throws Exception {
        answering(List.of("Вот текст:\n\n```\nдобавил очередь сообщений\n```\n"));
        ChatView view = showView();
        List<String> inserted = new ArrayList<>();

        view.requestCommitMessage(inserted::add);
        waitUntil(() -> !view.session().isBusy() && !inserted.isEmpty(), () -> view.history().getText());

        assertEquals(List.of("добавил очередь сообщений"), inserted);
        assertEquals(ChatView.COMMIT_PROMPT, view.session().messages().get(0).text());
        assertTrue(ChatView.COMMIT_PROMPT.contains("commit-message"));
        assertEquals("Сообщение коммита — в окне коммита", view.status().getText());

        ask(view, "спасибо");
        assertEquals(1, inserted.size(), "следующие ответы в окно коммита не идут");
    }

    @Test
    void messageFieldIsFirstEditableMultiLineText() {
        Shell shell = new Shell(page.getWorkbenchWindow().getShell());
        try {
            Composite form = new Composite(shell, SWT.NONE);
            new Text(form, SWT.SINGLE);
            StyledText readOnly = new StyledText(form, SWT.MULTI | SWT.READ_ONLY);
            readOnly.setEditable(false);
            Composite area = new Composite(form, SWT.NONE);
            StyledText message = new StyledText(area, SWT.MULTI | SWT.WRAP);
            new StyledText(form, SWT.MULTI);

            assertSame(message, CommitMessageHandler.firstEditable(shell));
            assertNull(CommitMessageHandler.firstEditable(new Composite(shell, SWT.NONE)));
        } finally {
            shell.dispose();
        }
    }
}
