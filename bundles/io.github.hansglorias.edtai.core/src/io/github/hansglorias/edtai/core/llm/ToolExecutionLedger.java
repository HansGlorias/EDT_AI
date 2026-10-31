package io.github.hansglorias.edtai.core.llm;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.tools.Tool;

/**
 * Факты выполнения в пределах одного ответа, независимо от того, какие
 * результаты ещё помещаются в запрос. При обрезке контекста записи не
 * удаляются. Используется только потоком AgentLoop.
 */
final class ToolExecutionLedger {

    static final int MAX_READ_ATTEMPTS = 2;
    static final String UNCERTAIN = "Предыдущий вызов мог изменить данные, но его успешное завершение не подтверждено."
            + " Повторно не выполнен: сначала проверь фактическое состояние читающим инструментом."
            + " Не сообщай, что операция выполнена успешно.";
    static final String DENIED = "Пользователь отклонил этот вызов. Повторно разрешение не запрашивается."
            + " Объясни ограничение или используй другой способ без этой операции.";

    enum State { STARTED, SUCCEEDED, FAILED, DENIED }
    enum Action { RUN, REPEATED, REPLAY, DENIED, UNCERTAIN, FAILED }

    record Key(String name, String arguments) { }

    record Entry(int step, long generation, int attempts, boolean read, State state,
            String text, ToolUse.Problem problem) { }

    record Decision(Action action, Entry previous) { }

    private final Map<Key, Entry> entries = new HashMap<>();
    /** Любая начатая операция с возможным эффектом делает прежние чтения устаревшими. */
    private long generation;

    static Key key(String name, Map<String, Object> input) {
        return new Key(name, Json.write(canonical(input)));
    }

    Decision decide(Key key, int firstVisibleStep) {
        Entry previous = entries.get(key);
        if (previous == null) {
            return new Decision(Action.RUN, null);
        }
        if (previous.state() == State.DENIED) {
            return new Decision(Action.DENIED, previous);
        }
        if (!previous.read()) {
            return new Decision(previous.state() == State.SUCCEEDED ? Action.REPLAY : Action.UNCERTAIN, previous);
        }
        if (previous.generation() != generation) {
            return new Decision(Action.RUN, previous);
        }
        if (previous.state() == State.SUCCEEDED) {
            return new Decision(previous.step() < firstVisibleStep ? Action.RUN : Action.REPEATED, previous);
        }
        boolean transientFailure = previous.problem() == ToolUse.Problem.SERVER || previous.problem() == ToolUse.Problem.TIMEOUT;
        return new Decision(transientFailure && previous.attempts() < MAX_READ_ATTEMPTS ? Action.RUN : Action.FAILED, previous);
    }

    void denied(Key key, int step) {
        entries.put(key, new Entry(step, generation, 0, false, State.DENIED, DENIED, ToolUse.Problem.DENIED));
    }

    void started(Key key, Tool tool, int step) {
        Entry previous = entries.get(key);
        boolean read = tool.repeatableRead();
        int attempts = previous != null && previous.generation() == generation && previous.state() == State.FAILED
                ? previous.attempts() + 1 : 1;
        if (!read) {
            generation++;
        }
        entries.put(key, new Entry(step, generation, attempts, read, State.STARTED, "", null));
    }

    void succeeded(Key key, String text) {
        finish(key, State.SUCCEEDED, text, null);
    }

    void failed(Key key, String text, ToolUse.Problem problem) {
        finish(key, State.FAILED, text, problem);
    }

    private void finish(Key key, State state, String text, ToolUse.Problem problem) {
        Entry entry = entries.get(key);
        entries.put(key, new Entry(entry.step(), entry.generation(), entry.attempts(), entry.read(), state, text, problem));
    }

    /** Порядок полей не меняет вызов; порядок массивов и содержимое строк сохраняются. */
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put((String) key, canonical(item)));
            return sorted;
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream().map(ToolExecutionLedger::canonical).toList();
        }
        return value instanceof Number number ? new BigDecimal(number.toString()).stripTrailingZeros() : value;
    }
}
