package io.github.hansglorias.edtai.core.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;

import io.github.hansglorias.edtai.core.settings.McpServer;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolException;

/** Инструмент MCP-сервера для модели. Вызов — с подтверждением пользователя. */
final class McpTool implements Tool {

    private static final int MAX_SUMMARY_ARGUMENT = 60;
    private static final int MAX_OUTCOME = 80;

    private final McpHub hub;
    private final McpServer server;
    private final McpToolInfo info;
    private final String name;
    private final IntSupplier limit;

    McpTool(McpHub hub, McpServer server, McpToolInfo info, String name, IntSupplier limit) {
        this.hub = hub;
        this.server = server;
        this.info = info;
        this.name = name;
        this.limit = limit;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        String description = info.description().isBlank() ? info.name() : info.description();
        return description + "\n(инструмент MCP-сервера «" + server.name() + "»)";
    }

    @Override
    public Map<String, Object> inputSchema() {
        if ("object".equals(info.inputSchema().get("type"))) {
            return info.inputSchema();
        }
        // Схема без type: object — некоторые модели такую не принимают.
        Map<String, Object> schema = new LinkedHashMap<>(info.inputSchema());
        schema.put("type", "object");
        return schema;
    }

    @Override
    public boolean needsApproval() {
        return true;
    }

    /** «MCP «EDT»: read_module_source (ОбщийМодуль.Х)» — значения первых двух аргументов. */
    @Override
    public String summary(Map<String, Object> input) {
        StringBuilder arguments = new StringBuilder();
        int shown = 0;
        for (Object value : input.values()) {
            if (shown == 2 || !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                continue;
            }
            String text = String.valueOf(value).strip().replaceAll("\\s+", " ");
            if (text.length() > MAX_SUMMARY_ARGUMENT) {
                text = text.substring(0, MAX_SUMMARY_ARGUMENT) + "…";
            }
            arguments.append(shown++ == 0 ? "" : ", ").append(text);
        }
        return "MCP «" + server.name() + "»: " + info.name() + (arguments.length() == 0 ? "" : " (" + arguments + ")");
    }

    @Override
    public Result call(Map<String, Object> input) throws ToolException {
        McpCallResult result = hub.call(server, info.name(), input);
        String text = result.text();
        int max = limit.getAsInt();
        if (text.length() > max) {
            text = text.substring(0, max) + "\n(результат обрезан по лимиту контекста: " + max + " из " + result.text().length()
                    + " символов)";
        }
        if (result.error()) {
            throw new ToolException(text.isBlank() ? "инструмент вернул ошибку без описания" : text);
        }
        return new Result(text, outcome(result.text()));
    }

    /** Итог для ленты: первая непустая строка результата или его размер. */
    private static String outcome(String text) {
        for (String line : text.split("\n")) {
            String value = line.strip().replaceAll("^[#>*\\-\\s]+", "");
            if (!value.isEmpty()) {
                return value.length() > MAX_OUTCOME ? value.substring(0, MAX_OUTCOME) + "…" : value;
            }
        }
        return text.isEmpty() ? "пустой ответ" : text.length() + " символов";
    }
}
