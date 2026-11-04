package io.github.hansglorias.edtai.core.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Специалисты (субагенты) одного ответа для панели: по запуску — роль, задача, состояние, шаги,
 * токены, время и отчёт. Собирается из вызовов ответа: запись {@link ToolUse#SUBAGENT} и вызовы
 * специалиста с id «запуск/шаг».
 */
public final class Subagents {

    /** Состояние запуска для значка. */
    public enum State {
        RUNNING("работает"), DONE("готово"), PARTIAL("частично"), FAILED("ошибка"), STOPPED("прервано");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Запуск специалиста.
     *
     * @param role   «Разведка», «Ревью», «Проверка»
     * @param task   задача одной строкой
     * @param steps  вызовы инструментов специалиста по порядку
     * @param tokens токены всего запуска; {@code null} — не известно
     * @param millis длительность запуска; пока идёт — до сейчас; -1 — не известно
     * @param report отчёт специалиста или причина ошибки; пусто — ещё нет
     */
    public record Run(String id, String role, String task, State state, List<ToolUse> steps, TokenUsage tokens, long millis,
            String report) {

        public Run {
            steps = List.copyOf(steps);
        }

        /** Вызовы с ошибкой. */
        public long failedSteps() {
            return steps.stream().filter(step -> step.status() == ToolUse.Status.FAILED).count();
        }

        /** «6 вызовов · 34 с · ↑12k ↓1,2k». */
        public String metrics() {
            List<String> parts = new ArrayList<>();
            parts.add(calls(steps.size()));
            if (millis >= 0) {
                parts.add(ReplyStats.duration(millis));
            }
            if (tokens != null) {
                parts.add(tokens.describeStep());
            }
            return String.join(" · ", parts);
        }

        /** Шаг без префикса роли: «Читает модуль Х — строки 1–20». */
        public static String step(ToolUse use) {
            String label = use.label();
            int colon = label.indexOf(": ");
            return colon > 0 && colon < 20 ? label.substring(colon + 2) : label;
        }
    }

    private Subagents() {
    }

    /** Запуски специалистов в ответе по порядку; пусто — их не было. */
    public static List<Run> of(ChatMessage answer, Instant now) {
        if (answer == null || answer.role() != ChatRole.ASSISTANT) {
            return List.of();
        }
        Map<String, ToolUse> runs = new LinkedHashMap<>();
        for (ToolUse use : answer.tools()) {
            if (ToolUse.SUBAGENT.equals(use.name())) {
                runs.put(use.id(), use);
            }
        }
        if (runs.isEmpty()) {
            return List.of();
        }
        List<Run> result = new ArrayList<>();
        for (ToolUse run : runs.values()) {
            String prefix = run.id() + "/";
            List<ToolUse> steps = answer.tools().stream()
                    .filter(use -> use.id().startsWith(prefix) && !ToolUse.SUBAGENT.equals(use.name())).toList();
            int colon = run.summary().indexOf(": ");
            String role = colon > 0 ? run.summary().substring(0, colon) : "Специалист";
            String task = colon > 0 ? run.summary().substring(colon + 2) : run.summary();
            long millis = answer.stats() == null ? -1 : answer.stats().call(run.id(), now);
            result.add(new Run(run.id(), role, task, state(answer, run), steps, run.usage(), millis, run.outcome()));
        }
        return result;
    }

    /** Последний ответ ассистента в диалоге со специалистами; {@code null} — нет такого. */
    public static ChatMessage latest(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.role() == ChatRole.USER) {
                return null;
            }
            if (message.role() == ChatRole.ASSISTANT && message.tools().stream().anyMatch(use -> ToolUse.SUBAGENT.equals(use.name()))) {
                return message;
            }
        }
        return null;
    }

    private static State state(ChatMessage answer, ToolUse run) {
        return switch (run.status()) {
            case AWAITING, RUNNING -> State.RUNNING;
            case FAILED -> run.problem() == ToolUse.Problem.INTERRUPTED ? State.STOPPED : State.FAILED;
            case DONE -> partial(answer, run) ? State.PARTIAL : State.DONE;
        };
    }

    /** Итог родительского вызова (explore_project / delegate_task): «partial» — неполный отчёт. */
    private static boolean partial(ChatMessage answer, ToolUse run) {
        int child = run.id().indexOf("/child/");
        if (child <= 0) {
            return false;
        }
        String parent = run.id().substring(0, child);
        return answer.tools().stream().anyMatch(use -> use.id().equals(parent) && use.outcome().strip().equals("partial"));
    }

    /** «1 вызов», «3 вызова», «7 вызовов». */
    static String calls(int count) {
        int last = count % 10;
        int lastTwo = count % 100;
        String word = last == 1 && lastTwo != 11 ? "вызов"
                : last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? "вызова" : "вызовов";
        return count + " " + word;
    }
}
