package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatMessage;

/** Несколько чатов: ответ одного идёт в фоне, пока открыт другой. */
class ChatParallelTest extends AbstractChatTest {

    private final CountDownLatch release = new CountDownLatch(1);

    /** Ответ на «долгий» ждёт {@link #release}, остальные — сразу. */
    private ChatView view() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            boolean slow = request.body().contains("долгий");
            boolean title = request.body().contains("Придумай");
            response.start(200, "text/event-stream");
            response.write(openAiPart(title ? "Название" : slow ? "Ответ на долгий" : "Быстрый ответ"));
            if (slow && !title) {
                release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            response.write("data: [DONE]\n\n");
            response.end();
        });
        configure(server);
        return showView();
    }

    @Test
    void answerGoesOnInBackgroundWhileAnotherChatIsOpen() throws Exception {
        ChatView view = view();
        view.input().setText("долгий вопрос");
        view.send();
        waitUntil(() -> view.history().getText().contains("Ответ на долгий"), () -> view.history().getText());
        String first = view.historyFile().path().getFileName().toString();
        String id = view.chats().list().get(0).id();

        view.newChatForTest();
        settle();
        assertTrue(view.session().messages().isEmpty(), "новый чат пустой, без истории прежнего");
        assertTrue(view.isEmptyStateShown());
        assertNotEquals(first, view.historyFile().path().getFileName().toString());
        assertEquals(List.of(ChatHistoryPanel.ANSWERING), view.historyPanel().times(), "прежний чат отвечает в фоне");

        ask(view, "быстрый вопрос");
        assertEquals(List.of("быстрый вопрос", "Быстрый ответ"), view.session().messages().stream().map(ChatMessage::text).toList());

        release.countDown();
        waitUntil(() -> !view.historyPanel().times().contains(ChatHistoryPanel.ANSWERING), () -> view.historyPanel().times().toString());
        waitUntil(() -> load(view, id).size() == 2);
        assertEquals("Ответ на долгий", load(view, id).get(1).text());

        view.openChatForTest(id);
        settle();
        assertEquals(List.of("долгий вопрос", "Ответ на долгий"), view.session().messages().stream().map(ChatMessage::text).toList());
        assertTrue(view.history().getText().contains("Ответ на долгий"));
    }

    @Test
    void openingChatDoesNotMoveItUp() throws Exception {
        ChatView view = view();
        ask(view, "первый вопрос");
        String older = view.historyFile().path().getFileName().toString();
        view.newChatForTest();
        ask(view, "второй вопрос");
        settle();
        List<String> titles = view.historyPanel().titles();
        String olderId = view.chats().list().get(1).id();
        FileTime before = Files.getLastModifiedTime(view.chats().file(olderId).path());

        view.openChatForTest(olderId);
        settle();
        view.newChatForTest();
        settle();

        assertEquals(older, view.chats().file(olderId).path().getFileName().toString());
        assertEquals(before, Files.getLastModifiedTime(view.chats().file(olderId).path()), "открытый и закрытый чат не пересохранён");
        assertEquals(titles, view.historyPanel().titles());
    }

    private static List<ChatMessage> load(ChatView view, String id) {
        try {
            return view.chats().file(id).load();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }
}
