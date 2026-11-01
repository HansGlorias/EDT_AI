package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.chat.ChatHistoryFile;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatSession;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.context.ContextItem;

/** Правка сообщения — отдельный черновик до подтверждения отправкой. */
class ChatMessageEditTest extends AbstractChatTest {

    private static final ContextItem SENT_CONTEXT = ContextItem.of("ДемоПравка", "src/CommonModules/Исходный/Module.bsl",
            2, 2, 3, "исходный код");
    private static final ContextItem DRAFT_CONTEXT = ContextItem.of("ДемоПравка", "src/CommonModules/Черновик/Module.bsl",
            1, 1, 1, "код черновика");
    private static final Attachment SENT_FILE = new Attachment("исходный.txt", "text/plain", "исходное вложение");
    private static final Attachment DRAFT_FILE = new Attachment("черновик.txt", "text/plain", "вложение черновика");

    @Test
    void pencilAndBothCancelActionsKeepHistoryAndRestoreOriginalDraft() throws Exception {
        ChatView view = view();
        view.contextBar().add(AttachedContext.fromItem(SENT_CONTEXT));
        view.contextBar().add(SENT_FILE);
        ask(view, "первый вопрос");
        ask(view, "второй вопрос");
        List<ChatMessage> withTool = new ArrayList<>(view.session().messages());
        withTool.set(1, withTool.get(1).withTools(List.of(
                new ToolUse("read-1", "read_module", "Читает исходный модуль", "прочитано", ToolUse.Status.DONE))));
        view.session().restore(withTool);
        view.refresh();
        settle();

        AttachedContext draftContext = AttachedContext.fromItem(DRAFT_CONTEXT);
        view.contextBar().add(draftContext);
        view.contextBar().add(DRAFT_FILE);
        view.input().setText("неотправленный\nчерновик");
        view.input().setSelection(2, 8);
        String draftText = view.input().getText();
        Point selection = view.input().getSelection();
        List<ChatMessage> before = view.session().messages();
        String saved = savedChats();
        String historyJson = Files.readString(view.historyFile().path());

        for (boolean byEscape : List.of(false, true)) {
            view.linkActions(MessageRenderer.EDIT).get(0).run();
            settle();
            assertTrue(view.cancelEditButton().isVisible(), "отмена видна рядом с полем правки");
            assertEquals("первый вопрос", view.input().getText());
            assertEquals(List.of(SENT_CONTEXT), view.contextBar().snapshot());
            assertEquals(List.of(SENT_FILE), view.contextBar().attachments());
            assertEquals(before, view.session().messages());
            assertEquals(saved, savedChats());
            assertEquals(historyJson, Files.readString(view.historyFile().path()));
            assertTrue(view.history().getText().contains("второй вопрос"));
            assertTrue(view.history().getText().contains("Читает исходный модуль"));
            Screenshots.render(view.control(), byEscape ? "message-edit-before-escape" : "message-edit-draft");
            view.input().setText("незавершённая правка");
            view.contextBar().clear();
            view.contextBar().add(new Attachment("правка.txt", "text/plain", "только для правки"));

            if (byEscape) {
                escape(view);
            } else {
                view.cancelEditButton().notifyListeners(SWT.Selection, new Event());
            }
            settle();
            assertFalse(view.cancelEditButton().isVisible());
            assertEquals(draftText, view.input().getText());
            assertEquals(selection, view.input().getSelection());
            assertSame(draftContext, view.contextBar().items().get(0), "возвращается та же ссылка на фрагмент редактора");
            assertEquals(List.of(DRAFT_CONTEXT), view.contextBar().snapshot());
            assertEquals(List.of(DRAFT_FILE), view.contextBar().attachments());
            assertEquals(before, view.session().messages());
            assertEquals(saved, savedChats());
            assertEquals(historyJson, Files.readString(view.historyFile().path()));
        }
    }

