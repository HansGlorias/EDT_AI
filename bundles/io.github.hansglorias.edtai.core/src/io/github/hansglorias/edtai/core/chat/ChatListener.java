package io.github.hansglorias.edtai.core.chat;

/**
 * Подписчик на изменения {@link ChatSession}. Вызывается из любого потока.
 */
public interface ChatListener {

    void messageAdded(ChatMessage message);

    void cleared();

    void busyChanged(boolean busy);
}
