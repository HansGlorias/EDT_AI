package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.bindings.TriggerSequence;
import org.eclipse.jface.fieldassist.IContentProposal;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Link;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.keys.IBindingService;
import org.eclipse.ui.menus.IMenuService;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorActionConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.SlashCommand;
import io.github.hansglorias.edtai.core.context.ContextItem;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.settings.SettingsStore;

/**
 * Контекст EDT, этап 4: «Спросить AI о выделенном», панель фрагментов над
 * полем ввода, фрагменты в ленте и в запросе к модели, «Прикрепить модуль…»,
 * вставка кода с отступом.
 */
class ChatContextTest extends AbstractChatTest {

    private static final String PROJECT = "edtai-context-test";
    private static final String MODULE_PATH = "src/CommonModules/Тест/Module.bsl";
    private static final String NAME = "ОбщийМодуль.Тест.Модуль";
    private static final String MODULE = String.join("\n",
            "// Сложение двух чисел.", // 1
            "&НаСервере", // 2
            "Функция Сумма(А, Б) Экспорт", // 3
            "\tВозврат А + Б;", // 4
            "КонецФункции", // 5
            "", // 6
            "Процедура Вывести() Экспорт", // 7
            "\tСообщить(Сумма(1, 2));", // 8
            "\tСообщить(\"Готово\");", // 9
            "КонецПроцедуры", // 10
            "", // 11
            "// Конец модуля") + "\n"; // 12

    private IProject project;
    private IFile module;

