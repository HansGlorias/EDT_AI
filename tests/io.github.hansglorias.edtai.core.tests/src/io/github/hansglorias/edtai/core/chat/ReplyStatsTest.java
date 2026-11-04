package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.chat.ChatMessage.Status;

class ReplyStatsTest {

    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");

    @TempDir
    Path temp;

    @Test
    void clockSplitsTimeByPhaseAndMeasuresCalls() {
        ReplyStats.Clock clock = new ReplyStats.Clock(T0);
        clock.enter(ReplyActivity.Phase.THINKING, T0.plusSeconds(1));
        clock.enter(ReplyActivity.Phase.REASONING, T0.plusSeconds(61));
        clock.enter(ReplyActivity.Phase.TOOL, T0.plusSeconds(181));
        clock.call(new ToolUse("a", "read_module", "Читает модуль", "", ToolUse.Status.RUNNING), T0.plusSeconds(181));
        ReplyStats running = clock.snapshot();
        assertNull(running.finished());
        assertEquals(5000, running.call("a", T0.plusSeconds(186)), "идущий вызов — до сейчас");
        clock.call(new ToolUse("a", "read_module", "Читает модуль", "строки 1–10", ToolUse.Status.DONE), T0.plusSeconds(193));
        clock.enter(ReplyActivity.Phase.WRITING, T0.plusSeconds(193));
        clock.tokens(TokenUsage.step(45_000, 2_100, false));
        clock.finish(T0.plusSeconds(252));

        ReplyStats stats = clock.snapshot();
        assertEquals(T0.plusSeconds(252), stats.finished());
        assertEquals(61_000, stats.waiting());
        assertEquals(120_000, stats.reasoning());
        assertEquals(12_000, stats.tools());
        assertEquals(59_000, stats.writing());
        assertEquals(252_000, stats.total(Instant.now()));
        assertEquals(12_000, stats.call("a", Instant.now()));
        assertEquals(-1, stats.call("нет", Instant.now()));
        assertTrue(stats.active().isEmpty());
        assertEquals("4 мин 12 с · думала 3 мин 1 с · ↑45k ↓2,1k", stats.summary());
        List<ReplyStats.Line> phases = stats.phases(1);
        assertEquals(List.of("Ожидание модели", "Рассуждение", "Текст ответа", "Инструменты"),
                phases.stream().map(ReplyStats.Line::label).toList());
        assertEquals("вызовов: 1", phases.get(3).note());
        assertTrue(stats.hint(1).contains("Рассуждение: 2 мин"));
    }

    @Test
    void approvalWaitIsNotCallTime() {
        ReplyStats.Clock clock = new ReplyStats.Clock(T0);
        clock.call(new ToolUse("w", "edit_module", "Правит", "", ToolUse.Status.AWAITING), T0);
        clock.call(new ToolUse("w", "edit_module", "Правит", "", ToolUse.Status.RUNNING), T0.plusSeconds(30));
        clock.call(new ToolUse("w", "edit_module", "Правит", "", ToolUse.Status.DONE), T0.plusSeconds(32));
        assertEquals(2000, clock.snapshot().call("w", Instant.now()));
    }

    @Test
    void formatsDurationsBarsAndPercents() {
        assertEquals("950 мс", ReplyStats.duration(950));
        assertEquals("1,5 с", ReplyStats.duration(1500));
        assertEquals("12 с", ReplyStats.duration(12_400));
        assertEquals("2 мин", ReplyStats.duration(120_000));
        assertEquals("1 ч 5 мин", ReplyStats.duration(3_900_000));
        assertEquals("████░░░░", ReplyStats.bar(0.5, 8));
        assertEquals("█░░░░░░░", ReplyStats.bar(0.001, 8), "ненулевая доля видна");
        assertEquals("<1%", ReplyStats.percent(0.001));
        assertEquals("25%", ReplyStats.percent(0.25));
    }

    @Test
    void historyKeepsFinishedStatsAndSurvivesDamagedOnes() throws Exception {
        ReplyStats stats = new ReplyStats(T0, T0.plusSeconds(10), 4000, 3000, 2000, 1000,
                TokenUsage.step(100, 20, false), Map.of("call/1", 1000L));
        ChatHistoryFile file = new ChatHistoryFile(temp.resolve("chat.json"));
        List<ChatMessage> messages = List.of(ChatMessage.of(ChatRole.USER, "вопрос"),
                new ChatMessage(ChatRole.ASSISTANT, "ответ", T0, Status.DONE, List.of(), List.of(), List.of(), stats));
        file.save(messages);
        assertEquals(messages, file.load());

        String damaged = java.nio.file.Files.readString(file.path()).replace("\"waiting\":4000", "\"waiting\":\"много\"");
        java.nio.file.Files.writeString(file.path(), damaged);
        ChatMessage loaded = file.load().get(1);
        assertEquals("ответ", loaded.text());
        assertNull(loaded.stats(), "повреждённая статистика не мешает загрузить диалог");
    }

    @Test
    void streamingStatsAreNotSaved() throws Exception {
        ReplyStats.Clock clock = new ReplyStats.Clock(T0);
        ChatHistoryFile file = new ChatHistoryFile(temp.resolve("chat.json"));
        file.save(List.of(new ChatMessage(ChatRole.ASSISTANT, "часть", T0, Status.STOPPED, List.of(), List.of(), List.of(),
                clock.snapshot())));
        assertNull(file.load().get(0).stats());
    }
}