    @Test
    void confirmCutsBranchOnlyAtSendAndEmptyEditKeepsEverything() throws Exception {
        ChatView view = view();
        view.contextBar().add(AttachedContext.fromItem(SENT_CONTEXT));
        view.contextBar().add(SENT_FILE);
        ask(view, "исходный вопрос");
        ask(view, "прежнее продолжение");
        List<ChatMessage> before = view.session().messages();
        String saved = savedChats();
        view.input().setText("следующий черновик");
        view.contextBar().add(DRAFT_FILE);

        view.linkActions(MessageRenderer.EDIT).get(0).run();
        view.input().setText(" \n\t ");
        view.send();
        settle();
        assertTrue(view.cancelEditButton().isVisible());
        assertEquals(before, view.session().messages());
        assertEquals(saved, savedChats());
        assertEquals(2, server.requests().size(), "пустая правка не отправляется");

        view.input().setText("исправленный вопрос");
        assertEquals(before, view.session().messages());
        view.send();
        assertEquals("исправленный вопрос", view.session().messages().get(0).text());
        assertFalse(view.session().messages().stream().anyMatch(message -> message.text().equals("прежнее продолжение")));
        assertFalse(view.cancelEditButton().isVisible());
        assertEquals("следующий черновик", view.input().getText(), "подтверждение также сохраняет прежний ввод");
        assertEquals(List.of(DRAFT_FILE), view.contextBar().attachments());
        waitUntil(() -> !view.session().isBusy());
        view.refresh();

        assertEquals(List.of("исправленный вопрос", "Ответ."), texts(view));
        assertEquals(List.of(SENT_CONTEXT), view.session().messages().get(0).context());
        assertEquals(List.of(SENT_FILE), view.session().messages().get(0).attachments());
        assertEquals(view.session().messages(), view.historyFile().load());
        assertEquals(3, server.requests().size());
        Screenshots.render(view.control(), "message-edit-confirmed");
    }

    @Test
    void switchingChatsParksEditAndEachComposerBelongsToItsOwnChat() throws Exception {
        ChatView view = view();
        ask(view, "вопрос первого чата");
        String firstId = id(view);
        ChatSession firstSession = view.session();
        List<ChatMessage> firstHistory = firstSession.messages();
        view.input().setText("обычный черновик первого");
        view.contextBar().add(DRAFT_FILE);
        view.linkAction(MessageRenderer.EDIT).run();
        view.input().setText("правка первого");
        view.input().setSelection(1, 7);
        view.contextBar().add(SENT_FILE);

        view.newChatForTest();
        settle();
        assertTrue(view.session().messages().isEmpty());
        assertEquals("", view.input().getText());
        assertTrue(view.contextBar().isEmpty());
        assertFalse(view.cancelEditButton().isVisible());
        ask(view, "вопрос второго чата");
        String secondId = id(view);
        ChatSession secondSession = view.session();
        List<ChatMessage> secondHistory = secondSession.messages();
        view.input().setText("черновик второго");
        assertEquals(firstHistory, firstSession.messages(), "отправка во втором чате не подтверждает правку первого");

        view.openChatForTest(firstId);
        settle();
        assertSame(firstSession, view.session());
        assertTrue(view.cancelEditButton().isVisible());
        assertEquals("правка первого", view.input().getText());
        assertEquals(new Point(1, 7), view.input().getSelection());
        assertEquals(List.of(SENT_FILE), view.contextBar().attachments());
        assertEquals(firstHistory, view.historyFile().load());
        view.send();
        waitUntil(() -> !view.session().isBusy());
        view.refresh();
        assertEquals(List.of("правка первого", "Ответ."), texts(view));
        assertEquals("обычный черновик первого", view.input().getText());
        assertEquals(List.of(DRAFT_FILE), view.contextBar().attachments());
        assertEquals(secondHistory, secondSession.messages());
        assertEquals(secondHistory, view.chats().file(secondId).load());

        view.openChatForTest(secondId);
        settle();
        assertSame(secondSession, view.session());
        assertEquals("черновик второго", view.input().getText());
        assertFalse(view.cancelEditButton().isVisible());
        assertTrue(view.contextBar().isEmpty());
    }

