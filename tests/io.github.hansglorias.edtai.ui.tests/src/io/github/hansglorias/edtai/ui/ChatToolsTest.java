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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.settings.SettingsStore;

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
        assertEquals(List.of("project_info", "list_objects", "read_module", "search_code", "git_status", "git_diff", "git_log"), names);
        List<Object> messages = Json.asList(Json.parseObject(server.requests().get(1).body()).get("messages"));
        String result = Json.stringAt(messages.get(messages.size() - 1), "content");
        assertTrue(result.contains("ОбщийМодуль.Тест.Модуль (проект " + PROJECT + ", src/CommonModules/Тест/Module.bsl)"), result);
        assertTrue(result.contains("2  \tСообщить(\"Тест\");"), result);

        String transcript = view.history().getText();
        assertTrue(transcript.contains("\n" + MessageRenderer.TOOL + "Читает модуль ОбщийМодуль.Тест — строки 1–3 из 3\n"
                + "Посмотрю модуль.\n\nВ модуле одна процедура Тест."), transcript);
        assertEquals(List.of(ToolUse.Status.DONE), view.session().messages().get(1).tools().stream().map(ToolUse::status).toList());
        Screenshots.capture(view.control(), "tools");

        page.hideView(view);
        ChatView reopened = showView();
        assertTrue(reopened.history().getText().contains(MessageRenderer.TOOL + "Читает модуль ОбщийМодуль.Тест — строки 1–3 из 3"));
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
