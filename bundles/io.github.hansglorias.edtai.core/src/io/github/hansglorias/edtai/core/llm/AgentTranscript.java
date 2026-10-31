package io.github.hansglorias.edtai.core.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * Полные шаги текущего ответа и их проекция в запрос модели. Уменьшение
 * проекции не меняет факты выполнения в ToolExecutionLedger и не удаляет
 * исходные результаты. Пары вызов/результат остаются целыми в обоих протоколах.
 */
final class AgentTranscript {

    static final String OMITTED = "(результат не передан: не помещается в окно модели; если нужно, повтори чтение."
            + " Операцию изменения данных повторно не выполняй)";
    static final String OMITTED_ERROR = "(подробности ошибки не переданы: не помещаются в окно модели;"
            + " этот вызов НЕ завершился успешно)";

    private record Step(ModelReply reply, List<ProtocolAdapter.ToolResult> results) { }

    private final List<Step> steps = new ArrayList<>();

    int size() {
        return steps.size();
    }

    boolean isEmpty() {
        return steps.isEmpty();
    }

    void add(ModelReply reply, List<ProtocolAdapter.ToolResult> results) {
        steps.add(new Step(reply, List.copyOf(results)));
    }

    List<Object> project(ProtocolAdapter adapter, int omitted) {
        List<Object> extra = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            extra.add(adapter.assistantMessage(step.reply()));
            List<ProtocolAdapter.ToolResult> results = step.results();
            if (i < omitted) {
                results = results.stream().map(result -> new ProtocolAdapter.ToolResult(result.call(),
                        result.error() ? OMITTED_ERROR : OMITTED, result.error())).toList();
            }
            extra.addAll(adapter.toolResults(results));
        }
        return extra;
    }
}
