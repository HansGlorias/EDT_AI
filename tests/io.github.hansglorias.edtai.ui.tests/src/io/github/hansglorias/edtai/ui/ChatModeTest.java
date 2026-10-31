package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.AgentMode;
import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.json.Json;

/** Режим модели: кнопка у поля ввода и {@code /plan}. */
class ChatModeTest extends AbstractChatTest {

    private ChatView view() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(openAiPart("Готово"), "data: [DONE]\n\n"), 0));
        configure(server);
        return showView();
    }

    @Test
    void menuHasAgentModesOnly() throws Exception {
        ChatView view = view();
        Menu menu = view.modeMenu();
        List<String> items = java.util.Arrays.stream(menu.getItems()).map(MenuItem::getText).toList();
        menu.dispose();
        assertEquals(List.of("Агент — правит модули и вызывает инструменты; перед инструментами MCP спрашивает",
                "Авто — правит модули и вызывает инструменты MCP без вопроса", "План — изучает проект и составляет план, ничего не меняет",
                "Вопросы — только отвечает: проект не меняет, правки предлагает кодом в ответе"), items);
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
        assertTrue(server.requests().get(0).body().contains("Составь план реализации"));
        String tools = String.valueOf(Json.parseObject(server.requests().get(0).body()).get("tools"));
        assertTrue(tools.contains("read_module") && !tools.contains("edit_module"), "инструменты правки не передаются: " + tools);
        view.setAgentMode(AgentMode.AGENT);
    }

    @Test
    void planCommandSwitchesModeAndSends() throws Exception {
        ChatView view = view();
        view.input().setText("/plan");
        view.send();
        assertEquals("", view.input().getText());
        assertEquals("План", view.modeButton().getText());
        assertTrue(view.session().messages().isEmpty(), "команда без текста только включает режим");

        view.setAgentMode(AgentMode.AGENT);
        ask(view, "/plan какой регистр взять");
        assertEquals("План", view.modeButton().getText());
        assertEquals("какой регистр взять", view.session().messages().get(0).text());
        assertEquals(List.of("режим «План»"), view.session().messages().get(0).attachments().stream().map(Attachment::label).toList());
        view.setAgentMode(AgentMode.AGENT);
    }
}
