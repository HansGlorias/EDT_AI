package io.github.hansglorias.edtai.core.llm;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Predicate;

import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolException;

/** Снимок разрешённых MCP одного ответа; схемы выбираются только между запросами. */
final class McpToolCatalog implements Tool {
    static final String NAME = "discover_mcp_tools";
    static final int PAGE_SIZE = 10;
    static final int MAX_ACTIVE = 8;
    static final String NOTE = "Каталог — снимок доступных серверных инструментов на начало ответа. "
            + "Серверное расширение через enable_toolset и подобные операции пока не поддерживается: "
            + "новые схемы автоматически не появятся. select полностью заменяет активный набор со следующего запроса модели.";
    private final List<Tool> candidates;
    private List<Tool> selected = List.of();
    private boolean disabled;
    private Predicate<List<Tool>> fits = tools -> true;

    McpToolCatalog(List<Tool> tools) {
        candidates = tools.stream().filter(Tool::mcp).toList();
    }

    boolean empty() { return candidates.isEmpty(); }
    void fits(Predicate<List<Tool>> value) { fits = value; }
    void disable() { disabled = true; selected = List.of(); }
    List<Tool> selected() { return selected; }
    void restore(List<Tool> previous) { selected = previous; }

    @Override public String name() { return NAME; }
    @Override public String description() {
        return "Find available MCP tools: list/search pages of names and short descriptions using optional query substring, then select exact names to load schemas. "
                + "select replaces all active MCP schemas (maximum 8); call selected tools only in the next model request. "
                + "No MCP tools can be called before selection. Server-side toolset expansion is not supported.";
    }
    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "action", Map.of("type", "string", "enum", List.of("list", "select")),
                "offset", Map.of("type", "integer", "minimum", 0),
                "query", Map.of("type", "string", "maxLength", 200),
                "names", Map.of("type", "array", "maxItems", MAX_ACTIVE, "items", Map.of("type", "string"))),
                "required", List.of("action"), "additionalProperties", false);
    }
    @Override public boolean safeInPlan() { return true; }
    @Override public String summary(Map<String, Object> input) { return "Выбирает доступные инструменты MCP"; }

    @Override public Result call(Map<String, Object> input) throws ToolException {
        if (disabled) throw bad("MCP отключены после отказа сервера модели принимать схемы");
        if (!input.keySet().stream().allMatch(List.of("action", "offset", "names", "query")::contains)) throw bad("Неизвестное поле");
        if ("list".equals(input.get("action"))) return list(input);
        if ("select".equals(input.get("action"))) return select(input);
        throw bad("action должен быть list или select");
    }

    private Result list(Map<String, Object> input) throws ToolException {
        if (input.containsKey("names")) throw bad("names допустим только при select");
        String query = "";
        if (input.containsKey("query")) {
            if (!(input.get("query") instanceof String text) || text.length() > 200) throw bad("query: строка до 200 символов");
            query = text.strip().toLowerCase(Locale.ROOT);
        }
        String search = query;
        List<Tool> matches = candidates.stream().filter(tool -> search.isEmpty()
                || tool.name().toLowerCase(Locale.ROOT).contains(search)
                || tool.description().toLowerCase(Locale.ROOT).contains(search)).toList();
        int offset = 0;
        if (input.containsKey("offset")) {
            if (!(input.get("offset") instanceof Number number)) throw bad("offset должен быть целым числом");
            try { offset = new BigDecimal(number.toString()).intValueExact(); }
            catch (ArithmeticException | NumberFormatException e) { throw bad("offset должен быть целым числом"); }
        }
        if (offset < 0 || offset > matches.size()) throw bad("offset вне каталога");
        int end = Math.min(matches.size(), offset + PAGE_SIZE);
        List<Object> page = new ArrayList<>();
        for (Tool tool : matches.subList(offset, end)) {
            String description = tool.description().replaceAll("\\s+", " ").strip();
            if (description.length() > 160) description = description.substring(0, 159) + "…";
            page.add(Map.of("name", tool.name(), "description", description));
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("tools", page);
        output.put("offset", offset);
        output.put("next_offset", end < matches.size() ? end : null);
        output.put("more", end < matches.size());
        output.put("active", selected.stream().map(Tool::name).toList());
        output.put("note", NOTE);
        return new Result(Json.write(output), "инструменты " + offset + "–" + end + " из " + matches.size());
    }

    private Result select(Map<String, Object> input) throws ToolException {
        if (input.containsKey("offset") || input.containsKey("query")) throw bad("offset и query допустимы только при list");
        if (!(input.get("names") instanceof List<?> names) || names.size() > MAX_ACTIVE) throw bad("names: массив до 8 точных имён");
        List<Tool> replacement = new ArrayList<>();
        for (Object name : names) {
            if (!(name instanceof String text) || text.length() > 64 || replacement.stream().anyMatch(tool -> tool.name().equals(text))) throw bad("Имена должны быть строками без повторов");
            Tool tool = candidates.stream().filter(candidate -> candidate.name().equals(text)).findFirst()
                    .orElseThrow(() -> bad("Нет разрешённого инструмента: " + text));
            replacement.add(tool);
        }
        if (!fits.test(replacement)) throw bad("Схемы не помещаются в бюджет; выбери меньше инструментов");
        selected = List.copyOf(replacement);
        return new Result(Json.write(Map.of("active", selected.stream().map(Tool::name).toList(), "note", NOTE)),
                "выбрано " + selected.size() + " схем со следующего запроса");
    }

    private static ToolException bad(String text) { return new ToolException(ToolUse.Problem.BAD_ARGUMENTS, text); }
}
