package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorActionConstants;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatRole;

/**
 * Лента этапа 3: Markdown, действия у сообщений и блоков кода, ввод,
 * сохранение диалога.
 */
class ChatTranscriptTest extends AbstractChatTest {

    private static final String CODE = "Процедура ПриСозданииНаСервере(Отказ, СтандартнаяОбработка)\n"
            + "\tДата = ТекущаяДатаСеанса();\n"
            + "КонецПроцедуры";
    private static final String ANSWER = "## Получение даты\n\n"
            + "Есть **два** способа:\n\n"
            + "1. `ТекущаяДатаСеанса()` — с учётом *часового пояса*;\n"
            + "2. `ТекущаяДата()` — время сервера, на клиенте лучше не использовать.\n\n"
            + "| Функция | Где работает |\n|---|---|\n| ТекущаяДатаСеанса | сервер и клиент |\n| ТекущаяДата | сервер |\n\n"
            + "```bsl\n" + CODE + "\n```\n\n"
            + "> Часовой пояс сеанса задаётся в `УстановитьЧасовойПоясСеанса()`.\n\n"
            + "Подробнее — в [документации](https://its.example.invalid/date).";

    @Test
    void markdownIsRendered() throws Exception {
        answering(chunks(ANSWER, 17));
        ChatView view = showView();

        ask(view, "Как получить текущую дату?");

        String text = view.history().getText();
        assertTrue(text.contains("\nПолучение даты\n"), text);
        assertFalse(text.contains("##") || text.contains("**") || text.contains("```"), text);
        assertTrue(text.contains("1. ТекущаяДатаСеанса() — с учётом часового пояса;"), text);
        assertTrue(text.contains("ТекущаяДатаСеанса │ сервер и клиент"), text);
        assertTrue(text.contains("bsl   " + MessageRenderer.COPY + "   " + MessageRenderer.INSERT + "\n" + CODE), text);
        assertTrue(text.contains("│ Часовой пояс сеанса"), text);

        assertTrue((styleAt(view, "два").fontStyle & SWT.BOLD) != 0);
        assertTrue((styleAt(view, "часового пояса").fontStyle & SWT.ITALIC) != 0);
        assertEquals(JFaceResources.getHeaderFont(), styleAt(view, "Получение даты").font);
        assertEquals(JFaceResources.getTextFont(), styleAt(view, "ТекущаяДата()").font);
        assertNotNull(styleAt(view, "ТекущаяДата()").background);
        assertEquals(JFaceResources.getTextFont(), styleAt(view, "Дата = ТекущаяДатаСеанса();").font);
        StyleRange link = styleAt(view, "документации");
        assertTrue(link.underline);
        assertInstanceOf(Runnable.class, link.data);

        assertTrue(view.hasCodeBackground(text.indexOf("Дата = ТекущаяДатаСеанса();")));
        assertTrue(view.hasCodeBackground(text.indexOf("bsl   " + MessageRenderer.COPY)));
        assertFalse(view.hasCodeBackground(text.indexOf("Есть два способа")));
        assertFalse(view.hasCodeBackground(text.indexOf("Подробнее")));
        Screenshots.capture(view.control(), "chat-markdown");

        page.getWorkbenchWindow().getShell().setSize(420, 780);
        settle();
        Screenshots.capture(view.control(), "chat-narrow");
    }

    @Test
    void copiesCodeAndAnswer() throws Exception {
        answering(chunks(ANSWER, 40));
        ChatView view = showView();
        ask(view, "Пример?");

        List<Runnable> copies = view.linkActions(MessageRenderer.COPY);
        assertEquals(2, copies.size(), "у блока кода и значок под ответом");

        copies.get(0).run();
        assertEquals(CODE, clipboard());
        assertEquals("Скопировано в буфер обмена", view.status().getText());

        copies.get(1).run();
        assertEquals(ANSWER, clipboard());
    }

    @Test
    void longQuestionWrapsInsideBubble() throws Exception {
        answering(List.of("Да."));
        ChatView view = showView();
        String question = "Как получить текущую дату на сервере с учётом часового пояса сеанса и чем это отличается от "
                + "ТекущаяДата() на клиенте? ".repeat(6) + "Напиши пример.";
        ask(view, question);
        assertBubble(view, question);
        Screenshots.render(view.history(), "message-padding-long");

        page.getWorkbenchWindow().getShell().setSize(420, 780);
        settle();
        assertBubble(view, question);
        Screenshots.render(view.history(), "message-padding-narrow");
        page.getWorkbenchWindow().getShell().setSize(720, 780);
        settle();
        assertBubble(view, question);
    }

