package io.github.hansglorias.edtai.core.chat;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import io.github.hansglorias.edtai.core.chat.ChatMessage.Status;

/**
 * История одного диалога и отправка сообщений провайдеру. Одновременно
 * обрабатывается не больше одного запроса. Ответ ассистента появляется в
 * истории с первой полученной частью и растёт по мере генерации.
 */
public final class ChatSession {

    private final ChatProvider provider;
    private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
    private final List<ChatListener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    /** Текущий запрос, {@code null} — ничего не выполняется. Под {@link #lock}. */
    private Request current;

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
        synchronized (lock) {
            return current != null;
        }
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
     * @return ответ ассистента (возможно, остановленный) или сообщение об ошибке
     * @throws IllegalArgumentException если текст пустой
     * @throws IllegalStateException    если предыдущий запрос ещё не завершён
     */
    public CompletableFuture<ChatMessage> send(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Пустое сообщение");
        }
        synchronized (lock) {
            checkIdle();
            add(ChatMessage.of(ChatRole.USER, text.strip()));
            return request();
        }
    }

    /** Можно ли повторить запрос: есть сообщение пользователя и ничего не выполняется. */
    public boolean canRetry() {
        synchronized (lock) {
            return current == null && messages.stream().anyMatch(message -> message.role() == ChatRole.USER);
        }
    }

    /**
     * Убирает всё после последнего сообщения пользователя (ответ, ошибку) и
     * запрашивает ответ заново.
     *
     * @throws IllegalStateException если запрос выполняется или повторять нечего
     */
    public CompletableFuture<ChatMessage> retry() {
        synchronized (lock) {
            checkIdle();
            int lastUser = -1;
            for (int i = messages.size() - 1; i >= 0 && lastUser < 0; i--) {
                if (messages.get(i).role() == ChatRole.USER) {
                    lastUser = i;
                }
            }
            if (lastUser < 0) {
                throw new IllegalStateException("Нет сообщения, которое можно повторить");
            }
            if (lastUser < messages.size() - 1) {
                messages.subList(lastUser + 1, messages.size()).clear();
                listeners.forEach(ChatListener::historyChanged);
            }
            return request();
        }
    }

    /** Останавливает текущий запрос. Полученная часть ответа остаётся в истории. */
    public void cancel() {
        Request request;
        synchronized (lock) {
            request = current;
        }
        if (request != null) {
            request.reply.cancel(true);
        }
    }

    /** Останавливает запрос, если он есть, и очищает историю. */
    public void clear() {
        cancel();
        synchronized (lock) {
            messages.clear();
        }
        listeners.forEach(ChatListener::historyChanged);
    }

    private void checkIdle() {
        if (current != null) {
            throw new IllegalStateException("Предыдущий запрос ещё выполняется");
        }
    }

    /** Под {@link #lock}. */
    private CompletableFuture<ChatMessage> request() {
        List<ChatMessage> history = messages.stream()
                .filter(message -> message.role() != ChatRole.ERROR && !message.text().isEmpty())
                .toList();
        Request request = new Request();
        current = request;
        listeners.forEach(listener -> listener.busyChanged(true));

        CompletableFuture<String> reply;
        try {
            reply = provider.reply(history, part -> onPart(request, part));
        } catch (RuntimeException e) {
            reply = CompletableFuture.failedFuture(e);
        }
        request.reply = reply;
        return reply.handle((answer, error) -> finish(request, answer, error));
    }

    private void onPart(Request request, String part) {
        if (part == null || part.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (request.finished) {
                return;
            }
            request.text.append(part);
            put(request, request.text.toString(), Status.STREAMING);
        }
    }

    private ChatMessage finish(Request request, String answer, Throwable error) {
        ChatMessage result;
        synchronized (lock) {
            request.finished = true;
            Throwable cause = unwrap(error);
            String partial = request.text.toString();
            if (cause == null) {
                result = put(request, answer != null ? answer : partial, Status.DONE);
            } else if (cause instanceof CancellationException) {
                result = put(request, partial, Status.STOPPED);
            } else {
                if (request.index >= 0) {
                    put(request, partial, Status.STOPPED);
                }
                result = ChatMessage.of(ChatRole.ERROR, describe(cause));
                add(result);
            }
            if (current == request) {
                current = null;
            }
        }
        listeners.forEach(listener -> listener.busyChanged(false));
        return result;
    }

    /** Добавляет или обновляет ответ ассистента этого запроса. Под {@link #lock}. */
    private ChatMessage put(Request request, String text, Status status) {
        if (request.index < 0 || request.index >= messages.size()) {
            ChatMessage message = new ChatMessage(ChatRole.ASSISTANT, text, Instant.now(), status);
            request.index = messages.size();
            add(message);
            return message;
        }
        ChatMessage message = messages.get(request.index).with(text, status);
        messages.set(request.index, message);
        int index = request.index;
        listeners.forEach(listener -> listener.messageUpdated(index, message));
        return message;
    }

    private void add(ChatMessage message) {
        messages.add(message);
        listeners.forEach(listener -> listener.messageAdded(message));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String describe(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }

    private static final class Request {
        final StringBuilder text = new StringBuilder();
        /** Номер ответа ассистента в истории, -1 — ещё не добавлен. */
        int index = -1;
        boolean finished;
        CompletableFuture<String> reply;
    }
}
