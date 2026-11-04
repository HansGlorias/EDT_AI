package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
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

        view.linkAction(MessageRenderer.HIDE_STATS).run();
        settle();
        assertFalse(view.history().getText().contains("Ожидание модели"));
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