    @Test
    void newChatKeepsUnsentDraftAccessibleWithoutWritingItAsHistory() throws Exception {
        ChatView view = view();
        view.showHistoryPanel(true);
        String draftId = id(view);
        ChatHistoryFile draftHistory = view.historyFile();
        AttachedContext context = AttachedContext.fromItem(DRAFT_CONTEXT);
        view.input().setText("ещё не отправлено");
        view.input().setSelection(3, 6);
        view.contextBar().add(context);
        view.contextBar().add(DRAFT_FILE);

        view.newChatForTest();
        settle();
        assertTrue(view.historyPanel().titles().stream().anyMatch(title -> title.contains("ещё не отправлено")),
                "неотправленный чат можно найти в панели");
        assertFalse(Files.exists(draftHistory.path()), "черновик не подменяет сохранённую историю");
        assertEquals("", view.input().getText());

        view.openChatForTest(draftId);
        settle();
        assertEquals("ещё не отправлено", view.input().getText());
        assertEquals(new Point(3, 6), view.input().getSelection());
        assertSame(context, view.contextBar().items().get(0));
        assertEquals(List.of(DRAFT_FILE), view.contextBar().attachments());
        assertTrue(view.session().messages().isEmpty());
    }

    @Test
    void stalePencilCannotEditAnotherLiveEvenWhenBothHistoriesAreEqual() throws Exception {
        ChatView view = view();
        ask(view, "одинаковый вопрос");
        String firstId = id(view);
        ChatSession firstSession = view.session();
        List<ChatMessage> sharedHistory = firstSession.messages();
        Runnable firstPencil = view.linkAction(MessageRenderer.EDIT);
        assertNotNull(firstPencil);
        view.input().setText("черновик первого");

        firstPencil.run();
        assertTrue(view.cancelEditButton().isVisible(), "действие работает в своём Live до смены чата");
        view.cancelEditButton().notifyListeners(SWT.Selection, new Event());
        assertEquals("черновик первого", view.input().getText());

        view.newChatForTest();
        assertTrue(view.linkActions(MessageRenderer.EDIT).isEmpty(), "при смене чата старая лента сброшена синхронно");
        firstPencil.run();
        assertFalse(view.cancelEditButton().isVisible());
        assertEquals("", view.input().getText());

        view.session().restore(sharedHistory);
        view.refresh();
        assertEquals(sharedHistory, view.session().messages(), "совпадают все поля, включая время и номера сообщений");
        view.input().setText("черновик второго");
        firstPencil.run();
        assertFalse(view.cancelEditButton().isVisible(), "проверки только равенства ChatMessage недостаточно");
        assertEquals("черновик второго", view.input().getText());
        assertEquals(sharedHistory, view.session().messages());
        Runnable secondPencil = view.linkAction(MessageRenderer.EDIT);
        assertNotNull(secondPencil);
        assertNotSame(firstPencil, secondPencil);
        secondPencil.run();
        assertTrue(view.cancelEditButton().isVisible(), "новое действие создано для второго Live");
        view.cancelEditButton().notifyListeners(SWT.Selection, new Event());
        assertEquals("черновик второго", view.input().getText());

        view.openChatForTest(firstId);
        assertSame(firstSession, view.session());
        assertEquals(sharedHistory, view.session().messages());
        secondPencil.run();
        assertFalse(view.cancelEditButton().isVisible(), "действие второго Live не применяется к первому");
        assertEquals("черновик первого", view.input().getText());
        Runnable recreatedFirstPencil = view.linkAction(MessageRenderer.EDIT);
        assertNotNull(recreatedFirstPencil);
        assertNotSame(firstPencil, recreatedFirstPencil, "равная история не сохраняет действия прежней отрисовки");
        assertNotSame(secondPencil, recreatedFirstPencil);
        recreatedFirstPencil.run();
        assertTrue(view.cancelEditButton().isVisible(), "после возврата действие привязано к открытому Live");
        view.cancelEditButton().notifyListeners(SWT.Selection, new Event());
        assertEquals("черновик первого", view.input().getText());
        assertEquals(sharedHistory, view.historyFile().load());
    }

