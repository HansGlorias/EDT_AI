package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ContextGauge;
import io.github.hansglorias.edtai.core.chat.TokenUsage;

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
        ContextGauge during = view.contextUsage().gauge();
        assertNotNull(during, "процент окна доступен уже во время ответа");
        assertTrue(during.used() > 0 && during.estimated(), during.toString());
        assertTrue(view.contextUsage().getToolTipText().startsWith("Контекст ≈"), view.contextUsage().getToolTipText());

        release.countDown();
        waitUntil(() -> !view.session().isBusy() && view.history().getText().contains("Абзац 59"), () -> view.history().getText());
        view.refresh();
        settle();
        ContextGauge after = view.contextUsage().gauge();
        assertTrue(after.used() > during.used(), "занятость окна растёт вместе с ответом: " + during + " → " + after);
        assertEquals(after.window(), during.window());

        assertEquals("Начало", view.history().getSelectionText(), "начало ответа не переписывалось — выделение в нём цело");
        assertTrue(view.history().getText().contains("Абзац 59: текст"), view.history().getText());
        Screenshots.capture(view.control(), "long-answer-streamed");
        Screenshots.capture(view.contextUsage().getParent(), "context-usage");
    }

    /** Чат открыли заново (как после перезапуска EDT): счётчик — окно последнего запроса, а не одна история. */
    @Test
    void reopenedChatShowsLastRequestWindow() throws Exception {
        answering(List.of("Ответ."));
        ChatView view = showView();
        ask(view, "Вопрос");
        settle();
        ContextGauge answered = view.contextUsage().gauge();
        String id = view.chats().list().get(0).id();
        assertTrue(answered.used() > view.session().estimateTokens(),
                "запрос больше истории: в нём ещё системный промпт и инструменты");

        view.newChatForTest();
        settle();
        view.openChatForTest(id);
        settle();

        assertNull(view.session().usage(), "открыт заново: данных об ответе в памяти нет");
        assertEquals(answered.used(), view.contextUsage().gauge().used());
    }

    /** /compact обновляет статичный счётчик контекста, без крутилки в нижней панели. */
    @Test
    void compactionUpdatesStaticContextGauge() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            if (request.body().contains("Сожми переписку")) {
                release.await(10, TimeUnit.SECONDS);
                response.sse(List.of(openAiPart("Кратко: был вопрос."), "data: [DONE]\n\n"), 0);
            } else {
                response.sse(List.of(openAiPart("Подробный ответ. " + "слово ".repeat(1500)), "data: [DONE]\n\n"), 0);
            }
        });
        configure(server);
        ChatView view = showView();
        ask(view, "Вопрос");
        settle();
        ContextGauge before = view.contextUsage().gauge();

        view.input().setText("/compact");
        view.send();
        waitUntil(() -> view.contextUsage().isCompacting(), () -> view.status().getText());
        assertTrue(view.contextUsage().getToolTipText().contains("История сжимается"));
        Screenshots.capture(view.contextUsage().getParent(), "context-usage-compacting");

        release.countDown();
        waitUntil(() -> !view.session().isBusy() && !view.contextUsage().isCompacting(), () -> view.status().getText());
        ContextGauge after = view.contextUsage().gauge();
        assertTrue(after.used() < before.used(), "сразу после сжатия — меньше: " + before + " → " + after);
        // После уведомления «История сжата» — то же число, что у процента, а не оценка одной истории.
        waitUntil(() -> view.status().getText().contains(TokenUsage.describeEstimate(after.used(), after.window())),
                () -> view.status().getText());
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
