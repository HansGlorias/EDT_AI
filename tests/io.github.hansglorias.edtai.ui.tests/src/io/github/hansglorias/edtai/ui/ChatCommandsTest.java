package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.fieldassist.IContentProposal;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.ChatStore;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.SettingsStore;

/** Команды через «/» и строка состояния: что делает модель, токены. */
class ChatCommandsTest extends AbstractChatTest {

    @Test
    void reviewCommandSendsPromptWithRemark() throws Exception {
        answering(List.of("Замечаний нет."));
        ChatView view = showView();

        ask(view, "/review  только модуль Общий");

        assertEquals(ChatView.REVIEW_PROMPT + "\n\nтолько модуль Общий", view.session().messages().get(0).text());
        assertEquals("", view.input().getText());
    }

    @Test
    void explainAndTestsNeedCode() throws Exception {
        answering(List.of("Объясняю."));
        ChatView view = showView();

        view.input().setText("/explain");
        view.send();
        assertEquals(List.of(), view.session().messages(), "нет ни редактора, ни прикреплённого, ни уточнения");
        assertTrue(view.status().getText().startsWith("Выделите код в редакторе"), view.status().getText());
        assertEquals(0, server.requests().size());

        ask(view, "/explain ОбщийМодуль.Тест.Проверить");
        assertEquals(ChatView.EXPLAIN_PROMPT + "\n\nОбщийМодуль.Тест.Проверить", view.session().messages().get(0).text());
        ask(view, "/tests ОбщийМодуль.Тест");
        assertEquals(ChatView.TESTS_PROMPT + "\n\nОбщийМодуль.Тест", view.session().messages().get(2).text());
    }

    @Test
    void compactCommandSummarizesHistory() throws Exception {
        answering(List.of("Кратко о диалоге."));
        ChatView view = showView();
        view.input().setText("/compact");
        view.send();
        assertTrue(view.status().getText().startsWith("Сжимать нечего"), view.status().getText());

        ask(view, "привет");
        view.input().setText("/compact");
        view.send();
        waitUntil(() -> view.session().messages().size() == 3 && !view.session().isBusy(),
                () -> view.session().messages().toString());
        assertEquals(ChatRole.SUMMARY, view.session().messages().get(2).role());
        assertEquals("Кратко о диалоге.", view.session().messages().get(2).text());
        assertTrue(server.requests().get(1).body().contains("Сожми переписку"), "второй запрос — краткое содержание");
        waitUntil(() -> view.status().getText().startsWith("История сжата"), () -> view.status().getText());
        waitUntil(() -> view.history().getText().contains("Краткое содержание диалога выше"), () -> view.history().getText());
    }

    @Test
    void mcpCommandShowsServers() throws Exception {
        answering(List.of("Ответ."));
        ChatView view = showView();
        List<String> shown = new ArrayList<>();
        view.mcpStatus = shown::add;

        view.input().setText("/mcp");
        view.send();
        waitUntil(() -> !shown.isEmpty(), () -> view.status().getText());
        assertEquals(List.of("MCP-серверы не настроены. Добавить: Настройки → EDT AI → MCP-серверы."), shown);
        assertEquals(0, server.requests().size(), "модели ничего не уходит");
    }

    @Test
    void chatTitleIsGenerated() throws Exception {
        answering(List.of("«Текущая дата", " сеанса»."));
        InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER).putBoolean(ChatView.GENERATE_TITLES, true);
        ChatView view = showView();
        ask(view, "как получить текущую дату");

        waitUntil(() -> server.requests().size() == 2, () -> String.valueOf(server.requests().size()));
        assertTrue(server.requests().get(1).body().contains("Придумай короткое название"), server.requests().get(1).body());
        waitUntil(() -> chatTitle(view).equals("Текущая дата сеанса"), () -> chatTitle(view));

        ask(view, "а время?");
        settle();
        assertEquals(3, server.requests().size(), "название — один раз на чат");