    @Test
    void reopeningViewKeepsOriginalHistoryBeforeConfirmAndReplacementAfterConfirm() throws Exception {
        ChatView view = view();
        view.contextBar().add(SENT_FILE);
        view.contextBar().add(AttachedContext.fromItem(SENT_CONTEXT));
        ask(view, "сохранённый вопрос");
        ask(view, "сохранённый хвост");
        List<ChatMessage> original = view.session().messages();
        view.linkActions(MessageRenderer.EDIT).get(0).run();
        view.input().setText("неподтверждённая правка");

        page.hideView(view);
        ChatView reopened = showView();
        assertEquals(original, reopened.session().messages());
        assertEquals(original, reopened.historyFile().load());
        assertFalse(reopened.cancelEditButton().isVisible());
        reopened.linkActions(MessageRenderer.EDIT).get(0).run();
        reopened.input().setText("подтверждённая правка");
        reopened.send();
        waitUntil(() -> !reopened.session().isBusy());
        reopened.refresh();
        List<ChatMessage> confirmed = reopened.session().messages();

        page.hideView(reopened);
        ChatView again = showView();
        assertEquals(confirmed, again.session().messages());
        assertEquals(confirmed, again.historyFile().load());
        assertEquals(List.of("подтверждённая правка", "Ответ."), texts(again));
        assertEquals(List.of(SENT_FILE), again.session().messages().get(0).attachments());
        assertEquals(List.of(SENT_CONTEXT), again.session().messages().get(0).context());
    }

    @Test
    void ownMessageCopyHasHintAndCopiesOnlyTextWithoutObjectPlaceholders() throws Exception {
        ChatView view = view();
        view.contextBar().add(SENT_FILE);
        view.contextBar().add(AttachedContext.fromItem(SENT_CONTEXT));
        ask(view, "**свой текст**\nвторая строка");
        assertTrue(view.history().getText().indexOf(MessageRenderer.OBJECT) >= 0, "в ленте есть значки U+FFFC");
        List<Runnable> copies = view.linkActions(MessageRenderer.COPY);
        assertEquals(2, copies.size(), "копирование у вопроса и ответа");
        StyleRange copyStyle = java.util.Arrays.stream(view.history().getStyleRanges())
                .filter(style -> style.data instanceof MessageRenderer.ActionIcon icon && MessageRenderer.COPY.equals(icon.label()))
                .findFirst().orElseThrow();
        view.history().setSelection(copyStyle.start, copyStyle.start + 1);
        view.history().showSelection();
        settle();
        Rectangle iconBounds = view.history().getTextBounds(copyStyle.start, copyStyle.start);
        Rectangle visibleBounds = iconBounds.intersection(view.history().getClientArea());
        // На строке с вертикальным отступом location + (1, 1) может оказаться в пустом промежутке.
        // Ищем настоящий видимый пиксель самого значка, который SWT относит к его символу.
        Point point = null;
        for (int y = visibleBounds.y; y < visibleBounds.y + visibleBounds.height && point == null; y++) {
            for (int x = visibleBounds.x; x < visibleBounds.x + visibleBounds.width; x++) {
                if (view.history().getOffsetAtPoint(new Point(x, y)) == copyStyle.start) {
                    point = new Point(x, y);
                    break;
                }
            }
        }
        String geometry = "offset=" + copyStyle.start + ", icon=" + iconBounds + ", visible=" + visibleBounds
                + ", client=" + view.history().getClientArea() + ", control=" + view.history().getBounds()
                + ", location=" + view.history().getLocationAtOffset(copyStyle.start) + ", top=" + view.history().getTopPixel();
        assertNotNull(point, "значок копирования должен быть видим и доступен указателю: " + geometry);
        assertEquals(MessageRenderer.COPY, view.hintAt(point.x, point.y), geometry + ", pointer=" + point);

        copies.get(0).run();
        Clipboard clipboard = new Clipboard(view.input().getDisplay());
        try {
            String copied = (String) clipboard.getContents(TextTransfer.getInstance());
            assertEquals(view.session().messages().get(0).text(), copied);
            assertFalse(copied.indexOf(MessageRenderer.OBJECT) >= 0);
            assertFalse(copied.contains(SENT_FILE.name()));
            assertFalse(copied.contains(SENT_CONTEXT.label()));
        } finally {
            clipboard.dispose();
        }
    }

