package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.settings.SettingsStore;

class ChatTitlesTest extends AbstractChatTest {

    @Test
    void titleAppearsWhileFirstAnswerIsStillWaiting() throws Exception {
        CountDownLatch answer = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            if (isTitle(request)) {
                response.sse(List.of(openAiPart("Поиск вызовов РассчитатьСкидку"), "data: [DONE]\n\n"), 0);
            } else {
                answer.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                response.sse(List.of(openAiPart("Нашёл вызовы."), "data: [DONE]\n\n"), 0);
            }
        });
        configure(server);
        enableTitles();
        ChatView view = showView();
        view.showHistoryPanel(true);
        try {
            view.input().setText("Найди вызовы РассчитатьСкидку");
            view.send();
            waitUntil(() -> view.historyPanel().titles().contains("Поиск вызовов РассчитатьСкидку"));
            waitUntil(() -> server.requests().size() == 2);
            assertTrue(view.session().isBusy(), "для названия не нужен конец ответа");
            assertEquals(2, server.requests().size());
            Screenshots.render(view.control(), "chat-title-during-answer");
            answer.countDown();
            waitUntil(() -> !view.session().isBusy());
            ask(view, "А в форме?");
            assertEquals(3, server.requests().size(), "название запрашивается один раз");
        } finally {
            answer.countDown();
            view.showHistoryPanel(false);
        }
    }

    @Test
    void delayedTitleBelongsToBackgroundChat() throws Exception {
        CountDownLatch title = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            if (isTitle(request)) {
                title.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                response.sse(List.of(openAiPart("Автоматическое название"), "data: [DONE]\n\n"), 0);
            } else {
                response.sse(List.of(openAiPart("Ответ."), "data: [DONE]\n\n"), 0);
            }
        });
        configure(server);
        enableTitles();
        ChatView view = showView();
        try {
            ask(view, "Ревью обмена");
            waitUntil(() -> server.requests().stream().anyMatch(ChatTitlesTest::isTitle));
            String id = view.chats().list().get(0).id();
            view.newChatForTest();
            title.countDown();
            waitUntil(() -> view.chats().customTitle(id).orElse("").equals("Автоматическое название"));
            assertTrue(view.session().messages().isEmpty(), "название не создаёт сообщений в новом чате");
            assertEquals(1, view.chats().list().size());
        } finally {
            title.countDown();
        }
    }

    @Test
    void manualNameIsKeptWhenTitleArrivesLater() throws Exception {
        CountDownLatch title = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            if (isTitle(request)) {
                title.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                response.sse(List.of(openAiPart("Автоматическое название"), "data: [DONE]\n\n"), 0);
                returned.countDown();
            } else response.sse(List.of(openAiPart("Ответ."), "data: [DONE]\n\n"), 0);
        });
        configure(server);
        enableTitles();
        ChatView view = showView();
        try {
            ask(view, "Ревью обмена");
            waitUntil(() -> server.requests().stream().anyMatch(ChatTitlesTest::isTitle));
            String id = view.chats().list().get(0).id();
            view.chats().rename(id, "Моё название");
            title.countDown();
            waitUntil(() -> returned.getCount() == 0);
            settle();
            assertEquals("Моё название", view.chats().customTitle(id).orElseThrow());
        } finally {
            title.countDown();
        }
    }

    @Test
    void titleFailureLeavesQuestionAndDoesNotAffectAnswer() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            if (isTitle(request)) response.send(500, "application/json", "{\"error\":{\"message\":\"Недоступно\"}}");
            else response.sse(List.of(openAiPart("Ответ готов."), "data: [DONE]\n\n"), 0);
        });
        configure(server);
        enableTitles();
        ChatView view = showView();
        ask(view, "Ревью обмена");
        waitUntil(() -> server.requests().size() == 2);
        settle();
        assertEquals("Ревью обмена", view.chats().list().get(0).title());
        assertEquals("Ответ готов.", view.session().messages().get(1).text());
        ask(view, "Продолжай");
        assertEquals(3, server.requests().size(), "ошибка названия не запускает повторы");
    }

    @Test
    void deletedChatDoesNotReturnWithLateTitle() throws Exception {
        CountDownLatch title = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            if (isTitle(request)) title.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            response.sse(List.of(openAiPart("Поздний ответ"), "data: [DONE]\n\n"), 0);
        });
        configure(server);
        enableTitles();
        ChatView view = showView();
        view.showHistoryPanel(true);
        try {
            ask(view, "Ревью обмена");
            waitUntil(() -> server.requests().stream().anyMatch(ChatTitlesTest::isTitle));
            String id = view.chats().list().get(0).id();
            view.newChatForTest();
            view.historyPanel().runMenuItem(0, "Удалить");
            title.countDown();
            settle();
            assertTrue(view.chats().list().isEmpty());
            assertTrue(view.chats().customTitle(id).isEmpty());
            assertTrue(view.session().messages().isEmpty());
        } finally {
            title.countDown();
            view.showHistoryPanel(false);
        }
    }

    static boolean isTitle(FakeModelServer.Request request) {
        return request.body().contains("Придумай короткое название");
    }

    private static void enableTitles() {
        InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER).putBoolean(ChatView.GENERATE_TITLES, true);
    }
}
