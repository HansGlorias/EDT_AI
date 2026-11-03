package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jgit.api.Git;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.AgentMode;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.PlanApproval;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;

class ChatQuestionsTest extends AbstractChatTest {
    private static final Map<String, Object> QUESTION = Json.object("id", "empty", "text", "Что делать с пустым значением?",
            "options", List.of(Json.object("id", "error", "text", "Вызвать исключение"),
                    Json.object("id", "zero", "text", "Вернуть ноль")));

    private ChatView view(boolean three) throws Exception {
        List<Object> questions = three ? List.of(QUESTION, Json.object("id", "scope", "text", "Где менять?", "options", List.of(
                Json.object("id", "here", "text", "Только здесь"), Json.object("id", "all", "text", "Везде"),
                Json.object("id", "callers", "text", "В вызывающих методах"))), Json.object("id", "check", "text", "Как проверить?"))
                : List.of(QUESTION);
        server = FakeModelServer.http().handler((request, response) -> {
            if (request.body().contains(PlanApproval.EXECUTE)) {
                response.sse(List.of(openAiPart("Выполнение начато"), "data: [DONE]\n\n"), 0);
            } else if (request.body().contains("\"role\":\"tool\"")) {
                response.sse(List.of(openAiPart("План: проверить пустое значение, затем перечитать модуль и проверить diff."), "data: [DONE]\n\n"), 0);
            } else {
                tool(response, "ask_user", Json.write(Json.object("questions", questions)));
            }
        });
        configure(server);
        return showView();
    }

    private static void start(ChatView view, String text) {
        view.input().setText(text);
        view.send();
        waitUntil(() -> view.questionsBar().isShown());
    }

