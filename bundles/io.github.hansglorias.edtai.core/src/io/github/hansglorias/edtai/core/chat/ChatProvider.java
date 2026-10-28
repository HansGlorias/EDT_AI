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
}
