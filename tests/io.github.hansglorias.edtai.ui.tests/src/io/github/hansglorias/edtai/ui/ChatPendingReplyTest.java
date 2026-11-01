package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.IWorkbenchPage;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.SettingsStore;

/** Ожидание первого байта — такая же часть ленты, как растущий ответ. */
class ChatPendingReplyTest extends AbstractChatTest {

    private ChatView pendingView() throws Exception {
        ChatView view = showView();
        view.showHistoryPanel(false);
        // Предыдущий тест мог оставить вид в нижнем стеке: явно перестраиваем максимизацию.
        page.setPartState(page.getReference(view), IWorkbenchPage.STATE_RESTORED);
        settle();
        page.setPartState(page.getReference(view), IWorkbenchPage.STATE_MAXIMIZED);
        settle();
        return view;
    }

    @Test
    void errorBeforeTextReplacesIndicator() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            response.send(503, "application/json", "{\"error\":{\"message\":\"unavailable\"}}");
        });
        configure(server);
        ChatView view = pendingView();
        view.input().setText("Вопрос");
        view.send();
        view.refresh();
        assertTrue(view.history().getText().contains("ожидает модель…"));
        release.countDown();
        waitUntil(() -> !view.session().isBusy());
        view.refresh();
        assertEquals(List.of(ChatRole.USER, ChatRole.ERROR), view.session().messages().stream().map(ChatMessage::role).toList());
        assertFalse(view.history().getText().contains("ожидает модель"));
        assertFalse(view.history().getText().contains("ответ не получен"));
        assertTrue(view.history().getText().contains("HTTP 503"));
        assertNotNull(view.linkAction(MessageRenderer.RETRY));
        Screenshots.render(view.control(), "chat-pending-error");
    }

    @Test
    void toolOnlyReplyUsesSameEntryAndEndsIndicator() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("\"role\":\"tool\"")) {
                String arguments = Json.write(Json.object("name", "report.txt", "content", "готово"));
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"attach_file\",\"arguments\":" + Json.write(arguments)
                        + "}}]}}]}\n\n", "data: [DONE]\n\n"), 0);
            } else {
                release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                response.sse(List.of("data: [DONE]\n\n"), 0);
            }
        });
        configure(server);
        ChatView view = pendingView();
        view.input().setText("Подготовь файл");
        view.send();
        var time = view.session().messages().get(1).time();
        waitUntil(() -> server.requests().size() == 2);
        view.refresh();
        assertEquals(2, view.session().messages().size());
        assertTrue(view.history().getText().contains("ожидает модель…"));
        assertTrue(view.history().getText().contains("report.txt"));
        release.countDown();
        waitUntil(() -> !view.session().isBusy());
        view.refresh();
        assertEquals(2, view.session().messages().size());
        ChatMessage answer = view.session().messages().get(1);
        assertEquals(time, answer.time());
        assertEquals(ChatMessage.Status.DONE, answer.status());
        assertEquals(1, answer.tools().size());
        assertEquals(1, answer.attachments().size());
        assertFalse(view.history().getText().contains("ожидает модель"));
        assertEquals(2, view.historyFile().load().size());
        Screenshots.render(view.control(), "chat-tool-only-final");
    }

    @Test
    void pendingAnswerSurvivesChatSwitchAndIsFilledInPlace() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            response.sse(List.of(openAiPart("Получен ответ"), "data: [DONE]\n\n"), 0);
        });
        configure(server);
        ChatView view = pendingView();
        view.input().setText("Долгий вопрос");
        view.send();
        view.refresh();
        assertTrue(view.history().getText().contains("ожидает модель…"), view.history().getText());
        assertEquals(2, view.session().messages().size());
        ChatMessage pending = view.session().messages().get(1);
        assertTrue(pending.isEmptyAnswer());
        settle();
        int waiting = view.history().getText().indexOf("ожидает модель…");
        int waitingY = view.history().getLocationAtOffset(waiting).y;
        assertTrue(waitingY >= 0 && waitingY < view.history().getClientArea().height,
                "ожидание видно в ленте без ручной прокрутки");
        Screenshots.render(view.control(), "chat-pending-first-byte");
        waitUntil(() -> !server.requests().isEmpty());
        assertFalse(server.requests().get(0).body().contains("ожидает модель"));
        assertEquals(List.of(ChatRole.USER), view.historyFile().load().stream().map(ChatMessage::role).toList());
        String id = view.chats().list().get(0).id();

        view.newChatForTest();
        settle();
        assertTrue(view.session().messages().isEmpty());
        assertFalse(view.history().getText().contains("ожидает модель"));
        view.openChatForTest(id);
        view.refresh();
        assertTrue(view.session().isBusy());
        assertTrue(view.history().getText().contains("ожидает модель…"));
        assertEquals(pending.time(), view.session().messages().get(1).time());
        // Ещё раз уходим: первый байт и завершение придут фоновому чату.
        var answering = view.session();
        view.newChatForTest();
        release.countDown();
        waitUntil(() -> !answering.isBusy());
        settle();
        assertTrue(view.session().messages().isEmpty());
        view.openChatForTest(id);
        view.refresh();
        assertEquals(2, view.session().messages().size());
        assertEquals(pending.time(), view.session().messages().get(1).time());
        assertEquals("Получен ответ", view.session().messages().get(1).text());
        assertFalse(view.history().getText().contains("ожидает модель"));
    }

    @Test
    void stopBeforeFirstByteEndsIndicatorAndReopenHasOnlyQuestion() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            response.sse(List.of(openAiPart("Поздний ответ"), "data: [DONE]\n\n"), 0);
        });
        configure(server);
        ChatView view = pendingView();
        // Даже при включённых названиях пустой ответ не запускает лишний запрос.
        InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER).putBoolean(ChatView.GENERATE_TITLES, true);
        view.input().setText("Вопрос");
        view.send();
        waitUntil(() -> !server.requests().isEmpty());
        view.session().cancel();
        view.refresh();
        assertFalse(view.session().isBusy());
        assertTrue(view.history().getText().contains("остановлено"));
        assertFalse(view.history().getText().contains("ожидает модель"));
        assertNotNull(view.linkAction(MessageRenderer.RETRY));
        assertEquals(1, view.historyFile().load().size());
        String id = view.chats().list().get(0).id();
        view.newChatForTest();
        view.openChatForTest(id);
        view.refresh();
        assertEquals(1, view.session().messages().size());
        assertFalse(view.history().getText().contains("ответ не получен"));
        release.countDown();
        settle();
        assertEquals(1, server.requests().size());
    }

    @Test
    void emptyFinalEndsIndicatorWithoutGeneratingTitle() throws Exception {
        answering(List.of());
        ChatView view = pendingView();
        InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER).putBoolean(ChatView.GENERATE_TITLES, true);
        ask(view, "Вопрос");
        assertTrue(view.history().getText().contains("пустой ответ"), view.history().getText());
        assertFalse(view.history().getText().contains("ожидает модель"));
        assertFalse(view.history().getText().contains("пишет…"));
        assertNotNull(view.linkAction(MessageRenderer.RETRY));
        assertEquals(1, view.historyFile().load().size());
        settle();
        assertEquals(1, server.requests().size());
        Screenshots.render(view.control(), "chat-empty-final");
    }
}
