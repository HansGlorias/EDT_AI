package io.github.hansglorias.edtai.core.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.tools.Tool;

/**
 * Бюджет запроса: сколько символов можно отправить модели, чтобы запрос
 * вместе с ответом поместился в её окно. Токены не считаем — оцениваем с
 * запасом: {@value #CHARS_PER_TOKEN} символа на токен (кириллица и код).
 * Если не помещается, сначала не передаётся код, прикреплённый к старым
 * сообщениям, потом сами старые сообщения; последний вопрос остаётся всегда.
 */
final class RequestBudget {

    static final int CHARS_PER_TOKEN = 3;
    /** Сколько токенов оставить на ответ, если max_tokens не задан. */
    static final int ANSWER_RESERVE = 4096;

    private RequestBudget() {
    }

    /** Бюджет в символах тела запроса. */
    static int chars(ModelConnection connection) {
        int window = connection.window();
        int reserve = connection.maxTokens() > 0 ? connection.maxTokens() : Math.min(ANSWER_RESERVE, window / 4);
        return Math.max(window / 4, window - reserve) * CHARS_PER_TOKEN;
    }

    /** Размер тела запроса в символах. */
    static int size(ModelConnection connection, List<ChatMessage> history, List<Object> extra, List<Tool> tools) {
        int size = Json.write(ProtocolAdapter.of(connection.protocol()).body(connection, history, extra, tools)).length();
        // Картинка стоит не по длине base64, а по числу пикселей: скриншот — порядка 1,5 тыс. токенов.
        for (ChatMessage message : history) {
            for (Attachment image : message.images()) {
                size += IMAGE_CHARS - image.data().length();
            }
        }
        return size;
    }

    /** Сколько символов считать за картинку. */
    static final int IMAGE_CHARS = 1600 * CHARS_PER_TOKEN;

    /**
     * Что передано модели из истории.
     *
     * @param withoutCode сколько старых сообщений отправлено без прикреплённого кода
     * @param dropped     сколько самых старых сообщений не отправлено
     */
    record Fitted(List<ChatMessage> history, int withoutCode, int dropped) {

        boolean trimmed() {
            return withoutCode > 0 || dropped > 0;
        }

        /** Строка для ленты: что модель в этот раз не видит. */
        ToolUse notice() {
            List<String> parts = new ArrayList<>();
            if (dropped > 0) {
                parts.add("модель не видит самые старые сообщения (" + dropped + ")");
            }
            if (withoutCode > 0) {
                parts.add((parts.isEmpty() ? "модель не видит " : "и ") + "прикреплённые к старым сообщениям код и файлы (" + withoutCode + ")");
            }
            return new ToolUse(NOTICE_ID, NOTICE_ID, "Диалог длиннее окна модели", String.join(" ", parts)
                    + ". Начните новый чат или увеличьте «Окно модели» в настройках", ToolUse.Status.DONE);
        }
    }

    static final String NOTICE_ID = "context";

    /**
     * Обрезает историю под бюджет.
     *
     * @param size размер запроса с такой историей
     */
    static Fitted fit(List<ChatMessage> history, ToIntFunction<List<ChatMessage>> size, int budget) {
        if (size.applyAsInt(history) <= budget) {
            return new Fitted(history, 0, 0);
        }
        List<ChatMessage> messages = new ArrayList<>(history);
        int last = lastUser(messages);
        int withoutCode = 0;
        for (int i = 0; i < last && size.applyAsInt(messages) > budget; i++) {
            ChatMessage message = messages.get(i);
            boolean files = !message.attachments().isEmpty() && message.role() != ChatRole.ASSISTANT;
            if (message.context().stream().anyMatch(item -> !item.text().isEmpty()) || files) {
                messages.set(i, message.withContext(message.context().stream().map(item -> item.truncate(0)).toList())
                        .withAttachments(List.of()));
                withoutCode++;
            }
        }
        int dropped = 0;
        while (last > 0 && size.applyAsInt(messages) > budget) {
            // Убираем самый старый обмен целиком: история должна начинаться с вопроса.
            do {
                messages.remove(0);
                dropped++;
                last--;
            } while (last > 0 && messages.get(0).role() != ChatRole.USER);
        }
        return new Fitted(messages, withoutCode, dropped);
    }

    private static int lastUser(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == ChatRole.USER) {
                return i;
            }
        }
        return 0;
    }
}
