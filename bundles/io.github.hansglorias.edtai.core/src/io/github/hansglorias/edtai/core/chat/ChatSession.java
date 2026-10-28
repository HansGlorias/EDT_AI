package io.github.hansglorias.edtai.core.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import io.github.hansglorias.edtai.core.chat.ChatMessage.Status;
import io.github.hansglorias.edtai.core.context.ContextItem;

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
        return send(text, List.of());
    }

    /**
     * Добавляет сообщение пользователя с фрагментами кода и запрашивает ответ.
     * Модель получает фрагменты перед текстом сообщения.
     *
     * @throws IllegalArgumentException если текст пустой
     * @throws IllegalStateException    если предыдущий запрос ещё не завершён
     */
    public CompletableFuture<ChatMessage> send(String text, List<ContextItem> context) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Пустое сообщение");
        }
        synchronized (lock) {
            checkIdle();
            add(new ChatMessage(ChatRole.USER, text.strip(), Instant.now(), Status.DONE, context));
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

    /**
     * «Изменить» своё сообщение: убирает его и всё, что после него.
     *
     * @return убранное сообщение — его текст и фрагменты возвращают в поле ввода
     * @throws IllegalStateException    если запрос выполняется
     * @throws IllegalArgumentException если по этому номеру не сообщение пользователя
     */
    public ChatMessage editFrom(int index) {
        ChatMessage message;
        synchronized (lock) {
            checkIdle();
            if (index < 0 || index >= messages.size() || messages.get(index).role() != ChatRole.USER) {
                throw new IllegalArgumentException("Изменить можно только своё сообщение");
            }
            message = messages.get(index);
            messages.subList(index, messages.size()).clear();
        }
        listeners.forEach(ChatListener::historyChanged);
        return message;
    }

    /**
     * Заменяет историю сохранённой. Неоконченный ответ становится
     * остановленным: продолжить его уже нельзя.
     *
     * @throws IllegalStateException если запрос выполняется
     */
    public void restore(List<ChatMessage> saved) {
        synchronized (lock) {
            checkIdle();
            messages.clear();
            for (ChatMessage message : saved) {
                ChatMessage restored = message.status() == Status.STREAMING ? message.with(message.text(), Status.STOPPED) : message;
                messages.add(restored.withTools(restored.tools().stream().map(ChatSession::interrupted).toList()));
            }
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
            reply = provider.reply(history, part -> onPart(request, part), use -> onTool(request, use));
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

    private void onTool(Request request, ToolUse use) {
        synchronized (lock) {
            if (request.finished) {
                return;
            }
            int existing = -1;
            for (int i = 0; i < request.tools.size() && existing < 0; i++) {
                if (!use.id().isEmpty() && request.tools.get(i).id().equals(use.id())) {
                    existing = i;
                }
            }
            if (existing >= 0) {
                request.tools.set(existing, use);
            } else {
                request.tools.add(use);
            }
            put(request, request.text.toString(), Status.STREAMING);
        }
    }

    private ChatMessage finish(Request request, String answer, Throwable error) {
        ChatMessage result;
        synchronized (lock) {
            request.finished = true;
            request.tools.replaceAll(ChatSession::interrupted);
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
            ChatMessage message = new ChatMessage(ChatRole.ASSISTANT, text, Instant.now(), status, List.of(), request.tools);
            request.index = messages.size();
            add(message);
            return message;
        }
        ChatMessage message = messages.get(request.index).with(text, status).withTools(request.tools);
        messages.set(request.index, message);
        int index = request.index;
        listeners.forEach(listener -> listener.messageUpdated(index, message));
        return message;
    }

    private void add(ChatMessage message) {
        messages.add(message);
        listeners.forEach(listener -> listener.messageAdded(message));
    }

    /** Инструмент, который не успел завершиться до конца ответа. */
    private static ToolUse interrupted(ToolUse use) {
        return use.status() == ToolUse.Status.RUNNING
                ? new ToolUse(use.id(), use.name(), use.summary(), "прервано", ToolUse.Status.FAILED)
                : use;
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
        final List<ToolUse> tools = new ArrayList<>();
        /** Номер ответа ассистента в истории, -1 — ещё не добавлен. */
        int index = -1;
        boolean finished;
        CompletableFuture<String> reply;
    }
}
