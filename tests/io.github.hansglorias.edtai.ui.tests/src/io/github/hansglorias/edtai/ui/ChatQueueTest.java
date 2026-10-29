package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatMessage;

/** Очередь: набранное во время ответа уходит после него. */
class ChatQueueTest extends AbstractChatTest {

    private final CountDownLatch release = new CountDownLatch(1);

    /** Первый ответ ждёт {@link #release}, остальные — сразу. */
    private ChatView slowServer() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            response.start(200, "text/event-stream");
            response.write(openAiPart("Ответ на " + (request.body().contains("второй") ? "второй" : "первый")));
            if (!request.body().contains("второй")) {
                release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            response.write("data: [DONE]\n\n");
            response.end();
        });
        configure(server);
        ChatView view = showView();
        view.input().setText("первый вопрос");
        view.send();
        waitUntil(() -> view.history().getText().contains("Ответ на первый"), () -> view.history().getText());
        return view;
    }

    @Test
    void typedDuringAnswerGoesAfterIt() throws Exception {
        ChatView view = slowServer();

        view.input().setText("второй вопрос");
        assertFalse(view.sendButton().isBusy(), "в поле текст — стрелка «в очередь», а не «Стоп»");
        assertTrue(view.sendButton().getToolTipText().startsWith("В очередь"), view.sendButton().getToolTipText());
        view.send();

        assertEquals("", view.input().getText());
        assertEquals(1, view.queueBar().messages().size());
        assertEquals("«второй вопрос»", QueueBar.preview(view.queueBar().messages().get(0)));
        assertTrue(view.sendButton().isBusy(), "поле пусто — снова «Стоп»");
        Screenshots.capture(view.control(), "queue");

        release.countDown();
        waitUntil(() -> !view.session().isBusy() && view.session().messages().size() == 4, () -> view.history().getText());
        assertEquals(List.of("первый вопрос", "Ответ на первый", "второй вопрос", "Ответ на второй"),
                view.session().messages().stream().map(ChatMessage::text).toList());
        assertTrue(view.queueBar().isEmpty());
        assertEquals(2, server.requests().size());
    }

    @Test
    void stoppedAnswerReturnsQueueToInput() throws Exception {
        ChatView view = slowServer();
        view.input().setText("второй вопрос");
        view.send();
        view.input().setText("третий");
        view.send();
        assertEquals(2, view.queueBar().messages().size());

        view.input().setText("набираю");
        view.session().cancel();
        waitUntil(() -> !view.session().isBusy() && view.queueBar().isEmpty());
        settle();

        assertEquals("набираю\n\nвторой вопрос\n\nтретий", view.input().getText());
        assertEquals(2, view.session().messages().size(), "после остановки ничего не отправлено");
        release.countDown();
    }

    @Test
    void queuedMessageCanBeEditedOrRemoved() throws Exception {
        ChatView view = slowServer();
        view.input().setText("второй вопрос");
        view.send();
        view.input().setText("лишний");
        view.send();

        Event remove = new Event();
        remove.text = "remove";
        view.queueBar().rows().get(1).notifyListeners(SWT.Selection, remove);
        settle();
        assertEquals(1, view.queueBar().messages().size());

        Event edit = new Event();
        edit.text = "edit";
        view.queueBar().rows().get(0).notifyListeners(SWT.Selection, edit);
        settle();
        assertTrue(view.queueBar().isEmpty());
        assertEquals("второй вопрос", view.input().getText());

        release.countDown();
        waitUntil(() -> !view.session().isBusy());
        settle();
        assertEquals(2, view.session().messages().size(), "из очереди ничего не ушло: сообщение снова в поле");
    }
}
