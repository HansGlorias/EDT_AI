package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Event;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.AgentMode;
import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.PlanApproval;
import io.github.hansglorias.edtai.core.chat.ReplyStats;
import io.github.hansglorias.edtai.core.chat.Subagents;
import io.github.hansglorias.edtai.core.chat.TokenUsage;
import io.github.hansglorias.edtai.core.chat.ToolUse;

/** Время и токены ответа, панель специалистов и план отдельным файлом. */
class ChatInsightsTest extends AbstractChatTest {

    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");

    @Test
    void answerShowsDurationCardAndSubagentPanel() throws Exception {
        answering(List.of("ok"));
        ChatView view = showView();
        String run = "call_1/child/u1";
        List<ToolUse> tools = List.of(
                new ToolUse("call_0", "read_module", "Читает модуль ОбщийМодуль.Продажи", "строки 1–120 из 120", ToolUse.Status.DONE),
                new ToolUse("call_1", "delegate_task", "Делегирует review", "complete", ToolUse.Status.DONE),
                new ToolUse(run, ToolUse.SUBAGENT, "Ревью: Найти ошибки в проведении документа", "Выводы: две ошибки.\nПроведение.bsl:42 — нет проверки",
                        ToolUse.Status.DONE).withUsage(TokenUsage.step(12_000, 900, false)),
                new ToolUse(run + "/r1", "delegate_task", "Ревью: Читает модуль Документ.Реализация", "строки 1–80", ToolUse.Status.DONE),
                new ToolUse(run + "/r2", "delegate_task", "Ревью: Ищет «Проведение»", "найдено: 3", ToolUse.Status.DONE));
        ReplyStats stats = new ReplyStats(T0, T0.plusSeconds(252), 61_000, 120_000, 20_000, 51_000,
                TokenUsage.step(45_000, 2_100, false), Map.of("call_0", 1_400L, "call_1", 48_000L, run, 47_000L));
        ChatMessage answer = new ChatMessage(ChatRole.ASSISTANT, "Нашёл две ошибки, подробности ниже.", T0, ChatMessage.Status.DONE,
                List.of(), tools, List.of(), stats);
        view.session().restore(List.of(new ChatMessage(ChatRole.USER, "Проверь проведение", T0, ChatMessage.Status.DONE), answer));
        view.refresh();
        settle();

        String text = view.history().getText();
        assertTrue(text.contains("4 мин 12 с · думала 3 мин 1 с · ↑45k ↓2,1k"), text);
        assertFalse(text.contains("Ожидание модели"), "карточка свёрнута");
        assertTrue(view.subagentsPanel().isShown());
        assertEquals("Специалисты · 1 — 1 готово", view.subagentsPanel().titleText());
        view.subagentsPanel().expand(run);

        view.linkAction(MessageRenderer.SHOW_STATS).run();
        settle();
        text = view.history().getText();
        assertTrue(text.contains("Ожидание модели"), text);
        assertTrue(text.contains("Рассуждение"));
        assertTrue(text.contains("Дольше всего"));
        assertNotNull(view.linkAction(MessageRenderer.HIDE_STATS));
        render(view, "reply-stats-and-subagents");
        var sidebar = view.subagentsPanel();
        var position = sidebar.control().toDisplay(0, 0);
        var inputPosition = view.input().toDisplay(0, 0);
        assertTrue(position.x > inputPosition.x + view.input().getSize().x,
                "специалисты находятся справа от поля ввода");
        assertTrue(sidebar.control().getSize().y > view.input().getSize().y);
        int expandedWidth = sidebar.control().getSize().x;
        int inputWidth = view.input().getSize().x;
        sidebar.setCollapsed(true);
        settle();
        assertTrue(sidebar.isCollapsed());
        assertTrue(sidebar.control().getSize().x < expandedWidth);
        assertTrue(view.input().getSize().x > inputWidth);
        render(view, "subagents-collapsed");
        sidebar.setCollapsed(false);
        settle();
        assertFalse(sidebar.isCollapsed());
        assertEquals(expandedWidth, sidebar.control().getSize().x);

        view.linkAction(MessageRenderer.HIDE_STATS).run();
        settle();
        assertFalse(view.history().getText().contains("Ожидание модели"));
        view.session().restore(List.of(ChatMessage.of(ChatRole.ASSISTANT, "Ответ без специалистов")));
        view.refresh();
        settle();
        assertFalse(sidebar.isShown(), "специалисты не переходят в другой ответ");
    }

