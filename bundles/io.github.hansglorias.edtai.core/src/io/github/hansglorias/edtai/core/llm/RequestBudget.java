package io.github.hansglorias.edtai.core.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.ToIntFunction;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.tools.Tool;

/**
 * Бюджет запроса: сколько символов можно отправить модели, чтобы запрос
 * вместе с ответом укладывался в оценку окна. Токены не считаем — эвристика: {@value #CHARS_PER_TOKEN} символа на токен (кириллица и код).
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
        return chars(connection.window(), reserve(connection.window(), OutputPolicy.maxTokens(connection)));
    }

    private static int reserve(int window, OptionalInt maxTokens) {
        return maxTokens.orElse(Math.min(ANSWER_RESERVE, window / 4));
    }

    private static int chars(int window, int reserve) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (long) window - reserve) * CHARS_PER_TOKEN);
    }

    /** Невозможный резерв — ошибка настройки, до сокращения истории и HTTP. */
    static void requireInputSpace(ModelConnection connection) throws ModelException {
        int reserve = reserve(connection.window(), OutputPolicy.maxTokens(connection));
        if (reserve >= connection.window()) {
            throw new ModelException("Лимит ответа " + reserve + " токенов при окне " + connection.window()
                    + " токенов не оставляет места для запроса. Уменьшите лимит ответа или укажите реальное окно сервера.");
        }
    }

    static void requireFits(ModelConnection connection, PreparedRequest request) throws ModelException {
        int reserve = reserve(connection.window(), request.maxTokens());
        requireFits(connection, request, chars(connection.window(), reserve));
    }

    /** Лимит может быть меньше обычного после серверного отказа по размеру окна. */
    static void requireFits(ModelConnection connection, PreparedRequest request, int budget) throws ModelException {
        int reserve = reserve(connection.window(), request.maxTokens());
        if (reserve >= connection.window() || request.estimatedInputChars() > budget) {
            throw failure(connection.window(), reserve, request.estimatedInputChars(), budget, request.maxTokens().isEmpty());
        }
    }

    private static ModelException failure(int window, int reserve, int inputChars, int budget, boolean assumedReserve) {
        return new ModelException("Запрос не помещается в оценочный бюджет: вход ≈" + ((inputChars + 2L) / CHARS_PER_TOKEN)
                + " токенов (" + inputChars + " условных символов), доступно " + budget + " условных символов; резерв ответа "
                + reserve + (assumedReserve ? " токенов (предположение плагина, max_tokens не отправлен)" : " токенов (max_tokens)")
                + ", окно " + window + " токенов. Уменьшите лимит ответа или размер запроса либо укажите реальное окно сервера."
                + " Это оценка, а не точная токенизация.");
    }

    /** Оценочный размер входа в условных символах; картинки учитываются эвристикой. */
    static int size(ModelConnection connection, List<ChatMessage> history, List<Object> extra, List<Tool> tools) {
        return PreparedRequest.prepare(connection, history, extra, tools).estimatedInputChars();
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
