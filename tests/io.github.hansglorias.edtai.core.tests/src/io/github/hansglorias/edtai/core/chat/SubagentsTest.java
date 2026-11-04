package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatMessage.Status;

class SubagentsTest {

    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");

    @Test
    void groupsRunsWithStepsTokensTimeAndReport() {
        String run = "call_1/child/u1";
        String other = "call_2/child/u2";
        List<ToolUse> tools = List.of(
                new ToolUse("call_1", "delegate_task", "Делегирует review", "partial", ToolUse.Status.DONE),
                new ToolUse(run, ToolUse.SUBAGENT, "Ревью: Проверить модуль Х", "Нашёл 2 ошибки", ToolUse.Status.DONE)
                        .withUsage(TokenUsage.step(12_000, 1_200, false)),
                new ToolUse(run + "/r1", "delegate_task", "Ревью: Читает модуль Х", "строки 1–10", ToolUse.Status.DONE),
                ToolUse.failed(run + "/r2", "delegate_task", "Ревью: Ищет код", ToolUse.Problem.TIMEOUT, ""),
                new ToolUse("call_2", "explore_project", "Исследует проект отдельным разведчиком", "", ToolUse.Status.RUNNING),
                new ToolUse(other, ToolUse.SUBAGENT, "Разведка: Где проводится документ", "", ToolUse.Status.RUNNING));
        ReplyStats stats = new ReplyStats(T0, null, 0, 0, 0, 0, null, Map.of(run, 34_000L), Map.of(other, T0));
        ChatMessage answer = new ChatMessage(ChatRole.ASSISTANT, "", T0, Status.STREAMING, List.of(), tools, List.of(), stats);

        List<Subagents.Run> runs = Subagents.of(answer, T0.plusSeconds(5));

        assertEquals(2, runs.size());
        Subagents.Run review = runs.get(0);
        assertEquals("Ревью", review.role());
        assertEquals("Проверить модуль Х", review.task());
        assertEquals(Subagents.State.PARTIAL, review.state(), "итог родителя partial — частичный отчёт");
        assertEquals(2, review.steps().size());
        assertEquals(1, review.failedSteps());
        assertEquals("Нашёл 2 ошибки", review.report());
        assertEquals("2 вызова · 34 с · ↑12k ↓1,2k", review.metrics());
        assertEquals("Читает модуль Х — строки 1–10", Subagents.Run.step(review.steps().get(0)));
        Subagents.Run explore = runs.get(1);
        assertEquals(Subagents.State.RUNNING, explore.state());
        assertEquals(5000, explore.millis(), "идущий запуск — время до сейчас");
        assertEquals("0 вызовов · 5 с", explore.metrics());
    }

    @Test
    void subagentRecordIsHiddenFromModelTrace() {
        ToolUse run = new ToolUse("c/child/u", ToolUse.SUBAGENT, "Проверка: гипотеза", "длинный отчёт", ToolUse.Status.DONE);
        assertFalse(run.isCall());
        assertEquals("Проверка: гипотеза — отчёт готов", run.label());
        ChatMessage answer = new ChatMessage(ChatRole.ASSISTANT, "ответ", T0, Status.DONE, List.of(), List.of(run));
        assertEquals("ответ", answer.modelText());
        assertEquals("Проверка: гипотеза — прервано",
                ToolUse.failed("c/child/u", ToolUse.SUBAGENT, "Проверка: гипотеза", ToolUse.Problem.INTERRUPTED, "").label());
    }

    @Test
    void latestAnswerWithRunsUntilNextQuestion() {
        ChatMessage withRuns = new ChatMessage(ChatRole.ASSISTANT, "a", T0, Status.DONE, List.of(),
                List.of(new ToolUse("c/child/u", ToolUse.SUBAGENT, "Ревью: x", "", ToolUse.Status.DONE)));
        assertSame(withRuns, Subagents.latest(List.of(ChatMessage.of(ChatRole.USER, "q"), withRuns)));
        assertNull(Subagents.latest(List.of(withRuns, ChatMessage.of(ChatRole.USER, "новый вопрос"))));
        assertTrue(Subagents.of(ChatMessage.of(ChatRole.ASSISTANT, "без специалистов"), T0).isEmpty());
    }

    @Test
    void pluralizesCalls() {
        assertEquals("1 вызов", Subagents.calls(1));
        assertEquals("3 вызова", Subagents.calls(3));
        assertEquals("11 вызовов", Subagents.calls(11));
        assertEquals("22 вызова", Subagents.calls(22));
        assertEquals("25 вызовов", Subagents.calls(25));
    }
}