    @Test
    void collapsedSidebarReceivesLongReportAndScrollsAfterOpening() throws Exception {
        answering(List.of("ok"));
        ChatView view = showView();
        var sidebar = view.subagentsPanel();
        var run = new Subagents.Run("run", "Ревью", "Проверить результат", Subagents.State.RUNNING,
                List.of(), null, 0, "");
        sidebar.show("answer", List.of(run));
        sidebar.expand("run");
        render(view, "subagents-running");
        sidebar.setCollapsed(true);
        List<ToolUse> steps = java.util.stream.IntStream.range(0, 12).mapToObj(i ->
                new ToolUse("run/" + i, "delegate_task", "Ревью: Читает модуль Документ.Реализация " + i,
                        "Длинный результат проверки, который переносится на несколько строк", ToolUse.Status.DONE)).toList();
        sidebar.show("answer", List.of(new Subagents.Run("run", "Ревью", "Проверить результат", Subagents.State.DONE,
                steps, TokenUsage.step(1000, 100, false), 1200, "Выводы проверки\n".repeat(20))));
        sidebar.setCollapsed(false);
        settle();
        ScrolledComposite scroll = java.util.Arrays.stream(sidebar.control().getChildren())
                .filter(ScrolledComposite.class::isInstance).map(ScrolledComposite.class::cast).findFirst().orElseThrow();
        assertTrue(scroll.getMinHeight() > scroll.getClientArea().height, "длинные шаги доступны прокруткой");
        scroll.setOrigin(0, scroll.getMinHeight());
        assertTrue(scroll.getOrigin().y > 0);
        render(view, "subagents-long-report");
    }

    @Test
    void planOpensAsFileAndEditedPlanIsExecuted() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        server = FakeModelServer.http().handler((request, response) -> {
            bodies.add(request.body());
            response.sse(List.of(openAiPart("Выполняю"), "data: [DONE]\n\n"), 0);
        });
        configure(server);
        ChatView view = showView();
        view.setAgentMode(AgentMode.PLAN);
        String plan = "1. Прочитать модуль\n2. Исправить проверку";
        view.session().restore(List.of(
                new ChatMessage(ChatRole.USER, "Составь план", T0, ChatMessage.Status.DONE, List.of(), List.of(),
                        List.of(Attachment.mode(AgentMode.PLAN.label(), "план"))),
                new ChatMessage(ChatRole.ASSISTANT, plan, T0, ChatMessage.Status.DONE)));
        view.refresh();
        settle();
        assertTrue(view.approvePlanButton().getVisible());
        assertTrue(view.openPlanButton().getVisible());
        assertTrue(view.planNoteText().startsWith("План готов"));
        render(view, "plan-bar");

        click(view.openPlanButton());
        settle();
        ITextEditor editor = assertInstanceOf(ITextEditor.class, page.getActiveEditor());
        assertTrue(view.planFileForTest().matches(plan));
        IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        assertTrue(document.get().contains("2. Исправить проверку"));
        document.replace(document.get().indexOf("2. Исправить проверку"), "2. Исправить проверку".length(),
                "2. Исправить проверку\n3. Добавить тест");
        editor.doSave(null);
        page.activate(view);
        settle();
        view.refresh();
        assertTrue(view.planNoteText().startsWith("План исправлен вручную"), view.planNoteText());
        render(view, "plan-edited");

        // Ещё одна правка без сохранения: перед выполнением она сохраняется — утверждают то, что видно.
        document.replace(document.get().length(), 0, "4. Проверить diff\n");
        assertTrue(editor.isDirty());

        click(view.approvePlanButton());
        waitUntil(() -> !view.session().isBusy() && bodies.size() == 1);
        assertFalse(editor.isDirty(), "несохранённая правка плана сохранена перед выполнением");
        String body = bodies.get(0);
        assertTrue(body.contains(PlanApproval.EXECUTE_EDITED.substring(0, 40)), body);
        assertTrue(body.contains("3. Добавить тест"), "модель получила исправленный план");
        assertTrue(body.contains("4. Проверить diff"), "и несохранённую правку");
        assertEquals(AgentMode.AGENT, ChatView.agentMode());
    }

    /** Вид целиком нужного размера: лента, панели и поле ввода. */
    private static void render(ChatView view, String name) throws Exception {
        view.control().setSize(720, 620);
        view.control().layout(true, true);
        settle();
        Screenshots.render(view.control(), name);
    }

    private static void click(Button button) {
        assertTrue(button.isEnabled());
        Event event = new Event();
        event.widget = button;
        button.notifyListeners(SWT.Selection, event);
        settle();
    }
}
