package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.EchoChatProvider;
import io.github.hansglorias.edtai.core.settings.AiSettings;

/**
 * Проверка в живом workbench: вид открывается, эхо отвечает, страница
 * настроек создаётся.
 */
class ChatViewSmokeTest {

    private static final long TIMEOUT_MS = 10_000;

    private IWorkbenchPage page;

    @BeforeEach
    void setUp() {
        page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    }

    @AfterEach
    void tearDown() {
        IViewPart view = page.findView(ChatView.ID);
        if (view != null) {
            page.hideView(view);
        }
    }

    @Test
    void viewEchoesMessage() throws Exception {
        ChatView view = assertInstanceOf(ChatView.class, page.showView(ChatView.ID));
        assertTrue(view.status().getText().contains("эхо"), view.status().getText());

        view.input().setText("привет из теста");
        view.send();

        assertEquals("", view.input().getText());
        waitUntil(() -> view.history().getText().contains(EchoChatProvider.PREFIX + "привет из теста"));
        assertTrue(view.history().getText().contains("Вы"));
        assertTrue(view.history().getText().contains("Ассистент"));
        waitUntil(() -> !view.session().isBusy());

        view.session().clear();
        waitUntil(() -> view.history().getText().isEmpty());
    }

    @Test
    void blankMessageIsNotSent() throws Exception {
        ChatView view = (ChatView) page.showView(ChatView.ID);

        view.input().setText("   ");
        view.send();

        assertTrue(view.session().messages().isEmpty());
    }

    @Test
    void preferencePageOpens() {
        PreferenceDialog dialog = PreferencesUtil.createPreferenceDialogOn(
                page.getWorkbenchWindow().getShell(), AiPreferencePage.ID, null, null);
        assertNotNull(dialog);
        dialog.setBlockOnOpen(false);
        dialog.open();
        try {
            AiPreferencePage preferencePage = assertInstanceOf(AiPreferencePage.class, dialog.getSelectedPage());
            assertNotNull(preferencePage.getControl());
            assertTrue(preferencePage.isValid());
            assertEquals(AiSettings.defaults(), preferencePage.current());
        } finally {
            dialog.close();
        }
    }

    private static void waitUntil(BooleanSupplier condition) {
        Display display = Display.getCurrent();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Не дождались условия за " + TIMEOUT_MS + " мс");
            }
            if (!display.readAndDispatch()) {
                display.timerExec(20, () -> { });
                display.sleep();
            }
        }
    }
}
