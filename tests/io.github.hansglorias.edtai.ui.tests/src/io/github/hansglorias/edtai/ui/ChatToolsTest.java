package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.settings.SettingsStore;
import io.github.hansglorias.edtai.core.tools.ProjectBrief;

/**
 * Инструменты, этап 5 (MVP): модель вызывает инструмент чтения проекта,
 * результат уходит ей обратно, в ленте видно, что она прочитала.
 */
class ChatToolsTest extends AbstractChatTest {

    private static final String PROJECT = "edtai-tools-test";
    private static final String CODE = "Процедура Тест() Экспорт\n\tСообщить(\"Тест\");\nКонецПроцедуры\n";

    private IProject project;

    @BeforeEach
    void createProject() throws Exception {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT);
        if (project.exists()) {
            project.delete(true, true, null);
        }
        project.create(null);
        project.open(null);
        createFile("src/Configuration/Configuration.mdo", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<mdclass:Configuration xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\"><name>Демо</name>"
                + "<version>2.0</version><compatibilityMode>8.3.24</compatibilityMode></mdclass:Configuration>\n");
        createFile("src/CommonModules/Тест/Module.bsl", CODE);
    }

    @AfterEach
    void deleteProject() throws Exception {
        if (project != null && project.exists()) {
            project.delete(true, true, null);
        }
    }

    @Test
    void modelReadsModuleThroughTool() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("\"role\":\"tool\"")) {
                response.sse(List.of(openAiPart("Посмотрю модуль."),
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                                + "\"function\":{\"name\":\"read_module\",\"arguments\":" + Json.write("{\"module\":\"ОбщийМодуль.Тест\"}")
                                + "}}]}}]}\n\n",
                        "data: [DONE]\n\n"), 5);
            } else {
                response.sse(List.of(openAiPart("В модуле одна процедура **Тест**."), "data: [DONE]\n\n"), 5);
            }
        });
        configure(server);
        ChatView view = showView();

        ask(view, "Что в общем модуле Тест?");

        Map<String, Object> first = Json.parseObject(server.requests().get(0).body());
        List<String> names = Json.asList(first.get("tools")).stream().map(tool -> Json.stringAt(tool, "function", "name")).toList();
        assertEquals(List.of("project_info", "list_objects", "read_module", "search_code", "edit_module", "git_status", "git_diff", "git_log", "git_restore"), names);
        List<Object> messages = Json.asList(Json.parseObject(server.requests().get(1).body()).get("messages"));
        String result = Json.stringAt(messages.get(messages.size() - 1), "content");
        assertTrue(result.contains("ОбщийМодуль.Тест.Модуль (проект " + PROJECT + ", src/CommonModules/Тест/Module.bsl)"), result);
        assertTrue(result.contains("2  \tСообщить(\"Тест\");"), result);

        String transcript = view.history().getText();
        // Вызов — между текстом шагов, там, где модель его сделала.
        assertTrue(transcript.contains("\nПосмотрю модуль.\n\n" + MessageRenderer.TOOL
                + "Читает модуль ОбщийМодуль.Тест — строки 1–3 из 3\nВ модуле одна процедура Тест."), transcript);
        assertEquals(List.of(ToolUse.Status.DONE), view.session().messages().get(1).tools().stream().map(ToolUse::status).toList());
        Screenshots.capture(view.control(), "tools");

        page.hideView(view);
        ChatView reopened = showView();
        assertTrue(reopened.history().getText().contains(MessageRenderer.TOOL + "Читает модуль ОбщийМодуль.Тест — строки 1–3 из 3"));
    }

    @Test
    void manyCallsAreCollapsed() throws Exception {
        StringBuilder calls = new StringBuilder();
        String[] queries = { "Сообщить", "Тест", "Процедура", "КонецПроцедуры" };
        for (int i = 0; i < queries.length; i++) {
            calls.append(i == 0 ? "" : ",").append("{\"index\":").append(i).append(",\"id\":\"call_").append(i)
                    .append("\",\"type\":\"function\",\"function\":{\"name\":\"search_code\",\"arguments\":")
                    .append(Json.write("{\"query\":\"" + queries[i] + "\"}")).append("}}");
        }
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("\"role\":\"tool\"")) {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"tool_calls\":[" + calls + "]}}]}\n\n", "data: [DONE]\n\n"), 5);
            } else {
                response.sse(List.of(openAiPart("Нашёл."), "data: [DONE]\n\n"), 5);
            }
        });
        configure(server);
        ChatView view = showView();

        ask(view, "Где что?");

        String transcript = view.history().getText();
        assertTrue(transcript.contains(MessageRenderer.TOOL + "Вызовы инструментов: 4   " + MessageRenderer.SHOW_CALLS + "\nНашёл."),
                transcript);
        assertFalse(transcript.contains("Ищет"), "отдельные вызовы свёрнуты");
        view.linkAction(MessageRenderer.SHOW_CALLS).run();
        transcript = view.history().getText();
        assertTrue(transcript.contains(MessageRenderer.HIDE_CALLS + "\n" + MessageRenderer.TOOL + "Ищет"), transcript);
        view.linkAction(MessageRenderer.HIDE_CALLS).run();
        assertFalse(view.history().getText().contains("Ищет"));
    }

    @Test
    void readOnlyModeDropsEditingTools() throws Exception {
        answering(List.of("Покажу исправление кодом."));
        configure(server);
        ChatView view = showView();
        assertFalse(view.readOnlyButton().getSelection());

        view.readOnlyButton().setSelection(true);
        view.readOnlyButton().notifyListeners(SWT.Selection, new Event());
        assertTrue(view.status().getText().startsWith("Только вопросы: модель не меняет проект"), view.status().getText());
        ask(view, "Исправь модуль Тест");

        Map<String, Object> request = Json.parseObject(server.requests().get(0).body());
        List<String> names = Json.asList(request.get("tools")).stream().map(tool -> Json.stringAt(tool, "function", "name")).toList();
        assertEquals(List.of("project_info", "list_objects", "read_module", "search_code", "git_status", "git_diff", "git_log"), names);
        String system = Json.stringAt(Json.asList(request.get("messages")).get(0), "content");
        assertTrue(system.contains(ProjectBrief.READ_ONLY_RULES), system);

        view.input().setText("/readonly");
        view.send();
        assertFalse(view.readOnlyButton().getSelection(), "команда переключает режим");
        ask(view, "А теперь исправь");
        Map<String, Object> second = Json.parseObject(server.requests().get(1).body());
        assertTrue(Json.write(second.get("tools")).contains("edit_module"));
        assertFalse(Json.stringAt(Json.asList(second.get("messages")).get(0), "content").contains("Режим «только вопросы»"));
    }

    @Test
    void toolsCanBeTurnedOff() throws Exception {
        answering(List.of("Без инструментов."));
        SettingsStore.workspace().save(new AiSettings(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", 0, "", 0, false, false, "",
                new ArrayList<>()));
        ChatView view = showView();

        ask(view, "Что в проекте?");

        assertNull(Json.parseObject(server.requests().get(0).body()).get("tools"));
        assertFalse(view.history().getText().contains(MessageRenderer.TOOL));
    }

    @Test
    void modelEditsModuleAndFileIsSaved() throws Exception {
        String arguments = Json.write(Json.object("module", "ОбщийМодуль.Тест", "old_text", "\tСообщить(\"Тест\");",
                "new_text", "\tСообщить(\"Изменено\");"));
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("\"role\":\"tool\"")) {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"function\":{\"name\":\"edit_module\",\"arguments\":" + Json.write(arguments) + "}}]}}]}\n\n",
                        "data: [DONE]\n\n"), 5);
            } else {
                response.sse(List.of(openAiPart("Поправил."), "data: [DONE]\n\n"), 5);
            }
        });
        configure(server);
        ChatView view = showView();

        ask(view, "Поменяй текст сообщения");

        IFile file = project.getFile("src/CommonModules/Тест/Module.bsl");
        try (var in = file.getContents()) {
            assertEquals(CODE.replace("\"Тест\");", "\"Изменено\");"), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertTrue(view.history().getText().contains(MessageRenderer.TOOL + "Правит модуль ОбщийМодуль.Тест — строка 2"),
                view.history().getText());

        // Правки ответа — дифф и откат одной ссылкой.
        waitUntil(() -> view.linkAction(MessageRenderer.REVERT) != null, () -> view.history().getText());
        assertTrue(view.linkAction(MessageRenderer.CHANGES) != null);
        assertTrue(view.history().getText().contains(MessageRenderer.CHANGED + "Тест/Module.bsl"), view.history().getText());
        assertTrue(view.linkAction("Тест/Module.bsl") != null, "файл в строке «Изменено:» — ссылка на сравнение");
        Screenshots.capture(view.control(), "model-edit");
        view.linkAction(MessageRenderer.CHANGES).run();
        waitUntil(() -> java.util.Arrays.stream(page.getEditorReferences())
                .anyMatch(editor -> editor.getTitle().equals("Правки модели: Module.bsl")), () -> "окно сравнения не открылось");
        page.closeAllEditors(false);
        view.linkAction(MessageRenderer.REVERT).run();
        try (var in = file.getContents()) {
            assertEquals(CODE, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        view.refresh();
        assertNull(view.linkAction(MessageRenderer.REVERT), "второй раз откатывать нечего");
    }

    @Test
    void reviewMenuSendsPrompt() throws Exception {
        answering(List.of("Изменений нет."));
        SettingsStore.workspace().save(new AiSettings(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", 0, "", 0, true, false, "",
                new ArrayList<>()));
        ChatView view = showView();

        Action review = null;
        for (IContributionItem item : view.getViewSite().getActionBars().getMenuManager().getItems()) {
            if (item instanceof ActionContributionItem action && ChatView.REVIEW_CHANGES.equals(action.getAction().getText())) {
                review = (Action) action.getAction();
            }
        }
        assertNotNull(review, "пункт меню вида");
        review.run();
        waitUntil(() -> !view.session().isBusy() && view.session().messages().size() == 2);

        assertEquals(ChatView.REVIEW_PROMPT, view.session().messages().get(0).text());
    }

    private void createFile(String path, String text) throws Exception {
        IFile file = project.getFile(path);
        createParents(file.getParent());
        file.create(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), true, null);
    }

    private static void createParents(IContainer container) throws Exception {
        if (container instanceof IFolder folder && !folder.exists()) {
            createParents(folder.getParent());
            folder.create(true, true, null);
        }
    }
}
