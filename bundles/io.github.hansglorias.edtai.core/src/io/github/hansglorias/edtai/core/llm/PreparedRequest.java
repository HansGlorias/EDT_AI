package io.github.hansglorias.edtai.core.llm;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.tools.Tool;

/** Неизменяемый снимок тела одного запроса; подключение и токен здесь не хранятся. */
final class PreparedRequest {
    private final String body;
    private final int estimatedInputChars;
    private final OptionalInt maxTokens;
    private final int schemaCount;

    private PreparedRequest(Map<String, Object> payload) {
        int imageAdjustment = estimatedSize(0, payload);
        maxTokens = payload.get("max_tokens") instanceof Number value ? OptionalInt.of(value.intValue()) : OptionalInt.empty();
        schemaCount = Json.asList(payload.get("tools")).size();
        body = Json.write(payload);
        estimatedInputChars = body.length() + imageAdjustment;
    }

    /** Входные коллекции не должны меняться одновременно с подготовкой; после возврата они больше не нужны. */
    static PreparedRequest prepare(ModelConnection connection, List<ChatMessage> history, List<Object> extra, List<Tool> tools) {
        return new PreparedRequest(ProtocolAdapter.of(connection.protocol()).body(connection, history, extra, tools));
    }

    String body() { return body; }
    int utf16Chars() { return body.length(); }
    int utf8Bytes() { return body.getBytes(StandardCharsets.UTF_8).length; }
    /** Прежняя символьная эвристика бюджета, с поправкой на реально переданные картинки. */
    int estimatedInputChars() { return estimatedInputChars; }
    int estimatedInputTokens() { return estimatedInputChars / RequestBudget.CHARS_PER_TOKEN; }
    OptionalInt maxTokens() { return maxTokens; }
    int schemaCount() { return schemaCount; }

    private static int estimatedSize(int chars, Map<String, Object> payload) {
        int size = chars;
        for (Object message : Json.asList(payload.get("messages"))) {
            for (Object block : Json.asList(Json.at(message, "content"))) {
                String data = null;
                if ("image".equals(Json.stringAt(block, "type")) && "base64".equals(Json.stringAt(block, "source", "type"))) {
                    data = Json.stringAt(block, "source", "data");
                } else if ("image_url".equals(Json.stringAt(block, "type"))) {
                    String url = Json.stringAt(block, "image_url", "url");
                    int separator = url == null ? -1 : url.indexOf(";base64,");
                    if (url != null && url.startsWith("data:") && separator >= 0) data = url.substring(separator + 8);
                }
                if (data != null) size += RequestBudget.IMAGE_CHARS - data.length();
            }
        }
        return size;
    }

    @Override
    public String toString() {
        return "PreparedRequest[utf16Chars=" + utf16Chars() + ", utf8Bytes=" + utf8Bytes()
                + ", estimatedInputTokens=" + estimatedInputTokens() + ", schemaCount=" + schemaCount + "]";
    }
}
