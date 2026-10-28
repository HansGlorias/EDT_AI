package io.github.hansglorias.edtai.core.chat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Источник ответов в чате: модель или заглушка.
 */
public interface ChatProvider {

    /** Короткое название для строки состояния. */
    String name();

    /**
     * Запрашивает ответ на историю диалога. Не блокирует вызывающий поток.
     * Отмена возвращённого future ({@code cancel}) прерывает запрос.
     *
     * @param history сообщения пользователя и ассистента, последнее — от пользователя
     * @param onText  получает части ответа по мере генерации, из любого потока
     * @return полный текст ответа
     */
    CompletableFuture<String> reply(List<ChatMessage> history, Consumer<String> onText);

    /**
     * То же, но провайдер может вызывать инструменты и сообщать о них в
     * {@code onTool}: сначала со статусом {@link ToolUse.Status#RUNNING}, потом
     * с итогом, с тем же {@link ToolUse#id()}.
     */
    default CompletableFuture<String> reply(List<ChatMessage> history, Consumer<String> onText, Consumer<ToolUse> onTool) {
        return reply(history, onText);
    }

    /**
     * То же, и провайдер сообщает в {@code onUsage} токены ответа по мере
     * шагов: каждый раз — итог ответа на этот момент.
     */
    default CompletableFuture<String> reply(List<ChatMessage> history, Consumer<String> onText, Consumer<ToolUse> onTool,
            Consumer<TokenUsage> onUsage) {
        return reply(history, onText, onTool);
    }

    /** Окно модели в токенах; 0 — неизвестно. */
    default long window() {
        return 0;
    }

    /** Сколько токенов займёт история в запросе, по оценке; 0 — неизвестно. */
    default long estimateTokens(List<ChatMessage> history) {
        return 0;
    }

    /**
     * Сколько первых сообщений истории сжать в краткое содержание перед
     * ответом, потому что история перестаёт помещаться в окно модели.
     *
     * @return 0 — сжимать не нужно
     */
    default int compactCount(List<ChatMessage> history) {
        return 0;
    }

    /** Краткое содержание сообщений, сделанное моделью. */
    default CompletableFuture<String> summarize(List<ChatMessage> messages) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Сжатие истории не поддерживается"));
    }
}