    @Test
    void shortAndMultilineQuestionsHavePadding() throws Exception {
        answering(List.of("Ответ с отступом от края."));
        ChatView view = showView();
        for (String question : List.of("Привет", "Первая строка\n\nВторая строка")) {
            view.newChatForTest();
            ask(view, question);
            assertBubblePadding(view, question);
            Screenshots.render(view.history(), question.contains("\n") ? "message-padding-multiline" : "message-padding-short");
        }
    }

    /** Вопрос сдвинут вправо целиком, вместе с перенесёнными строками, и переносится. */
    private static void assertBubble(ChatView view, String question) {
        assertBubblePadding(view, question);
        StyledText history = view.history();
        int indent = view.lineIndent(0);
        assertTrue(indent > 0, "вопрос — пузырём справа");
        int left = history.getLeftMargin() + indent;
        for (int i = 0; i < question.length(); i++) {
            int x = history.getLocationAtOffset(i).x;
            assertTrue(x >= left - 1, "символ " + i + " левее пузыря: " + x + " < " + left);
        }
        assertTrue(history.getLocationAtOffset(question.length() - 1).y > history.getLocationAtOffset(0).y, "длинный вопрос переносится");
    }

    private static void assertBubblePadding(ChatView view, String question) {
        StyledText history = view.history();
        // Многострочный Text на Windows отдаёт CRLF; проверяем смещения сохранённого текста.
        assertEquals(question.replace("\r\n", "\n"), view.session().messages().get(0).text().replace("\r\n", "\n"));
        question = view.session().messages().get(0).text();
        assertTrue(history.getText().startsWith(question));
        settle();
        history.setTopPixel(0);
        Rectangle bubble = view.bubbleBounds(0);
        assertNotNull(bubble);
        assertTrue(bubble.y >= 12, "сверху от первого пузыря есть внешнее поле: " + bubble);
        assertTrue(bubble.x >= 16, "слева от пузыря есть внешнее поле: " + bubble);
        assertTrue(bubble.x + bubble.width <= history.getClientArea().width - 16,
                "справа от пузыря есть внешнее поле: " + bubble);
        for (int i = 0; i < question.length(); i++) {
            if (question.charAt(i) == '\n' || question.charAt(i) == '\r') continue;
            Rectangle glyph = history.getTextBounds(i, i);
            assertTrue(glyph.x >= bubble.x + 12, "слева внутри пузыря есть поле: " + glyph);
            assertTrue(glyph.x + glyph.width <= bubble.x + bubble.width - 12, "справа внутри пузыря есть поле: " + glyph);
            assertTrue(glyph.y >= bubble.y + 8, "сверху внутри пузыря есть поле: " + glyph);
            assertTrue(glyph.y + glyph.height <= bubble.y + bubble.height - 8, "снизу внутри пузыря есть поле: " + glyph);
        }
        int footerY = history.getLocationAtOffset(question.length() + 1).y;
        assertTrue(footerY >= bubble.y + bubble.height + 4, "время и действия отделены от пузыря");
        assertTrue(history.getLocationAtOffset(question.length() + 1).x >= 16, "строка действий внутри внешних полей");
    }

    @Test
    void copiesSelectionWithoutIconPlaceholders() throws Exception {
        answering(chunks(ANSWER, 40));
        ChatView view = showView();
        ask(view, "Пример?");
        assertTrue(view.history().getText().indexOf(MessageRenderer.OBJECT) >= 0, "под значки — символы-заглушки");

        view.history().selectAll();
        view.copySelection();

        String copied = clipboard();
        assertTrue(copied.startsWith("Пример?\n"), copied);
        assertTrue(copied.contains(CODE), copied);
        assertFalse(copied.indexOf(MessageRenderer.OBJECT) >= 0, copied);
    }

