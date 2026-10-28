package io.github.hansglorias.edtai.core.mcp;

import io.github.hansglorias.edtai.core.json.Json;

/**
 * Результат {@code tools/call} текстом для модели.
 *
 * @param error инструмент сообщил об ошибке ({@code isError})
 */
public record McpCallResult(String text, boolean error) {

    /**
     * Текстовые части, текст встроенных ресурсов (так отвечает EDT-MCP —
     * Markdown в {@code resource}); вместо картинок и двоичных данных —
     * пометка. Если текста нет — {@code structuredContent} как JSON.
     */
    static McpCallResult of(Object result) {
        StringBuilder text = new StringBuilder();
        for (Object part : Json.asList(Json.at(result, "content"))) {
            String piece = switch (String.valueOf(Json.stringAt(part, "type"))) {
                case "text" -> Json.stringAt(part, "text");
                case "resource" -> {
                    String inner = Json.stringAt(part, "resource", "text");
                    yield inner != null ? inner
                            : "[двоичный ресурс " + Json.stringAt(part, "resource", "uri") + ", "
                                    + Json.stringAt(part, "resource", "mimeType") + " — не передаётся]";
                }
                case "image", "audio" -> "[" + Json.stringAt(part, "type") + " " + Json.stringAt(part, "mimeType") + " — не передаётся]";
                case "resource_link" -> "[ресурс " + Json.stringAt(part, "uri") + "]";
                default -> null;
            };
            if (piece != null) {
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append(piece);
            }
        }
        Object structured = Json.at(result, "structuredContent");
        if (text.length() == 0 && structured != null) {
            text.append(Json.write(structured));
        }
        return new McpCallResult(text.toString(), Boolean.TRUE.equals(Json.at(result, "isError")));
    }
}
