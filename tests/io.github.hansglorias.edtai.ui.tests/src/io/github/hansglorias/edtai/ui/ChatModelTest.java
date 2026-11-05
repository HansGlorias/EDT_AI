package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatModel;
import io.github.hansglorias.edtai.core.chat.ChatStore;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.SettingsStore;
import io.github.hansglorias.edtai.core.settings.ApiProvider;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.Protocol;

/** Выбор модели в поле ввода: список с сервера, недавние, {@code /model}. */
class ChatModelTest extends AbstractChatTest {

    /** Сервер со списком моделей; ответ — имя модели из запроса. */
    private ChatView serverWithModels() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            if (request.method().equals("GET") && request.path().endsWith("/models")) {
                response.send(200, "application/json", Json.write(Json.object("data", List.of(Json.object("id", "test-model"),
                        Json.object("id", "qwen3-32b"), Json.object("id", "deepseek-r1"), Json.object("id", "qwen3-coder")))));
                return;
            }
            String model = Json.stringAt(Json.parseObject(request.body()), "model");
            response.sse(List.of(openAiPart("Отвечает " + model), "data: [DONE]\n\n"), 5);
        });
        configure(server);
        return showView();
    }

    @Test
    void menuListsServerModelsAndSwitches() throws Exception {
        ChatView view = serverWithModels();
        assertEquals("test-model", view.modelButton().getText());
        assertTrue(view.modelButton().getToolTipText().startsWith("Модель: test-model · localhost"), view.modelButton().getToolTipText());

        view.loadModels(false);
        waitUntil(() -> view.modelChoices().size() == 4, () -> view.modelChoices().toString());
        Menu menu = view.modelMenu();
        List<String> items = Arrays.stream(menu.getItems()).map(MenuItem::getText).toList();
        assertEquals(List.of("test-model", "deepseek-r1", "qwen3-32b", "qwen3-coder", "", "Обновить список", "Настройки модели…"), items);
        assertTrue(menu.getItems()[0].getSelection(), "текущая отмечена");

        MenuItem qwen = menu.getItems()[2];
        qwen.setSelection(true);
        qwen.notifyListeners(SWT.Selection, new Event());
        menu.dispose();

        assertEquals("qwen3-32b", SettingsStore.workspace().load().model());
        waitUntil(() -> "qwen3-32b".equals(view.modelButton().getText()), () -> view.modelButton().getText());
        assertEquals("Модель: qwen3-32b", view.status().getText());
        Screenshots.capture(view.control(), "model-picker");

        ask(view, "Кто отвечает?");
        assertTrue(view.history().getText().contains("Отвечает qwen3-32b"), view.history().getText());
        assertEquals(List.of("qwen3-32b", "test-model", "deepseek-r1", "qwen3-coder"), view.modelChoices(), "недавние — первыми");
    }

    @Test
    void modelCommandFindsByPart() throws Exception {
        ChatView view = serverWithModels();

        view.input().setText("/model deep");
        view.send();
        waitUntil(() -> "deepseek-r1".equals(SettingsStore.workspace().load().model()), () -> view.status().getText());
        assertEquals("", view.input().getText());

        view.input().setText("/model qwen3");
        view.send();
        settle();
        assertEquals("deepseek-r1", SettingsStore.workspace().load().model(), "две подходящие — не выбираем");
        assertTrue(view.status().getText().startsWith("Под «qwen3» подходят: "), view.status().getText());

        view.input().setText("/model my-local-model");
        view.send();
        waitUntil(() -> "my-local-model".equals(SettingsStore.workspace().load().model()), () -> view.status().getText());
        List<String> requests = new ArrayList<>();
        server.requests().forEach(request -> requests.add(request.method() + " " + request.path()));
        assertEquals(List.of("GET /v1/models"), requests, "список загружен один раз, вопросов модели не было");
        assertFalse(view.session().isBusy());
    }

    /** У каждого чата своя модель: переключение чатов переключает и модель, новые чаты — с последней выбранной. */
    @Test
    void modelIsRememberedPerChat() throws Exception {
        ChatView view = serverWithModels();
        view.chooseModel("qwen3-32b");
        ask(view, "Кто ты?");
        assertTrue(view.history().getText().contains("Отвечает qwen3-32b"), view.history().getText());
        String first = view.chats().list().get(0).id();

        view.newChatForTest();
        settle();
        assertEquals("qwen3-32b", view.modelButton().getText(), "новый чат — с последней выбранной моделью");
        view.chooseModel("deepseek-r1");
        ask(view, "А ты?");
        assertTrue(view.history().getText().contains("Отвечает deepseek-r1"), view.history().getText());
        String second = view.chats().list().get(0).id();

        view.openChatForTest(first);
        settle();
        assertEquals("qwen3-32b", view.modelButton().getText(), "открытый чат — со своей моделью");
        assertTrue(view.status().getText().startsWith("qwen3-32b"), view.status().getText());
        ask(view, "Ещё раз?");
        List<ChatMessage> messages = view.session().messages();
        assertEquals("Отвечает qwen3-32b", messages.get(messages.size() - 1).text());
        assertEquals("deepseek-r1", SettingsStore.workspace().load().model(), "общая модель — последняя выбранная");

        ChatStore saved = new ChatStore(stateLocation().resolve(ChatView.CHATS_DIRECTORY));
        assertEquals("qwen3-32b", saved.model(first).model());
        assertEquals("deepseek-r1", saved.model(second).model());

        view.openChatForTest(second);
        settle();
        assertEquals("deepseek-r1", view.modelButton().getText());

        // Сменили сервер — привязка к прежнему не действует, чат отвечает моделью из настроек.
        SettingsStore.workspace().save(SettingsStore.workspace().load().withModel("test-model"));
        settle();
        assertEquals("test-model", view.modelButton().getText());
        assertEquals(new ChatModel("test-model", server.url()), saved.model(second), "смена модели в настройках — открытому чату");
    }
    @Test
    void providersRoutePerChatWithOwnTokensAndModelLists() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            if (request.method().equals("GET")) {
                response.send(200, "application/json", "{\"data\":[{\"id\":\"first-only\"}]}");
            } else response.sse(List.of(openAiPart("первый"), "data: [DONE]\n\n"), 5);
        });
        try (FakeModelServer secondServer = FakeModelServer.http().handler((request, response) -> {
            if (request.method().equals("GET")) {
                response.send(200, "application/json", "{\"data\":[{\"id\":\"second-only\"}]}");
            } else response.sse(List.of(openAiPart("второй"), "data: [DONE]\n\n"), 5);
        })) {
            configure(server);
            SettingsStore store = SettingsStore.workspace();
            ApiProvider first = store.providers().get(0);
            ApiProvider second = new ApiProvider("second", "Второй", Protocol.OPENAI_COMPATIBLE, secondServer.url(),
                    "second-model", 0, 16384, false, "");
            store.saveProviders(List.of(first, second), first.id());
            store.saveToken(first.id(), "test-first-secret");
            store.saveToken(second.id(), "test-second-secret");
            try {
                ChatView view = showView();
                ask(view, "Первый вопрос");
                String firstId = view.chats().list().get(0).id();
                view.newChatForTest();
                view.chooseProvider(second.id());
                settle();
                assertEquals(second.id(), view.chatSettings().providerId());
                view.loadModels(false);
                waitUntil(() -> view.modelChoices().contains("second-only"));
                assertFalse(view.modelChoices().contains("first-only"));
                Menu menu = view.modelMenu();
                assertTrue(menu.getItems()[0].getText().contains("Второй"));
                menu.dispose();
                Screenshots.capture(view.control(), "provider-picker");
                ask(view, "Второй вопрос");
                String secondId = view.chats().list().get(0).id();
                assertTrue(view.history().getText().contains("второй"));
                view.openChatForTest(firstId);
                settle();
                assertEquals(first.id(), view.chatSettings().providerId());
                view.loadModels(false);
                waitUntil(() -> view.modelChoices().contains("first-only"));
                assertFalse(view.modelChoices().contains("second-only"));
                ask(view, "Снова первый");
                assertEquals("первый", view.session().messages().get(view.session().messages().size() - 1).text());
                ChatStore reopened = new ChatStore(stateLocation().resolve(ChatView.CHATS_DIRECTORY));
                assertEquals(first.id(), reopened.model(firstId).providerId());
                assertEquals(second.id(), reopened.model(secondId).providerId());
                for (var request : server.requests()) assertEquals("Bearer test-first-secret", request.header("Authorization"));
                for (var request : secondServer.requests()) assertEquals("Bearer test-second-secret", request.header("Authorization"));
                assertEquals(2, server.requests().stream().filter(r -> r.method().equals("POST")).count());
                assertEquals(1, secondServer.requests().stream().filter(r -> r.method().equals("POST")).count());
            } finally {
                store.saveToken(first.id(), "");
                store.saveToken(second.id(), "");
            }
        }
    }

    @Test
    void switchingProviderDuringToolStepKeepsRunningReplyOnOriginalServer() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var posts = new java.util.concurrent.atomic.AtomicInteger();
        server = FakeModelServer.http().handler((request, response) -> {
            if (posts.incrementAndGet() == 1) {
                entered.countDown();
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"list_objects\",\"arguments\":\"{}\"}}]}}]}\n\n",
                        "data: [DONE]\n\n"), 5);
            } else response.sse(List.of(openAiPart("исходный сервер"), "data: [DONE]\n\n"), 5);
        });
        configure(server);
        SettingsStore store = SettingsStore.workspace();
        ApiProvider first = store.providers().get(0);
        ApiProvider second = new ApiProvider("offline", "Другой", Protocol.OPENAI_COMPATIBLE,
                "https://example.invalid", "other", 0, 8192, false, "");
        store.saveProviders(List.of(first, second), first.id());
        ChatView view = showView();
        view.input().setText("Вопрос с инструментом");
        view.send();
        waitUntil(() -> entered.getCount() == 0);
        view.chooseProvider(second.id());
        settle();
        release.countDown();
        waitUntil(() -> !view.session().isBusy());
        view.refresh();
        assertEquals(second.id(), view.chatSettings().providerId());
        assertEquals(2, posts.get(), "следующий шаг цикла остался на исходном подключении");
        assertTrue(view.history().getText().contains("исходный сервер"), view.history().getText());
    }

    @Test
    void deletedProviderRequiresExplicitSelectionEvenIfOnlyOneRemains() throws Exception {
        ChatView view = serverWithModels();
        SettingsStore store = SettingsStore.workspace();
        ask(view, "Старый чат до появления профилей");
        ApiProvider first = store.providers().get(0);
        ApiProvider second = ApiProvider.of("other", "Оставшийся", store.load().withModel("other-model"));
        store.saveProviders(List.of(first, second), first.id());
        settle();
        store.saveProviders(List.of(second), second.id());
        settle();
        assertFalse(view.chatSettings().isConfigured(), "текущий чат не переназначается при удалении его профиля");
        view.chooseModel("cannot-reroute");
        assertTrue(view.status().getText().contains("Провайдер удалён"));
        assertEquals("other-model", store.load().model());
        Menu menu = view.modelMenu();
        assertTrue(menu.getItems()[0].getText().contains("Удалённый провайдер"));
        MenuItem item = menu.getItems()[0].getMenu().getItems()[0];
        item.setSelection(true);
        item.notifyListeners(SWT.Selection, new Event());
        menu.dispose();
        settle();
        assertEquals(second.id(), view.chatSettings().providerId());
        assertTrue(view.chatSettings().isConfigured());
    }

    @Test
    void lateModelListAndPendingCommandCannotChangeAnotherProvider() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            entered.countDown();
            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            response.send(200, "application/json", "{\"data\":[{\"id\":\"first-only\"}]}");
        });
        try (FakeModelServer secondServer = FakeModelServer.http().handler((request, response) ->
                response.send(200, "application/json", "{\"data\":[{\"id\":\"second-only\"}]}"))) {
            configure(server);
            SettingsStore store = SettingsStore.workspace();
            ApiProvider first = store.providers().get(0);
            ApiProvider second = new ApiProvider("late", "Второй", Protocol.OPENAI_COMPATIBLE, secondServer.url(),
                    "second-model", 0, 0, false, "");
            store.saveProviders(List.of(first, second), first.id());
            ChatView view = showView();
            view.input().setText("/model first-only");
            view.send();
            waitUntil(() -> entered.getCount() == 0);
            view.chooseProvider(second.id());
            settle();
            release.countDown();
            waitUntil(() -> view.modelChoices().contains("second-only"));
            assertFalse(view.modelChoices().contains("first-only"));
            assertEquals("second-model", view.chatSettings().model());
            assertEquals("second-model", store.provider(second.id()).model());
        } finally { release.countDown(); }
    }

}
