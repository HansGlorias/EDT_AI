package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.settings.SettingsStore;

/**
 * Вид чата и страница настроек в живом workbench против фейкового сервера
 * модели. Снимки состояний — в {@code target/screenshots}.
 */
class ChatViewTest {

    private static final long TIMEOUT_MS = 15_000;
    private static final String QUESTION = "Как получить текущую дату на сервере?";
    private static final List<String> ANSWER = List.of(
            "На сервере используйте ", "`ТекущаяДатаСеанса()`", " — она учитывает часовой пояс сеанса.\n\n",
            "```bsl\n", "Дата = ТекущаяДатаСеанса();\n", "```\n\n",
            "`ТекущаяДата()` возвращает время компьютера сервера, ", "её лучше не использовать.");

    private IWorkbenchPage page;
    private FakeModelServer server;
    /** Ошибки, попавшие в лог Eclipse во время теста: например, исключения в цикле событий UI. */
    private final List<IStatus> loggedErrors = new CopyOnWriteArrayList<>();
    private final ILogListener logListener = (status, plugin) -> {
        if (status.matches(IStatus.ERROR)) {
            loggedErrors.add(status);
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        page.getWorkbenchWindow().getShell().setBounds(0, 0, 720, 780);
        SettingsStore.workspace().save(AiSettings.defaults());
        Platform.addLogListener(logListener);
    }

    @AfterEach
    void tearDown() throws Exception {
        IViewPart view = page.findView(ChatView.ID);
        if (view != null) {
            page.hideView(view);
        }
        if (server != null) {
            server.close();
        }
        SettingsStore.workspace().save(AiSettings.defaults());
        Platform.removeLogListener(logListener);
        assertEquals(List.of(), loggedErrors.stream().map(ChatViewTest::describe).toList());
    }

    @Test
    void notConfiguredShowsHint() throws Exception {
        ChatView view = showView();

        assertTrue(view.isEmptyStateShown());
        assertEquals("Модель не настроена", view.status().getText());
        assertFalse(view.sendButton().isEnabled());
        Screenshots.capture(view.control(), "chat-not-configured");
    }

    @Test
    void streamsAnswerFromModel() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            response.start(200, "text/event-stream");
            for (int i = 0; i < ANSWER.size(); i++) {
                if (i == 4) {
                    release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                }
                response.write(openAiPart(ANSWER.get(i)));
                Thread.sleep(30);
            }
            response.write("data: [DONE]\n\n");
            response.end();
        });
        configure(server);
        ChatView view = showView();
        waitUntil(() -> view.status().getText().equals("test-model · localhost"));
        assertTrue(view.isEmptyStateShown());
        Screenshots.capture(view.control(), "chat-empty");

        view.input().setText(QUESTION);
        view.send();

        assertEquals("", view.input().getText());
        waitUntil(() -> view.history().getText().contains("часовой пояс") && ChatView.STOP.equals(view.sendButton().getText()));
        assertFalse(view.isEmptyStateShown());
        assertTrue(view.history().getText().contains("пишет…"), view.history().getText());
        assertTrue(view.status().getText().endsWith("генерирует ответ…"), view.status().getText());
        Screenshots.capture(view.control(), "chat-streaming");

        release.countDown();
        waitUntil(() -> !view.session().isBusy() && view.history().getText().endsWith("её лучше не использовать."));
        waitUntil(() -> ChatView.SEND.equals(view.sendButton().getText()));
        String text = view.history().getText();
        assertTrue(text.startsWith("Вы  "), text);
        assertTrue(text.contains(QUESTION));
        assertTrue(text.contains("Ассистент  "));
        assertTrue(text.contains(String.join("", ANSWER)));
        assertFalse(text.contains("пишет…"));
        assertTrue(server.requests().get(0).body().contains(QUESTION));
        Screenshots.capture(view.control(), "chat-answer");
    }

    @Test
    void followsAnswerUnlessUserScrolledUp() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            response.start(200, "text/event-stream");
            for (int i = 0; i < 160; i++) {
                if (i == 80) {
                    release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                }
                response.write(openAiPart("строка " + i + "\n"));
            }
            response.write("data: [DONE]\n\n");
            response.end();
        });
        configure(server);
        ChatView view = showView();

        view.input().setText(QUESTION);
        view.send();
        waitUntil(() -> view.history().getText().contains("строка 79\n"));
        waitUntil(() -> view.history().getTopPixel() > 0 && isLastLineVisible(view), () -> "top=" + view.history().getTopPixel());

        // Пользователь прокрутил вверх, чтобы перечитать: новые строки не должны его сдёргивать.
        view.history().setTopPixel(0);
        release.countDown();
        waitUntil(() -> !view.session().isBusy() && view.history().getText().contains("строка 159"));
        view.refresh();
        assertEquals(0, view.history().getTopPixel());
    }

    private static boolean isLastLineVisible(ChatView view) {
        return view.history().getLinePixel(view.history().getLineCount()) <= view.history().getClientArea().height
                + view.history().getLineHeight();
    }

    @Test
    void stopKeepsPartialAnswer() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            response.start(200, "text/event-stream");
            for (int i = 0; i < 1000; i++) {
                response.write(openAiPart("слово" + i + " "));
                Thread.sleep(30);
            }
        });
        configure(server);
        ChatView view = showView();

        view.input().setText(QUESTION);
        view.send();
        waitUntil(() -> view.history().getText().contains("слово2"));
        view.sendButton().notifyListeners(SWT.Selection, new Event());

        waitUntil(() -> !view.session().isBusy() && view.history().getText().contains("остановлено"));
        waitUntil(() -> view.linkAction(ChatView.RETRY) != null);
        assertEquals(ChatView.SEND, view.sendButton().getText());
        String stopped = view.history().getText();
        Thread.sleep(200);
        assertEquals(stopped, view.history().getText());
        Screenshots.capture(view.control(), "chat-stopped");
    }

    @Test
    void escapeStopsAnswer() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            response.start(200, "text/event-stream");
            response.write(openAiPart("начало "));
            Thread.sleep(30_000);
        });
        configure(server);
        ChatView view = showView();

        view.input().setText(QUESTION);
        view.send();
        waitUntil(() -> view.history().getText().contains("начало"));
        Event escape = new Event();
        escape.keyCode = SWT.ESC;
        escape.character = SWT.ESC;
        view.input().notifyListeners(SWT.KeyDown, escape);

        waitUntil(() -> !view.session().isBusy() && view.history().getText().contains("остановлено"));
    }

    @Test
    void errorShowsRetryLink() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = FakeModelServer.http().handler((request, response) -> {
            if (calls.incrementAndGet() == 1) {
                response.send(401, "application/json", "{\"error\":{\"message\":\"Invalid API key\"}}");
            } else {
                response.sse(List.of(openAiPart("Теперь получилось."), "data: [DONE]\n\n"), 0);
            }
        });
        configure(server);
        ChatView view = showView();

        view.input().setText(QUESTION);
        view.send();
        waitUntil(() -> view.history().getText().contains("HTTP 401") && view.linkAction(ChatView.RETRY) != null,
                () -> view.history().getText());
        assertTrue(view.history().getText().contains("Ошибка  "), view.history().getText());
        Screenshots.capture(view.control(), "chat-error");

        view.linkAction(ChatView.RETRY).run();

        waitUntil(() -> view.history().getText().contains("Теперь получилось.") && !view.session().isBusy());
        assertFalse(view.history().getText().contains("HTTP 401"));
        assertEquals(2, server.requests().size());
    }

    @Test
    void blankMessageIsNotSent() throws Exception {
        ChatView view = showView();

        view.input().setText("   ");
        view.send();

        assertTrue(view.session().messages().isEmpty());
    }

    @Test
    void preferencePageChecksConnection() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(openAiPart("Готов"), "data: [DONE]\n\n"), 0));
        PreferenceDialog dialog = PreferencesUtil.createPreferenceDialogOn(
                page.getWorkbenchWindow().getShell(), AiPreferencePage.ID, null, null);
        assertNotNull(dialog);
        dialog.setBlockOnOpen(false);
        dialog.open();
        try {
            AiPreferencePage preferencePage = assertInstanceOf(AiPreferencePage.class, dialog.getSelectedPage());
            assertTrue(preferencePage.isValid());
            assertEquals(AiSettings.defaults(), preferencePage.current());

            preferencePage.maxTokensField().setText("много");
            assertFalse(preferencePage.isValid());
            preferencePage.maxTokensField().setText("");
            assertTrue(preferencePage.isValid());

            preferencePage.baseUrlField().setText(server.url());
            preferencePage.modelField().setText("test-model");
            preferencePage.checkButton().notifyListeners(SWT.Selection, new Event());

            waitUntil(() -> preferencePage.checkResult().getText().startsWith("Подключение работает"),
                    () -> preferencePage.checkResult().getText());
            assertTrue(preferencePage.checkResult().getText().endsWith("«Готов»."), preferencePage.checkResult().getText());
            // Диалог центрируется по окну и может уйти за край экрана xvfb.
            dialog.getShell().setLocation(0, 0);
            Screenshots.capture(dialog.getShell(), "preferences");
        } finally {
            dialog.close();
        }
        assertEquals(AiSettings.defaults(), SettingsStore.workspace().load());
    }

    private ChatView showView() throws Exception {
        ChatView view = assertInstanceOf(ChatView.class, page.showView(ChatView.ID));
        page.setPartState(page.getReference(view), IWorkbenchPage.STATE_MAXIMIZED);
        return view;
    }

    private static void configure(FakeModelServer server) throws Exception {
        SettingsStore.workspace().save(new AiSettings(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", 0, false, "",
                new ArrayList<>()));
    }

    private static String describe(IStatus status) {
        StringWriter out = new StringWriter();
        out.append(status.getMessage());
        if (status.getException() != null) {
            status.getException().printStackTrace(new PrintWriter(out));
        }
        return out.toString();
    }

    private static String openAiPart(String text) {
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + escaped + "\"}}]}\n\n";
    }

    private static void waitUntil(BooleanSupplier condition) {
        waitUntil(condition, () -> "");
    }

    /** @param state что показать в сообщении, если не дождались */
    private static void waitUntil(BooleanSupplier condition, Supplier<String> state) {
        Display display = Display.getCurrent();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Не дождались условия за " + TIMEOUT_MS + " мс. Состояние: " + state.get());
            }
            if (!display.readAndDispatch()) {
                display.timerExec(20, () -> { });
                display.sleep();
            }
        }
    }
}
