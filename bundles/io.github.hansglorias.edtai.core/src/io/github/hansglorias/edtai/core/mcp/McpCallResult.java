package io.github.hansglorias.edtai.core.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.json.JsonException;

/**
 * Результат {@code tools/call} текстом для модели.
 *
 * @param error инструмент сообщил об ошибке ({@code isError})
 * @param retryText прежнее представление результата для проверки ожидания сборки;
 *                  добавленные структурированные данные не меняют условия повтора
 */
public record McpCallResult(String text, boolean error, String retryText) {

    public McpCallResult(String text, boolean error) {
        this(text, error, text);
    }

    /**
     * Текстовые части, текст встроенных ресурсов (так отвечает EDT-MCP —
     * Markdown в {@code resource}); вместо картинок и двоичных данных —
     * пометка. Структура идёт перед текстом, даже если сервер прислал
     * текст-заглушку. Содержимое JSON не определяет успех операции: только
     * верхнеуровневый {@code isError} задаёт ошибку вызова.
     */
    static McpCallResult of(Object result) {
        List<String> pieces = new ArrayList<>();
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
            if (piece != null && !piece.isEmpty()) {
                pieces.add(piece);
            }
        }
        Object structured = Json.at(result, "structuredContent");
        String content = String.join("\n\n", pieces);
        String retryText = content.isEmpty() && structured != null ? Json.write(structured) : content;
        if (structured != null) {
            String report = Json.stringAt(structured, "report");
            pieces.removeIf(piece -> piece.equals(report) || sameJson(piece, structured));
            // Длинный report не должен оттеснять jobId, статус и ошибки
            // за границу обрезки. Значения и остальные поля не меняем.
            Map<String, Object> fields = Json.asObject(structured);
            if (fields != null && fields.containsKey("report")) {
                Map<String, Object> ordered = new LinkedHashMap<>(fields);
                Object value = ordered.remove("report");
                ordered.put("report", value);
                pieces.add(0, Json.write(ordered));
            } else {
                pieces.add(0, Json.write(structured));
            }
        }
        return new McpCallResult(String.join("\n\n", pieces), Boolean.TRUE.equals(Json.at(result, "isError")), retryText);
    }

    /** Удаляем только целый блок, дублирующий JSON; частичные совпадения оставляем. */
    private static boolean sameJson(String text, Object structured) {
        try {
            return structured.equals(Json.parse(text));
        } catch (JsonException e) {
            return false;
        }
    }
}
