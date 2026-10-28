package io.github.hansglorias.edtai.core.chat;

/**
 * Подписчик на изменения {@link ChatSession}. Вызывается из любого потока.
 */
public interface ChatListener {

    /** В конец диалога добавлено сообщение. */
    default void messageAdded(ChatMessage message) {
    }

    /** Сообщение с номером {@code index} заменено: например, вырос текст ответа. */
    default void messageUpdated(int index, ChatMessage message) {
    }

    /** История изменилась целиком: очистка или повтор запроса. */
    default void historyChanged() {
    }

    default void busyChanged(boolean busy) {
    }
}
