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
import org.eclipse.ui.ide.IDE;
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
    void reviewFindingsShowAsCardAndMarkers() throws Exception {
        String findings = "{\"findings\":[{\"module\":\"ОбщийМодуль.Тест\",\"line\":2,\"severity\":\"error\","
                + "\"title\":\"Сообщить в общем модуле\",\"fix\":\"ОбщегоНазначения.СообщитьПользователю\"}]}";
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("\"role\":\"tool\"")) {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"report_review\",\"arguments\":" + Json.write(findings)
                        + "}}]}}]}\n\n", "data: [DONE]\n\n"), 5);
            } else {
                response.sse(List.of(openAiPart("Одна ошибка."), "data: [DONE]\n\n"), 5);
            }
        });
        configure(server);
        ChatView view = showView();

        ask(view, "Сделай ревью модуля Тест");

        String transcript = withoutTokens(view.history().getText());
        assertTrue(transcript.contains("Ревью · 1 ошибка   " + MessageRenderer.CLEAR_MARKERS + "\n●  ОбщийМодуль.Тест.Модуль:2"
                + "  Сообщить в общем модуле"), transcript);
        org.eclipse.core.resources.IFile file = project.getFile("src/CommonModules/Тест/Module.bsl");
        waitUntil(() -> reviewMarkers(file).length == 1);
        org.eclipse.core.resources.IMarker marker = reviewMarkers(file)[0];
        assertEquals(2, marker.getAttribute(org.eclipse.core.resources.IMarker.LINE_NUMBER, 0));
        assertEquals(org.eclipse.core.resources.IMarker.SEVERITY_ERROR, marker.getAttribute(org.eclipse.core.resources.IMarker.SEVERITY, -1));
        assertEquals("EDT AI: Сообщить в общем модуле. Исправить: ОбщегоНазначения.СообщитьПользователю",
                marker.getAttribute(org.eclipse.core.resources.IMarker.MESSAGE, ""));
        Screenshots.capture(view.control(), "review");

        // В тестовой платформе нет BSL-редактора EDT; не использовать ассоциацию .bsl в ОС.
        IDE.setDefaultEditor(file, "org.eclipse.ui.DefaultTextEditor");
        view.linkAction("ОбщийМодуль.Тест.Модуль:2").run();
        settle();
        assertEquals(file, page.getActiveEditor().getEditorInput().getAdapter(org.eclipse.core.resources.IFile.class));

        view.linkAction(MessageRenderer.CLEAR_MARKERS).run();
        waitUntil(() -> reviewMarkers(file).length == 0);
        page.closeAllEditors(false);
    }

    private static org.eclipse.core.resources.IMarker[] reviewMarkers(org.eclipse.core.resources.IFile file) {
        try {
            return file.findMarkers(ReviewMarkers.TYPE, false, 0);
        } catch (org.eclipse.core.runtime.CoreException e) {
            return new org.eclipse.core.resources.IMarker[0];
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
        assertEquals(List.of("project_info", "list_objects", "read_module", "search_code", "edit_module", "skill", "save_skill", "report_review", "attach_file", "git_status", "git_diff", "git_log", "git_restore"), names);
        List<Object> messages = Json.asList(Json.parseObject(server.requests().get(1).body()).get("messages"));
        String result = Json.stringAt(messages.get(messages.size() - 1), "content");
        assertTrue(result.contains("ОбщийМодуль.Тест.Модуль (проект " + PROJECT + ", src/CommonModules/Тест/Module.bsl)"), result);
        assertTrue(result.contains("2  \tСообщить(\"Тест\");"), result);

        String transcript = view.history().getText();
        assertTrue(transcript.matches("(?s).*строки 1–3 из 3 · ↑≈\\S+ ↓≈\\S+\n.*"), "токены шага у вызова: " + transcript);
        transcript = withoutTokens(transcript);
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
        assertEquals("Агент", view.modeButton().getText());

        choose(view.modeMenu(), "Вопросы");
        assertTrue(view.status().getText().startsWith("Режим «Вопросы»: только отвечает"), view.status().getText());
        assertEquals("Вопросы", view.modeButton().getText(), "режим на кнопке");
        view.setAutoApprove(true);
        assertEquals("Авто", view.modeButton().getText());
        Screenshots.capture(view.control(), "composer-modes");
        choose(view.modeMenu(), "Вопросы");
        ask(view, "Исправь модуль Тест");

        Map<String, Object> request = Json.parseObject(server.requests().get(0).body());
        List<String> names = Json.asList(request.get("tools")).stream().map(tool -> Json.stringAt(tool, "function", "name")).toList();
        assertEquals(List.of("project_info", "list_objects", "read_module", "search_code", "skill", "save_skill", "report_review", "attach_file", "git_status", "git_diff",
                "git_log"), names, "скиллы проект не меняют — остаются");
        String system = Json.stringAt(Json.asList(request.get("messages")).get(0), "content");
        assertTrue(system.contains(ProjectBrief.READ_ONLY_RULES), system);
        assertTrue(view.session().messages().get(0).attachments().isEmpty(), "«Вопросы» — без инструкции плана");

        view.input().setText("/readonly");
        view.send();
        assertEquals("Агент", view.modeButton().getText(), "команда переключает режим");
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
    void fileChangedByUserDuringAnswerIsNotModelEdit() throws Exception {
        IFile file = project.getFile("src/CommonModules/Тест/Module.bsl");
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("\"role\":\"tool\"")) {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"read_module\",\"arguments\":" + Json.write("{\"module\":\"ОбщийМодуль.Тест\"}")
                        + "}}]}}]}\n\n", "data: [DONE]\n\n"), 5);
            } else {
                // Пока модель пишет ревью, файл правит пользователь (или другой чат).
                file.setContents(new ByteArrayInputStream("// правка пользователя\n".getBytes(StandardCharsets.UTF_8)), true, false, null);
                response.sse(List.of(openAiPart("Замечаний нет."), "data: [DONE]\n\n"), 5);
            }
        });
        configure(server);
        ChatView view = showView();

        ask(view, "Сделай ревью");
        settle();
        view.refresh();

        String transcript = view.history().getText();
        assertTrue(transcript.contains("Замечаний нет."), transcript);
        assertFalse(transcript.contains(MessageRenderer.CHANGED), transcript);
        assertNull(view.linkAction(MessageRenderer.REVERT), "откатывать правку пользователя нельзя");
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
