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

import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.SettingsStore;

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
}