    @BeforeEach
    void createProject() throws Exception {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT);
        if (project.exists()) {
            project.delete(true, true, null);
        }
        project.create(null);
        project.open(null);
        module = createFile(MODULE_PATH, MODULE);
    }

    @AfterEach
    void deleteProject() throws Exception {
        page.closeAllEditors(false);
        if (project != null && project.exists()) {
            project.delete(true, true, null);
        }
    }

    @Test
    void selectionAttachesWholeLines() throws Exception {
        ITextEditor editor = openModule();
        IDocument document = EditorAccess.document(editor);
        // С середины 8-й строки до середины 9-й: прикрепляются строки целиком.
        editor.selectAndReveal(document.getLineOffset(7) + 3, document.getLineOffset(8) + 4 - (document.getLineOffset(7) + 3));

        ChatView view = askAboutSelection();

        assertEquals(List.of(NAME + ": 8–9"), view.contextBar().labels());
        assertTrue(view.contextBar().control().isVisible());
        ContextItem item = view.contextBar().snapshot().get(0);
        assertEquals("\tСообщить(Сумма(1, 2));\n\tСообщить(\"Готово\");", item.text());
        assertEquals(PROJECT + "/" + MODULE_PATH + "\nстроки 8–9 из 12, 44 символа", item.description());
        assertEquals(item.description(), view.contextBar().chips().get(0).getToolTipText());

        // Выделение до начала следующей строки — это строки выше неё.
        editor.selectAndReveal(document.getLineOffset(2), document.getLineOffset(5) - document.getLineOffset(2));
        askAboutSelection();
        assertEquals(List.of(NAME + ": 8–9", NAME + ": 3–5"), view.contextBar().labels());
    }

    @Test
    void withoutSelectionAttachesMethodOrModule() throws Exception {
        ITextEditor editor = openModule();
        IDocument document = EditorAccess.document(editor);

        editor.selectAndReveal(document.getLineOffset(3) + 2, 0);
        ChatView view = askAboutSelection();
        assertEquals(List.of(NAME + ": 1–5"), view.contextBar().labels(), "метод с комментарием и аннотацией");

        editor.selectAndReveal(document.getLineOffset(11), 0);
        askAboutSelection();
        assertEquals(List.of(NAME + ": 1–5", NAME), view.contextBar().labels(), "вне метода — модуль целиком");
        assertEquals(MODULE.strip(), view.contextBar().snapshot().get(1).text());

        askAboutSelection();
        assertEquals(2, view.contextBar().labels().size(), "тот же фрагмент второй раз не добавляется");
        assertEquals("Этот фрагмент уже прикреплён", view.status().getText());

        maximize(view);
        page.getWorkbenchWindow().getShell().setSize(360, 780);
        settle();
        Screenshots.capture(view.control(), "context-bar-narrow");
    }

    @Test
    void chipOpensLinesAndRemoves() throws Exception {
        ITextEditor editor = openModule();
        IDocument document = EditorAccess.document(editor);
        editor.selectAndReveal(document.getLineOffset(7), document.getLineLength(7));
        ChatView view = askAboutSelection();
        editor.selectAndReveal(0, 0);

        click(view.contextBar().chips().get(0), "open");

        assertEquals(editor, page.getActiveEditor());
        ITextSelection selection = (ITextSelection) editor.getSelectionProvider().getSelection();
        assertEquals("\tСообщить(Сумма(1, 2));", selection.getText());

        click(view.contextBar().chips().get(0), "remove");
        settle();
        assertTrue(view.contextBar().isEmpty());
        assertFalse(view.contextBar().control().isVisible());
    }

    @Test
    void linesFollowEditsAboveAndUnsavedTextIsSent() throws Exception {
        ITextEditor editor = openModule();
        IDocument document = EditorAccess.document(editor);
        editor.selectAndReveal(document.getLineOffset(7), document.getLineLength(7) + document.getLineLength(8) - 1);
        ChatView view = askAboutSelection();

        document.replace(0, 0, "// новая строка\n");
        document.replace(document.getLineOffset(8) + 1, "Сообщить".length(), "Показать");
        view.contextBar().refresh();

        assertEquals(List.of(NAME + ": 9–10"), view.contextBar().labels());
        assertEquals("\tПоказать(Сумма(1, 2));\n\tСообщить(\"Готово\");", view.contextBar().snapshot().get(0).text());
        assertTrue(editor.isDirty(), "правки не сохранены — берём текст из редактора");
    }

    @Test
    void sendPutsContextIntoRequestAndTranscript() throws Exception {
        answering(List.of("Выводит ", "сумму."));
        ITextEditor editor = openModule();
        IDocument document = EditorAccess.document(editor);
        editor.selectAndReveal(document.getLineOffset(6), document.getLineOffset(10) - document.getLineOffset(6));
        ChatView view = askAboutSelection();
        view.input().setText("Что делает эта процедура?");
        maximize(view);
        Screenshots.capture(view.control(), "context-bar");

        ask(view, "Что делает эта процедура?");

        List<Object> messages = Json.asList(Json.parseObject(server.requests().get(0).body()).get("messages"));
        // Первое — системное сообщение со сведениями о проекте, вопрос — последним.
        String content = Json.stringAt(messages.get(messages.size() - 1), "content");
        String system = Json.stringAt(messages.get(0), "content");
        assertTrue(system.contains("Проект в IDE: edtai-context-test.") && system.contains("Открыт в редакторе: ОбщийМодуль.Тест.Модуль"),
                system);
        assertEquals(NAME + " (проект " + PROJECT + ", " + MODULE_PATH + "), строки 7–10:\n```bsl\n"
                + "Процедура Вывести() Экспорт\n\tСообщить(Сумма(1, 2));\n\tСообщить(\"Готово\");\nКонецПроцедуры\n```\n\n"
                + "Что делает эта процедура?", content);
        String transcript = view.history().getText();
        assertTrue(transcript.contains("\n" + MessageRenderer.CONTEXT + NAME + ": 7–10\nЧто делает эта процедура?"), transcript);
        assertFalse(transcript.contains("Процедура Вывести()"), "код в ленте не показываем");
        assertTrue(view.contextBar().isEmpty());
        assertFalse(view.contextBar().control().isVisible());
        Screenshots.capture(view.control(), "context-sent");

        // Ссылка фрагмента в ленте открывает файл на этих строках.
        editor.selectAndReveal(0, 0);
        view.linkAction(NAME + ": 7–10").run();
        assertEquals(editor, page.getActiveEditor());
        assertEquals(document.getLineOffset(6), ((ITextSelection) editor.getSelectionProvider().getSelection()).getOffset());
    }

    @Test
    void editReturnsContextAndTranscriptSurvivesReopen() throws Exception {
        answering(List.of("Ответ."));
        ITextEditor editor = openModule();
        IDocument document = EditorAccess.document(editor);
        editor.selectAndReveal(document.getLineOffset(3), 0);
        ChatView view = askAboutSelection();
        ask(view, "Объясни");

        page.hideView(view);
        ChatView reopened = showView();
        assertTrue(reopened.history().getText().contains(MessageRenderer.CONTEXT + NAME + ": 1–5"), reopened.history().getText());

        reopened.linkAction(MessageRenderer.EDIT).run();
        settle();
        assertEquals("Объясни", reopened.input().getText());
        assertEquals(List.of(NAME + ": 1–5"), reopened.contextBar().labels());
        assertEquals("// Сложение двух чисел.", reopened.contextBar().snapshot().get(0).text().lines().findFirst().orElseThrow());
    }

    @Test
    void contextOverLimitIsMarked() throws Exception {
        IFile big = createFile("src/CommonModules/Большой/Module.bsl", "А = 1;\n".repeat(400));
        SettingsStore.workspace().save(new AiSettings(Protocol.OPENAI_COMPATIBLE, "http://localhost:9", "test-model", 0, "",
                AiSettings.MIN_CONTEXT_LIMIT, true, false, "", new ArrayList<>()));
        ChatView view = showView();
        settle();

        view.attach(AttachedContext.wholeFile(big));

        assertEquals(List.of("ОбщийМодуль.Большой.Модуль (обрезано)"), view.contextBar().labels());
        ContextItem item = view.contextBar().snapshot().get(0);
        assertTrue(item.text().length() <= AiSettings.MIN_CONTEXT_LIMIT, String.valueOf(item.text().length()));
        assertEquals(400, item.requestedLastLine());
    }

    @Test
    void commandIsInEditorMenuAndBoundToCtrlAltA() throws Exception {
        IBindingService bindings = PlatformUI.getWorkbench().getService(IBindingService.class);
        List<TriggerSequence> active = Arrays.asList(bindings.getActiveBindingsFor(AskAboutSelectionHandler.COMMAND_ID));
        assertTrue(active.contains(KeySequence.getInstance("M1+M3+A")), active.toString());

        MenuManager menu = new MenuManager();
        menu.add(new Separator(IWorkbenchActionConstants.MB_ADDITIONS));
        IMenuService menus = PlatformUI.getWorkbench().getService(IMenuService.class);
        menus.populateContributionManager(menu, "popup:#TextEditorContext");
        try {
            // В E4 пункт — HandledContributionItem с id команды.
            List<String> ids = Arrays.stream(menu.getItems()).map(IContributionItem::getId).toList();
            assertTrue(ids.contains(AskAboutSelectionHandler.COMMAND_ID), ids.toString());
        } finally {
            menus.releaseContributions(menu);
            menu.dispose();
        }
    }

    @Test
    void commandWithoutEditorExplainsWhy() throws Exception {
        ChatView view = askAboutSelection();

        assertTrue(view.contextBar().isEmpty());
        assertEquals("Откройте модуль в редакторе и выделите код, о котором спросить", view.status().getText());
    }

    @Test
    void attachModuleDialogFindsByRussianName() throws Exception {
        IFile catalog = createFile("src/Catalogs/Номенклатура/ObjectModule.bsl", "Процедура ПередЗаписью(Отказ)\nКонецПроцедуры\n");
        ChatView view = showView();

        ModuleSelectionDialog dialog = new ModuleSelectionDialog(view.getSite().getShell());
        dialog.setInitialPattern("номенкл");
        dialog.setBlockOnOpen(false);
        dialog.open();
        try {
            waitUntil(() -> dialog.selectedItems().contains(catalog), () -> String.valueOf(dialog.selectedItems()));
            assertEquals(List.of(catalog), dialog.selectedItems());
        } finally {
            dialog.close();
        }

        view.attach(AttachedContext.wholeFile(catalog));
        assertEquals(List.of("Справочник.Номенклатура.МодульОбъекта"), view.contextBar().labels());
    }

    @Test
    void atMentionAttachesModule() throws Exception {
        createFile("src/Catalogs/Номенклатура/ObjectModule.bsl", "Процедура ПередЗаписью(Отказ)\nКонецПроцедуры\n");
        ChatView view = showView();
        String typed = "смотри @номенкл";

        IContentProposal[] proposals = view.inputAssist().proposals(typed, typed.length());
        assertEquals(List.of("Справочник.Номенклатура.МодульОбъекта"), Arrays.stream(proposals).map(IContentProposal::getLabel).toList());
        view.input().setText(typed);
        view.input().setSelection(typed.length());
        view.inputAssist().acceptForTest(proposals[0]);

        assertEquals("смотри @Справочник.Номенклатура.МодульОбъекта ", view.input().getText());
        assertEquals(List.of("Справочник.Номенклатура.МодульОбъекта"), view.contextBar().labels());
        assertEquals(SlashCommand.values().length, view.inputAssist().proposals("/", 1).length);
        assertEquals(0, view.inputAssist().proposals("a/", 2).length);
    }

    @Test
    void insertKeepsIndentationAndUndoes() throws Exception {
        String code = "Если А Тогда\n\tВозврат 0;\nКонецЕсли;";
        answering(List.of("```bsl\n" + code + "\n```"));
        ITextEditor editor = openModule();
        IDocument document = EditorAccess.document(editor);
        int offset = document.getLineOffset(3) + 1;
        editor.selectAndReveal(offset, 0);

        ChatView view = showView();
        ask(view, "Пример?");
        view.linkAction(MessageRenderer.INSERT).run();

        assertEquals("\tЕсли А Тогда\n\t\tВозврат 0;\n\tКонецЕсли;Возврат А + Б;", document.get(document.getLineOffset(3),
                document.getLineOffset(5) + document.getLineLength(5) - 1 - document.getLineOffset(3)));
        editor.getAction(ITextEditorActionConstants.UNDO).run();
        assertEquals(MODULE, document.get());
    }

    // ---- помощники ----

    private IFile createFile(String path, String text) throws Exception {
        IFile file = project.getFile(path);
        createParents(file.getParent());
        file.create(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), true, null);
        file.setCharset("UTF-8", null);
        // В тестовой платформе нет редактора BSL: открываем обычным текстовым.
        IDE.setDefaultEditor(file, "org.eclipse.ui.DefaultTextEditor");
        return file;
    }

    private static void createParents(IContainer container) throws Exception {
        if (container instanceof IFolder folder && !folder.exists()) {
            createParents(folder.getParent());
            folder.create(true, true, null);
        }
    }

    private ITextEditor openModule() throws Exception {
        return assertInstanceOf(ITextEditor.class, IDE.openEditor(page, module, "org.eclipse.ui.DefaultTextEditor"));
    }

    /** Как Ctrl+Alt+A: команда через сервис команд. */
    private ChatView askAboutSelection() throws Exception {
        PlatformUI.getWorkbench().getService(IHandlerService.class).executeCommand(AskAboutSelectionHandler.COMMAND_ID, null);
        settle();
        ChatView view = assertInstanceOf(ChatView.class, page.findView(ChatView.ID));
        return view;
    }

    private void maximize(ChatView view) {
        page.setPartState(page.getReference(view), IWorkbenchPage.STATE_MAXIMIZED);
        settle();
    }

    private static void click(Link link, String href) {
        Event event = new Event();
        event.text = href;
        link.notifyListeners(SWT.Selection, event);
    }
}
