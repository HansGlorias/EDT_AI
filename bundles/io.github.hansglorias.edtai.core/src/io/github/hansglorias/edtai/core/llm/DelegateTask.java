package io.github.hansglorias.edtai.core.llm;

import java.util.List;
import java.util.Map;

import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolException;

/** Ролевые чтения с общей квотой и lifecycle совместимой разведки. */
final class DelegateTask implements Tool {
    private final ExploreProject runner;

    DelegateTask(ExploreProject runner) { this.runner = runner; }
    @Override public String name() { return "delegate_task"; }
    @Override public boolean safeInPlan() { return true; }
    @Override public String description() {
        return "Изолированное чтение: research — исследование связей, review — ошибки и регрессии по diff/коду,"
                + " verify — проверка гипотезы доступной диагностикой. Без записей, на текущей модели."
                + " MCP доступен через каталог. Один краткий отчёт; общий с explore_project лимит — 2 запуска за ответ.";
    }
    @Override public Map<String, Object> inputSchema() {
        return Json.object("type", "object", "properties", Json.object(
                "role", Json.object("type", "string", "enum", List.of("research", "review", "verify")),
                "task", Json.object("type", "string", "description", "Задача до 4000 символов"),
                "scope", Json.object("type", "string", "description", "Ориентир поиска до 1000 символов; не граница доступа")),
                "required", List.of("role", "task"), "additionalProperties", false);
    }
    @Override public String summary(Map<String, Object> input) { return "Делегирует " + input.get("role"); }
    @Override public Result call(Map<String, Object> input) throws ToolException { return call(input, "delegate"); }
    Result call(Map<String, Object> input, String parentCall) throws ToolException {
        String role = ExploreProject.argument(input, "role", 20, true);
        String task = ExploreProject.argument(input, "task", 4000, true);
        String scope = ExploreProject.argument(input, "scope", 1000, false);
        if (!List.of("research", "review", "verify").contains(role)
                || input.keySet().stream().anyMatch(key -> !List.of("role", "task", "scope").contains(key))) {
            throw new ToolException(ToolUse.Problem.BAD_ARGUMENTS, "Нужны role (research/review/verify), task и необязательный scope");
        }
        return runner.run(role, task, scope, parentCall, name());
    }
}