    @Test
    void approvedPlanActuallyEditsThenRereadsAndDiffsTheModule() throws Exception {
        var project = ResourcesPlugin.getWorkspace().getRoot().getProject("QuestionsDemo");
        project.create(null);
        project.open(null);
        String before = "Функция ПроверитьПараметр(Параметр)\n\tВозврат Параметр;\nКонецФункции\n";
        String replacement = "Если Параметр = Неопределено Тогда\n\t\tВозврат 0;\n\tКонецЕсли;\n\tВозврат Параметр;";
        String after = before.replace("Возврат Параметр;", replacement);
        var path = project.getLocation().toFile().toPath().resolve("src/CommonModules/Тест/Module.bsl");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, before);
            project.refreshLocal(IResource.DEPTH_INFINITE, null);
            try (Git git = Git.init().setDirectory(project.getLocation().toFile()).call()) {
                git.add().addFilepattern(".").call();
                git.commit().setMessage("initial").setAuthor("Test", "test@example.invalid").setCommitter("Test", "test@example.invalid").call();
            }
            AtomicInteger step = new AtomicInteger();
            Map<String, Object> module = Json.object("project", project.getName(), "module", "ОбщийМодуль.Тест");
            server = FakeModelServer.http().handler((request, response) -> {
                switch (step.getAndIncrement()) {
                    case 0 -> tool(response, "ask_user", Json.write(Json.object("questions", List.of(QUESTION))));
                    case 1 -> response.sse(List.of(openAiPart("План: вернуть ноль в выбранной функции; перечитать и проверить diff."), "data: [DONE]\n\n"), 0);
                    case 2 -> tool(response, "edit_module", Json.write(Json.object("project", project.getName(), "module", "ОбщийМодуль.Тест",
                            "old_text", "Возврат Параметр;", "new_text", replacement)));
                    case 3 -> tool(response, "read_module", Json.write(module));
                    case 4 -> tool(response, "git_diff", Json.write(module));
                    default -> response.sse(List.of(openAiPart("Правка прочитана, diff проверен."), "data: [DONE]\n\n"), 0);
                }
            });
            configure(server);
            ChatView view = showView();
            view.setAgentMode(AgentMode.PLAN);
            start(view, "Уточни и составь план правки");
            select(view.questionsBar().options().get(1));
            click(view.questionsBar().submitButton());
            waitUntil(() -> !view.session().isBusy());
            view.refresh();
            assertEquals(before, Files.readString(path), "после уточнений код не менялся");
            click(view.approvePlanButton());
            waitUntil(() -> !view.session().isBusy());
            assertEquals(after, Files.readString(path).replace("\r\n", "\n"));
            var uses = view.session().messages().get(3).tools();
            assertEquals(List.of("edit_module", "read_module", "git_diff"), uses.stream().map(ToolUse::name).toList());
            assertTrue(uses.stream().allMatch(use -> use.status() == ToolUse.Status.DONE), uses.toString());
            assertTrue(server.requests().get(4).body().contains("Возврат 0;"), "модель получила перечитанный код");
            assertTrue(server.requests().get(5).body().contains("+"), "модель получила diff");
            assertEquals(AgentMode.AGENT, ChatView.agentMode());
        } finally {
            page.closeAllEditors(false);
            project.delete(true, true, null);
        }
    }

    @Test
    void threeQuestionsWaitForExplicitSubmitAndPlanNeedsExplicitApproval() throws Exception {
        ChatView view = view(true);
        view.setAgentMode(AgentMode.PLAN);
        start(view, "Составь план маленькой правки");
        UserQuestionsBar form = view.questionsBar();
        assertEquals(3, form.inputs().size());
        assertFalse(form.submitButton().isEnabled());
        select(form.options().get(1));
        select(form.options().get(2));
        assertFalse(form.submitButton().isEnabled(), "нужен ответ на третий вопрос");
        form.inputs().get(2).setText("Вручную на пустом значении");
        assertTrue(form.submitButton().isEnabled());
        settle();
        assertEquals(1, server.requests().size(), "выбор и набор текста ещё не отправка");
        assertEquals(AgentMode.PLAN, ChatView.agentMode());
        Screenshots.capture(view.control(), "user-questions");

        click(form.submitButton());
        click(form.submitButton());
        waitUntil(() -> !view.session().isBusy());
        view.refresh();
        assertEquals(2, server.requests().size());
        assertEquals(AgentMode.PLAN, ChatView.agentMode(), "ответы не утверждают план");
        assertTrue(view.approvePlanButton().getVisible());
        assertTrue(server.requests().get(1).body().contains("Вернуть ноль"));
        assertTrue(server.requests().get(1).body().contains("Вручную на пустом значении"));
        Screenshots.capture(view.control(), "plan-approval");
        click(view.approvePlanButton());
        click(view.approvePlanButton());
        waitUntil(() -> !view.session().isBusy());
        assertEquals(3, server.requests().size());
        assertEquals(AgentMode.AGENT, ChatView.agentMode());
        assertTrue(server.requests().get(2).body().contains("edit_module"));
        assertTrue(server.requests().get(2).body().contains("Ответы на уточнения"));
    }

    @Test
    void ownTextOverridesOptionAndStopInvalidatesPendingSubmit() throws Exception {
        ChatView view = view(false);
        start(view, "Уточни задачу");
        select(view.questionsBar().options().get(0));
        view.questionsBar().inputs().get(0).setText("Вернуть Неопределено");
        click(view.questionsBar().submitButton());
        waitUntil(() -> !view.session().isBusy());
        assertTrue(server.requests().get(1).body().contains("Вернуть Неопределено"));
        assertTrue(server.requests().get(1).body().contains("\\\"option_id\\\":\\\"\\\""));

        view.newChatForTest();
        start(view, "Второе уточнение");
        view.questionsBar().inputs().get(0).setText("поздний ответ");
        view.session().cancel();
        click(view.questionsBar().submitButton());
        waitUntil(() -> !view.session().isBusy() && !view.questionsBar().isShown());
        assertEquals(3, server.requests().size());
        assertEquals(ChatMessage.Status.STOPPED, view.session().messages().get(1).status());
    }

    @Test
    void twoChatsKeepTheirOwnQuestionsAndDraftAnswers() throws Exception {
        ChatView view = view(false);
        start(view, "чат первый");
        view.refresh();
        String first = view.chats().list().get(0).id();
        var firstSession = view.session();
        view.questionsBar().inputs().get(0).setText("Ответ первого чата");
        view.newChatForTest();
        assertFalse(view.questionsBar().isShown());
        start(view, "чат второй");
        view.questionsBar().inputs().get(0).setText("Ответ второго чата");
        click(view.questionsBar().submitButton());
        waitUntil(() -> !view.session().isBusy());
        assertTrue(firstSession.isBusy());
        assertEquals(3, server.requests().size());
        assertTrue(server.requests().get(2).body().contains("Ответ второго чата"));
        assertFalse(server.requests().get(2).body().contains("Ответ первого чата"));

        view.openChatForTest(first);
        assertTrue(view.questionsBar().isShown());
        assertEquals("Ответ первого чата", view.questionsBar().inputs().get(0).getText());
        click(view.questionsBar().submitButton());
        waitUntil(() -> !view.session().isBusy());
        assertEquals(4, server.requests().size());
        assertTrue(server.requests().get(3).body().contains("Ответ первого чата"));
        assertFalse(server.requests().get(3).body().contains("Ответ второго чата"));
    }

    @Test
    void closeAndReopenCancelsPendingAndRetryCreatesANewQuestion() throws Exception {
        ChatView view = view(false);
        start(view, "Уточни перед планом");
        page.hideView(view);
        view = showView();
        assertFalse(view.session().isBusy());
        assertFalse(view.questionsBar().isShown());
        assertEquals(ToolUse.Problem.INTERRUPTED, view.session().messages().get(1).tools().get(0).problem());
        assertEquals(1, server.requests().size());
        view.session().retry();
        ChatView reopened = view;
        waitUntil(() -> reopened.questionsBar().isShown());
        reopened.questionsBar().inputs().get(0).setText("Новый ответ");
        click(reopened.questionsBar().submitButton());
        waitUntil(() -> !reopened.session().isBusy());
        assertEquals(3, server.requests().size());
    }

    private static void select(Button option) { option.setSelection(true); click(option); }
    private static void click(Button button) { button.notifyListeners(SWT.Selection, new Event()); }

    static void tool(FakeModelServer.Response response, String name, String arguments) throws Exception {
        response.send(200, "application/json", Json.write(Json.object("choices", List.of(Json.object("message",
                Json.object("role", "assistant", "content", "", "tool_calls", List.of(Json.object("id", name + "-call", "type", "function",
                        "function", Json.object("name", name, "arguments", arguments)))))))));
    }
}