    @Test
    void insertsCodeIntoEditorWithUndo() throws Exception {
        answering(chunks(ANSWER, 40));
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("edtai-ui-test");
        if (!project.exists()) {
            project.create(null);
        }
        project.open(null);
        IFile file = project.getFile("Module.txt");
        if (file.exists()) {
            file.delete(true, null);
        }
        file.create(new ByteArrayInputStream("Начало\n".getBytes(StandardCharsets.UTF_8)), true, null);
        try {
            IEditorPart editor = IDE.openEditor(page, file);
            ITextEditor textEditor = assertInstanceOf(ITextEditor.class, editor);
            textEditor.selectAndReveal("Начало\n".length(), 0);
            IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());

            ChatView view = (ChatView) page.showView(ChatView.ID);
            ask(view, "Пример?");
            view.linkAction(MessageRenderer.INSERT).run();

            assertEquals("Начало\n" + CODE, document.get());
            assertTrue(view.status().getText().startsWith("Код вставлен в Module.txt"), view.status().getText());
            assertEquals(editor, page.getActivePart());

            textEditor.getAction(ITextEditorActionConstants.UNDO).run();
            assertEquals("Начало\n", document.get());
        } finally {
            page.closeAllEditors(false);
            project.delete(true, true, null);
        }
    }

    @Test
    void insertWithoutEditorExplainsWhy() throws Exception {
        answering(chunks(ANSWER, 40));
        ChatView view = showView();
        ask(view, "Пример?");

        view.linkAction(MessageRenderer.INSERT).run();

        assertEquals("Откройте модуль в редакторе, чтобы вставить код", view.status().getText());
    }

    @Test
    void editReturnsMessageToInput() throws Exception {
        answering(List.of("Ответ."));
        ChatView view = showView();
        ask(view, "первый вопрос");
        ask(view, "второй вопрос");
        assertEquals(2, view.linkActions(MessageRenderer.EDIT).size());

        view.linkActions(MessageRenderer.EDIT).get(1).run();
        settle();

        assertEquals("второй вопрос", view.input().getText());
        assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT), view.session().messages().stream().map(m -> m.role()).toList());
        assertFalse(view.history().getText().contains("второй вопрос"));

        view.linkActions(MessageRenderer.EDIT).get(0).run();
        settle();
        assertEquals("первый вопрос", view.input().getText());
        assertTrue(view.isEmptyStateShown());
    }

    @Test
    void retryRegeneratesLastAnswer() throws Exception {
        List<String> answers = new ArrayList<>(List.of("Первый ответ.", "Второй ответ."));
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(
                openAiPart(answers.remove(0)), "data: [DONE]\n\n"), 0));
        configure(server);
        ChatView view = showView();
        ask(view, "вопрос");

        view.linkAction(MessageRenderer.RETRY).run();
        waitUntil(() -> view.history().getText().contains("Второй ответ.") && !view.session().isBusy());

        assertFalse(view.history().getText().contains("Первый ответ."));
        assertEquals(2, view.session().messages().size());
    }

    @Test
    void enterSendsAndShiftEnterDoesNot() throws Exception {
        answering(List.of("ок"));
        ChatView view = showView();

        view.input().setText("строка");
        Event shiftEnter = key(SWT.CR, SWT.SHIFT);
        view.onInputKey(shiftEnter);
        assertTrue(shiftEnter.doit, "Shift+Enter — перевод строки в поле");
        assertTrue(view.session().messages().isEmpty());

        Event enter = key(SWT.CR, 0);
        view.onInputKey(enter);
        assertFalse(enter.doit);
        assertEquals("", view.input().getText());
        waitUntil(() -> !view.session().isBusy() && view.session().messages().size() == 2);
        assertEquals("строка", view.session().messages().get(0).text());
    }

    @Test
    void arrowsRecallSentMessages() throws Exception {
        answering(List.of("ок"));
        ChatView view = showView();
        ask(view, "раз");
        ask(view, "два");

        assertEquals("два", press(view, SWT.ARROW_UP));
        assertEquals("раз", press(view, SWT.ARROW_UP));
        assertEquals("раз", press(view, SWT.ARROW_UP));
        assertEquals("два", press(view, SWT.ARROW_DOWN));
        assertEquals("", press(view, SWT.ARROW_DOWN));

        view.input().setText("свой текст");
        Event up = key(SWT.ARROW_UP, 0);
        view.onInputKey(up);
        assertTrue(up.doit, "в непустом поле стрелки двигают курсор");
        assertEquals("свой текст", view.input().getText());
    }

    @Test
    void inputGrowsWithText() throws Exception {
        ChatView view = showView();
        int empty = view.input().getSize().y;

        view.input().setText("1\n2\n3\n4\n5\n6");
        settle();
        int six = view.input().getSize().y;
        view.input().setText("строка\n".repeat(40));
        settle();
        int many = view.input().getSize().y;

        assertTrue(six > empty, empty + " → " + six);
        assertTrue(many > six, six + " → " + many);
        assertTrue(many < view.input().getLineHeight() * 12, "не больше 10 строк: " + many);
    }

    @Test
    void dialogSurvivesReopeningView() throws Exception {
        answering(List.of("Запомнил."));
        ChatView view = showView();
        ask(view, "сохрани меня");
        page.hideView(view);
        assertTrue(savedChats().contains("сохрани меня"));

        ChatView reopened = showView();
        settle();

        assertTrue(reopened.history().getText().contains("сохрани меня"));
        assertTrue(reopened.history().getText().contains("Запомнил."));
        assertFalse(reopened.isEmptyStateShown());

        reopened.session().clear();
        settle();
        assertTrue(reopened.isEmptyStateShown());
        assertFalse(savedChats().contains("сохрани меня"));
    }

    @Test
    void newChatKeepsPreviousInHistory() throws Exception {
        answering(List.of("Ответ."));
        ChatView view = showView();
        ask(view, "первый чат");
        String first = view.historyFile().path().getFileName().toString().replace(".json", "");

        view.newChatForTest();
        settle();
        assertTrue(view.isEmptyStateShown());
        ask(view, "второй чат");

        assertEquals(List.of("второй чат", "первый чат"), view.chats().list().stream().map(entry -> entry.title()).toList());
        view.showHistoryPanel(true);
        assertEquals(List.of("второй чат", "первый чат"), view.historyPanel().titles());
        Screenshots.capture(view.control(), "chat-history");
        view.historyPanel().select(1);
        settle();
        assertTrue(view.historyFile().path().getFileName().toString().startsWith(first));

        view.historyPanel().clickNewChat();
        settle();
        assertTrue(view.isEmptyStateShown(), "«Новый чат» в заголовке панели");
        view.historyPanel().select(1);
        settle();
        assertTrue(view.history().getText().contains("первый чат"), view.history().getText());
        assertFalse(view.history().getText().contains("второй чат"));

        page.hideView(view);
        ChatView reopened = showView();
        settle();
        assertTrue(reopened.history().getText().contains("первый чат"), "открывается чат, открытый в прошлый раз");
    }

    @Test
    void chatsAreRenamedSearchedAndExported() throws Exception {
        answering(List.of("Используйте ", "ТекущаяДатаСеанса()."));
        ChatView view = showView();
        ask(view, "как получить дату");
        view.newChatForTest();
        settle();
        ask(view, "ревью модуля");
        view.showHistoryPanel(true);
        ChatHistoryPanel panel = view.historyPanel();
        assertEquals(List.of("ревью модуля", "как получить дату"), panel.titles());

        view.askTitle = current -> {
            assertEquals("как получить дату", current);
            return "Даты сеанса";
        };
        panel.runMenuItem(1, "Переименовать");
        assertEquals(List.of("ревью модуля", "Даты сеанса"), panel.titles(), "порядок не меняется");

        panel.search("получить ДАТУ");
        waitUntil(() -> panel.titles().size() == 1, () -> panel.titles().toString());
        assertEquals(List.of("Даты сеанса"), panel.titles(), "по тексту вопроса, без учёта регистра");
        panel.search("нет такого");
        waitUntil(panel::isNothingFound, () -> panel.titles().toString());
        panel.search("");
        waitUntil(() -> panel.titles().size() == 2, () -> panel.titles().toString());
        assertFalse(panel.isNothingFound());

        java.nio.file.Path target = Files.createTempDirectory("edtai-export").resolve("chat.md");
        List<String> names = new ArrayList<>();
        view.chooseExportFile = name -> {
            names.add(name);
            return target;
        };
        panel.runMenuItem(1, "Экспорт");
        assertEquals(List.of("Даты сеанса.md"), names);
        String markdown = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(markdown.startsWith("# Даты сеанса\n\n## Вопрос · "), markdown);
        assertTrue(markdown.contains("как получить дату\n\n## Ответ · "), markdown);
        assertTrue(markdown.endsWith("Используйте ТекущаяДатаСеанса().\n"), markdown);
        assertTrue(view.status().getText().contains("Чат сохранён"), view.status().getText());

        assertEquals("a_b_c_.md", ChatView.exportName("a/b:c?…..."));
        assertEquals("chat.md", ChatView.exportName("..."));
    }

    private static String press(ChatView view, int keyCode) {
        view.onInputKey(key(keyCode, 0));
        return view.input().getText();
    }

    private static Event key(int keyCode, int stateMask) {
        Event event = new Event();
        event.keyCode = keyCode;
        event.stateMask = stateMask;
        event.doit = true;
        return event;
    }

    private static StyleRange styleAt(ChatView view, String fragment) {
        int offset = view.history().getText().indexOf(fragment);
        assertTrue(offset >= 0, fragment);
        StyleRange style = view.history().getStyleRangeAtOffset(offset);
        assertNotNull(style, "нет оформления у «" + fragment + "»");
        return style;
    }

    private String clipboard() {
        Clipboard clipboard = new Clipboard(page.getWorkbenchWindow().getShell().getDisplay());
        try {
            return (String) clipboard.getContents(TextTransfer.getInstance());
        } finally {
            clipboard.dispose();
        }
    }

    /** Ответ частями по {@code size} символов, как при стриминге. */
    private static List<String> chunks(String text, int size) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            parts.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return parts;
    }
}