    @Test
    void confirmedEditIsSavedWhileWaitingAndStopDoesNotRestoreOldBranch() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            if (request.body().contains("исправленный вопрос")) {
                release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            response.sse(List.of(openAiPart("Ответ."), "data: [DONE]\n\n"), 0);
        });
        configure(server);
        ChatView view = showView();
        view.showHistoryPanel(false);
        try {
            ask(view, "исходный вопрос");
            ask(view, "старый хвост");
            view.linkActions(MessageRenderer.EDIT).get(0).run();
            view.input().setText("исправленный вопрос");
            view.send();
            view.refresh();

            assertTrue(view.session().isBusy());
            assertFalse(view.cancelEditButton().isVisible());
            assertEquals(List.of("исправленный вопрос", ""), texts(view));
            assertEquals(ChatMessage.Status.STREAMING, view.session().messages().get(1).status());
            assertTrue(view.history().getText().contains("ожидает модель…"), view.history().getText());
            assertFalse(view.history().getText().contains("старый хвост"));
            assertEquals(List.of("исправленный вопрос"), view.historyFile().load().stream().map(ChatMessage::text).toList(),
                    "новая ветвь сохраняется сразу при подтверждении, до первого байта ответа");

            escape(view);
            waitUntil(() -> !view.session().isBusy());
            view.refresh();
            assertFalse(view.history().getText().contains("ожидает модель"));
            page.hideView(view);
            ChatView reopened = showView();
            assertEquals(List.of("исправленный вопрос"), texts(reopened));
            assertEquals(List.of("исправленный вопрос"), reopened.historyFile().load().stream().map(ChatMessage::text).toList());
        } finally {
            release.countDown();
        }
    }

    @Test
    void generationRejectsPencilAndEscapeCancelsEditBeforeStoppingAnswer() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("долгий вопрос")) {
                response.sse(List.of(openAiPart("Ответ."), "data: [DONE]\n\n"), 0);
                return;
            }
            response.start(200, "text/event-stream");
            response.write(openAiPart("начало долгого ответа"));
            release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            response.write("data: [DONE]\n\n");
            response.end();
        });
        configure(server);
        ChatView view = showView();
        try {
            ask(view, "первый вопрос");
            Runnable pencil = view.linkAction(MessageRenderer.EDIT);
            assertNotNull(pencil);
            view.input().setText("долгий вопрос");
            view.send();
            waitUntil(() -> view.history().getText().contains("начало долгого ответа"));
            List<ChatMessage> running = view.session().messages();
            pencil.run();
            assertFalse(view.cancelEditButton().isVisible());
            assertEquals("", view.input().getText());
            assertEquals(running, view.session().messages());
            assertTrue(view.session().isBusy());
            escape(view);
            waitUntil(() -> !view.session().isBusy());
            view.refresh();

            view.input().setText("черновик перед правкой");
            pencil.run();
            assertTrue(view.cancelEditButton().isVisible());
            // Имитируем начало запроса вне composer между началом правки и Esc.
            view.session().send("ещё один долгий вопрос");
            assertTrue(view.session().isBusy());
            escape(view);
            assertFalse(view.cancelEditButton().isVisible());
            assertEquals("черновик перед правкой", view.input().getText());
            assertTrue(view.session().isBusy(), "первый Esc отменяет правку, оставляя генерацию");
            escape(view);
            waitUntil(() -> !view.session().isBusy());
        } finally {
            release.countDown();
        }
    }

    private ChatView view() throws Exception {
        answering(List.of("Ответ."));
        ChatView view = showView();
        view.showHistoryPanel(false);
        page.setPartState(page.getReference(view), org.eclipse.ui.IWorkbenchPage.STATE_RESTORED);
        page.setPartState(page.getReference(view), org.eclipse.ui.IWorkbenchPage.STATE_MAXIMIZED);
        settle();
        return view;
    }

    private static void escape(ChatView view) {
        Event event = new Event();
        event.keyCode = SWT.ESC;
        event.character = SWT.ESC;
        view.input().notifyListeners(SWT.KeyDown, event);
        assertFalse(event.doit);
    }

    private static String id(ChatView view) {
        String filename = view.historyFile().path().getFileName().toString();
        return filename.substring(0, filename.length() - ".json".length());
    }

    private static List<String> texts(ChatView view) {
        return view.session().messages().stream().map(ChatMessage::text).toList();
    }
}