        view.chats().rename(view.historyFile().path().getFileName().toString().replace(".json", ""), "Моё");
        view.newChatForTest();
        ask(view, "второй");
        waitUntil(() -> server.requests().size() == 5, () -> String.valueOf(server.requests().size()));
        waitUntil(() -> chatTitle(view).equals("Текущая дата сеанса"), () -> chatTitle(view));
        assertEquals(List.of("Текущая дата сеанса", "Моё"), view.chats().list().stream().map(ChatStore.Entry::title).toList(),
                "у нового чата — своё название, данное пользователем не меняется");
    }

    private static String chatTitle(ChatView view) {
        try {
            return view.chats().list().get(0).title();
        } catch (IOException e) {
            return e.toString();
        }
    }

    @Test
    void skillsAreInvokedListedAndCreated() throws Exception {
        answering(List.of("Готово."));
        ChatView view = showView();

        IContentProposal[] proposals = view.inputAssist().proposals("/q", 2);
        assertTrue(Arrays.stream(proposals).anyMatch(proposal -> proposal.getContent().equals("/query-optimize")),
                Arrays.toString(proposals));

        ask(view, "/query-optimize  проверь запрос в ОбщийМодуль.Склад");
        ChatMessage question = view.session().messages().get(0);
        assertEquals("проверь запрос в ОбщийМодуль.Склад", question.text());
        assertEquals(List.of("скилл query-optimize"), question.attachments().stream().map(Attachment::label).toList());
        Map<String, Object> request = Json.parseObject(server.requests().get(0).body());
        List<Object> messages = Json.asList(request.get("messages"));
        assertTrue(Json.stringAt(messages.get(0), "content").contains("Скиллы — проверенные инструкции"), "список скиллов в промпте");
        String sent = Json.stringAt(messages.get(messages.size() - 1), "content");
        assertTrue(sent.startsWith("Выполни задачу по скиллу «query-optimize» — инструкция:\n```markdown\n# Оптимизация запроса 1С"), sent);
        assertTrue(view.history().getText().contains(MessageRenderer.FILES + "скилл query-optimize"), view.history().getText());

        ask(view, "/method-docs");
        assertEquals("Выполни скилл «method-docs».", view.session().messages().get(2).text());

        view.input().setText("/skill");
        view.send();
        assertTrue(view.status().getText().startsWith("Опишите после /skill"), view.status().getText());
        ask(view, "/skill оформление печатных форм");
        ChatMessage create = view.session().messages().get(4);
        assertEquals(ChatView.SKILL_PROMPT + "\n\nоформление печатных форм", create.text());
        assertEquals("create-skill", create.attachments().get(0).name());

        List<String> shown = new ArrayList<>();
        List<java.nio.file.Path> opened = new ArrayList<>();
        view.skillsDialog = text -> shown.add(text);
        view.openFile = opened::add;
        view.input().setText("/skills");
        view.send();
        assertTrue(shown.get(0).startsWith("Встроенные:\n  /client-server — "), shown.get(0));
        assertEquals(1, opened.size(), "«Открыть папку своих скиллов»");
        assertTrue(opened.get(0).endsWith("skills"), opened.toString());
        assertEquals(3, server.requests().size(), "/skills модели не отправляется");
    }

    @Test
    void newAndAutoRunWithoutModel() throws Exception {
        answering(List.of("Привет."));
        ChatView view = showView();
        ask(view, "привет");

        view.input().setText("/new");
        view.send();
        assertEquals(List.of(), view.session().messages());
        assertEquals("", view.input().getText());
        assertEquals(1, server.requests().size(), "команда модели не отправляется");

        view.input().setText("/auto");
        view.send();
        assertTrue(autoApprove());
        assertTrue(view.status().getText().startsWith("Авторежим включён"), view.status().getText());
        view.input().setText("/AUTO");
        view.send();
        assertFalse(autoApprove());
    }

    @Test
    void statusShowsWhatModelDoesThenTokens() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(openAiPart("Раз, "), openAiPart("два."),
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1234,\"completion_tokens\":56}}\n\n", "data: [DONE]\n\n"), 300));
        configure(server);
        ChatView view = showView();
        assertTrue(view.status().getText().startsWith("test-model · "), view.status().getText());

        view.input().setText("Считай");
        view.send();
        waitUntil(() -> view.status().getText().matches("test-model · (думает|пишет ответ)… \\d+ с"), () -> view.status().getText());
        // Новый чат — в истории сразу, пока ответ ещё идёт.
        waitUntil(() -> {
            try {
                return view.session().isBusy() && view.chats().list().size() == 1;
            } catch (IOException e) {
                return false;
            }
        }, () -> "чат не сохранён до конца ответа");
        waitUntil(() -> !view.session().isBusy());
        settle();

        assertEquals("test-model · контекст 1,3k из 32k · ответ ↑1,2k ↓56", view.status().getText());
    }

    @Test
    void openedChatIsScrolledToEnd() throws Exception {
        answering(List.of("Ок."));
        ChatView view = showView();
        String id = view.chats().newId();
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            messages.add(ChatMessage.of(ChatRole.USER, "Вопрос " + i));
            messages.add(ChatMessage.of(ChatRole.ASSISTANT, "Ответ " + i + "\n\nстрока\nстрока\nстрока"));
        }
        view.chats().file(id).save(messages);

        view.openChatForTest(id);

        waitUntil(() -> view.history().getTopPixel() > 0
                && view.history().getLinePixel(view.history().getLineCount() - 1) < view.history().getClientArea().height,
                () -> "лента не внизу: " + view.history().getTopPixel());
    }

    private static boolean autoApprove() {
        return InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER).getBoolean(ChatView.AUTO_APPROVE, false);
    }
}
