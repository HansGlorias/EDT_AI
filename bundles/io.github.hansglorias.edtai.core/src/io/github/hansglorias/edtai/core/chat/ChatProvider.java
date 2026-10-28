package io.github.hansglorias.edtai.core.chat;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Источник ответов в чате: модель или заглушка.
 */
public interface ChatProvider {

    /** Короткое название для строки состояния. */
    String name();

    /**
     * Запрашивает ответ на историю диалога. Не блокирует вызывающий поток.
     *
     * @param history сообщения пользователя и ассистента, последнее — от пользователя
     */
    CompletableFuture<String> reply(List<ChatMessage> history);
}
