package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Длинный ответ по частям: лента дописывает хвост, а не переписывает сообщение целиком. */
class ChatStreamingTest extends AbstractChatTest {

    @Test
    void longAnswerKeepsItsBeginningWhileStreaming() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            response.start(200, "text/event-stream");
            response.write(openAiPart("Начало ответа. " + "слово ".repeat(200) + "\n\n"));
            release.await(10, TimeUnit.SECONDS);
            for (int i = 0; i < 60; i++) {
                response.write(openAiPart("Абзац " + i + ": " + "текст ".repeat(30) + "\n\n"));
                Thread.sleep(3);
            }
            response.write("data: [DONE]\n\n");
            response.end();
        });
        configure(server);
        ChatView view = showView();

        view.input().setText("Длинный ответ");
        view.send();
        waitUntil(() -> view.history().getText().contains("Начало ответа"), () -> view.history().getText());
        view.refresh();
        int at = view.history().getText().indexOf("Начало ответа");
        view.history().setSelection(at, at + "Начало".length());

        release.countDown();
        waitUntil(() -> !view.session().isBusy() && view.history().getText().contains("Абзац 59"), () -> view.history().getText());
        view.refresh();
        settle();

        assertEquals("Начало", view.history().getSelectionText(), "начало ответа не переписывалось — выделение в нём цело");
        assertTrue(view.history().getText().contains("Абзац 59: текст"), view.history().getText());
        Screenshots.capture(view.control(), "long-answer-streamed");
    }

    @Test
    void commonPrefixDoesNotSplitPairs() {
        assertEquals(3, ChatView.commonPrefix("абвг", "абвд"));
        assertEquals(4, ChatView.commonPrefix("абвг", "абвгд"));
        assertEquals(0, ChatView.commonPrefix("", "а"));
        assertEquals(1, ChatView.commonPrefix("а\r\n", "а\rx"), "\\r\\n не разрезается");
        assertEquals(1, ChatView.commonPrefix("а😀", "а😁"), "суррогатная пара не разрезается");
    }
}
