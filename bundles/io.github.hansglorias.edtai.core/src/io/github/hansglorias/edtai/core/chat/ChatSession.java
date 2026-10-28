package io.github.hansglorias.edtai.core.chat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * История одного диалога и отправка сообщений провайдеру. Одновременно
 * обрабатывается не больше одного запроса.
 */
public final class ChatSession {

    private final ChatProvider provider;
    private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
    private final List<ChatListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean busy = new AtomicBoolean();

    public ChatSession(ChatProvider provider) {
        this.provider = provider;
    }

    public ChatProvider provider() {
        return provider;
    }

    public List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    public boolean isBusy() {
        return busy.get();
    }

    public void addListener(ChatListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ChatListener listener) {
        listeners.remove(listener);
    }

    /**
     * Добавляет сообщение пользователя и запрашивает ответ.
     *
     * @return ответ ассистента или сообщение об ошибке
     * @throws IllegalArgumentException если текст пустой
     * @throws IllegalStateException    если предыдущий запрос ещё не завершён
     */
    public CompletableFuture<ChatMessage> send(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Пустое сообщение");
        }
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("Предыдущий запрос ещё выполняется");
        }
        listeners.forEach(listener -> listener.busyChanged(true));
        add(ChatMessage.of(ChatRole.USER, text.strip()));
        List<ChatMessage> history = messages.stream().filter(message -> message.role() != ChatRole.ERROR).toList();

        CompletableFuture<String> reply;
        try {
            reply = provider.reply(history);
        } catch (RuntimeException e) {
            reply = CompletableFuture.failedFuture(e);
        }
        return reply.handle((answer, error) -> {
            ChatMessage message = error == null
                    ? ChatMessage.of(ChatRole.ASSISTANT, answer)
                    : ChatMessage.of(ChatRole.ERROR, describe(error));
            add(message);
            busy.set(false);
            listeners.forEach(listener -> listener.busyChanged(false));
            return message;
        });
    }

    public void clear() {
        messages.clear();
        listeners.forEach(ChatListener::cleared);
    }

    private void add(ChatMessage message) {
        messages.add(message);
        listeners.forEach(listener -> listener.messageAdded(message));
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        String text = cause.getMessage();
        return "Ошибка: " + (text == null || text.isBlank() ? cause.getClass().getSimpleName() : text);
    }
}
