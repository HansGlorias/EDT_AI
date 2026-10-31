package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.DialogMode;

/** Режим вопроса: инструкция уходит модели с вопросом, после отправки режим сбрасывается. */
class ChatModeTest extends AbstractChatTest {

    private ChatView view() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(openAiPart("Готово"), "data: [DONE]\n\n"), 0));
        configure(server);
        return showView();
    }

    @Test
    void chosenModeGoesWithNextQuestionOnly() throws Exception {
        ChatView view = view();
        Menu menu = view.modeMenu();
        List<String> items = java.util.Arrays.stream(menu.getItems()).map(MenuItem::getText).filter(text -> !text.isEmpty()).toList();
        menu.dispose();
        assertEquals(List.of("Агент — правит модули и вызывает инструменты; перед инструментами MCP спрашивает",
                "Авто — правит модули и вызывает инструменты MCP без вопроса", "План — изучает проект и составляет план, ничего не меняет",
                "Вопросы — только отвечает: проект не меняет, правки предлагает кодом в ответе", "Следующий вопрос:",
                "    Ответ\t/answer", "    Варианты\t/options", "    Рекомендация\t/recommend", "    План\t/plan"), items);

        choose(view.modeMenu(), "План\t");
        assertEquals("Агент · План", view.modeButton().getText());
        assertTrue(view.status().getText().contains("следующий вопрос — «План»"), view.status().getText());

        ask(view, "выгрузка остатков в КД");
        ChatMessage question = view.session().messages().get(0);
        assertEquals("выгрузка остатков в КД", question.text());
        assertEquals(List.of("режим «План»"), question.attachments().stream().map(Attachment::label).toList());
        assertTrue(server.requests().get(0).body().contains("Составь план реализации"), server.requests().get(0).body());
        assertEquals("Агент", view.modeButton().getText(), "после отправки — снова обычный вопрос");

        ask(view, "ещё вопрос");
        assertTrue(view.session().messages().get(2).attachments().isEmpty());
    }

    @Test
    void planModeSendsEveryQuestionWithPlanAndWithoutEditing() throws Exception {
        ChatView view = view();
        choose(view.modeMenu(), "План —");
        assertEquals("План", view.modeButton().getText());

        ask(view, "выгрузка в КД");
        ask(view, "а с отбором?");
        assertEquals(List.of("режим «План»"), view.session().messages().get(2).attachments().stream().map(Attachment::label).toList(),
                "каждый вопрос — с инструкцией плана");
        String tools = String.valueOf(io.github.hansglorias.edtai.core.json.Json.parseObject(server.requests().get(0).body()).get("tools"));
        assertTrue(tools.contains("read_module") && !tools.contains("edit_module"), "инструменты правки не передаются: " + tools);
        view.setAutoApprove(false);
    }

    @Test
    void commandSetsModeOrSendsWithIt() throws Exception {
        ChatView view = view();
        view.input().setText("/options");
        view.send();
        assertEquals("", view.input().getText());
        assertEquals("Агент · Варианты", view.modeButton().getText());
        assertTrue(view.session().messages().isEmpty(), "команда без текста только выбирает режим");

        view.chooseMode(null);
        ask(view, "/recommend какой регистр взять");
        ChatMessage question = view.session().messages().get(0);
        assertEquals("какой регистр взять", question.text());
        assertEquals(List.of("режим «Рекомендация»"), question.attachments().stream().map(Attachment::label).toList());
    }
}
