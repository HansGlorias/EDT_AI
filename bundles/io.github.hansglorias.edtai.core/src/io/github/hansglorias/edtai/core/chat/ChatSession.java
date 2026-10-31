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

    private static final String COMPACT_ID = "compact";

    private final ChatProvider provider;
    private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
    private final List<ChatListener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    /** Текущий запрос, {@code null} — ничего не выполняется. Под {@link #lock}. */
    private Request current;
    /** Токены последнего ответа в этом диалоге; {@code null} — ответа ещё не было. */
    private volatile TokenUsage usage;

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

    /** Что происходит с идущим ответом; {@code null} — ответа нет. */
    public ReplyActivity activity() {
        synchronized (lock) {
            return current == null ? null : current.activity;
        }
    }

    /** Токены последнего ответа в этом диалоге; {@code null} — ответа ещё не было (после открытия чата тоже). */
    public TokenUsage usage() {
        return usage;
    }

    /** Сколько токенов займёт диалог в запросе к модели, по оценке провайдера; 0 — неизвестно. */
    public long estimateTokens() {
        List<ChatMessage> history;
        synchronized (lock) {
            history = history();
        }
        return history.isEmpty() ? 0 : provider.estimateTokens(history);
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
        return send(text, context, List.of());
    }

    /**
     * То же с приложенными файлами и скриншотами.
     *
     * @throws IllegalArgumentException если текст пустой
     * @throws IllegalStateException    если предыдущий запрос ещё не завершён
     */
    public CompletableFuture<ChatMessage> send(String text, List<ContextItem> context, List<Attachment> attachments) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Пустое сообщение");
        }
        synchronized (lock) {
            checkIdle();
            add(new ChatMessage(ChatRole.USER, text.strip(), Instant.now(), Status.DONE, context, List.of(), attachments));
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

    /** Есть ли что сжимать по команде: ничего не выполняется и после последнего краткого содержания есть ответ. */
    public boolean canCompact() {
        synchronized (lock) {
            return current == null && history().stream().anyMatch(message -> message.role() == ChatRole.ASSISTANT);
        }
    }

    /**
     * Сжимает историю по команде пользователя: краткое содержание всего, что
     * модель видит сейчас, встаёт в конец диалога, и дальше модель получает
     * его вместо сообщений выше. Не удалось — в истории ошибка; остановили —
     * ничего не меняется.
     *
     * @return сжата ли история
     * @throws IllegalStateException если запрос выполняется или сжимать нечего
     */
    public CompletableFuture<Boolean> compact() {
        synchronized (lock) {
            if (!canCompact()) {
                throw new IllegalStateException(current != null ? "Предыдущий запрос ещё выполняется" : "Сжимать нечего");
            }
            List<ChatMessage> history = history();
            Request request = new Request();
            request.activity = new ReplyActivity(ReplyActivity.Phase.TOOL, "Сжимаю историю диалога", Instant.now());
            current = request;
            listeners.forEach(listener -> listener.busyChanged(true));
            CompletableFuture<String> summary;
            try {
                summary = provider.summarize(history);
            } catch (RuntimeException e) {
                summary = CompletableFuture.failedFuture(e);
            }
            request.reply = summary;
            request.inner = summary;
            return summary.handle((text, error) -> {
                boolean done;
                synchronized (lock) {
                    request.finished = true;
                    Throwable cause = unwrap(error);
                    done = cause == null && text != null && !text.isBlank();
                    if (done) {
                        add(new ChatMessage(ChatRole.SUMMARY, text.strip(), Instant.now(), Status.DONE));
                    } else if (!(cause instanceof CancellationException)) {
                        add(ChatMessage.of(ChatRole.ERROR, "Не удалось сжать историю: "
                                + (cause == null ? "модель вернула пустой ответ" : describe(cause))));
                    }
                    if (current == request) {
                        current = null;
                    }
                }
                listeners.forEach(listener -> listener.busyChanged(false));
                return done;
            });
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
            CompletableFuture<String> inner = request.inner;
            if (inner != null) {
                inner.cancel(true);
            }
        }
    }

    /** Останавливает запрос, если он есть, и очищает историю. */
    public void clear() {
        cancel();
        synchronized (lock) {
            messages.clear();
            usage = null;
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
            usage = null;
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

    /**
     * Что уходит модели: без ошибок и пустых сообщений, начиная с последнего
     * краткого содержания. Под {@link #lock}.
     */
    private List<ChatMessage> history() {
        List<ChatMessage> history = messages.stream()
                .filter(message -> message.role() != ChatRole.ERROR && !message.text().isEmpty())
                .toList();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).role() == ChatRole.SUMMARY) {
                return history.subList(i, history.size());
            }
        }
        return history;
    }

    /** Под {@link #lock}. */
    private CompletableFuture<ChatMessage> request() {
        List<ChatMessage> history = history();
        Request request = new Request();
        current = request;
        listeners.forEach(listener -> listener.busyChanged(true));

        CompletableFuture<String> reply;
        try {
            int compact = provider.compactCount(history);
            reply = compact > 0 ? compactThenReply(request, history, compact) : reply(request, history);
        } catch (RuntimeException e) {
            reply = CompletableFuture.failedFuture(e);
        }
        request.reply = reply;
        return reply.handle((answer, error) -> finish(request, answer, error));
    }

    private CompletableFuture<String> reply(Request request, List<ChatMessage> history) {
        CompletableFuture<String> reply = provider.reply(history, part -> onPart(request, part), use -> onTool(request, use),
                tokens -> onUsage(request, tokens));
        request.inner = reply;
        return reply;
    }

    /**
     * Сначала краткое содержание первых {@code count} сообщений — оно встаёт в
     * историю после них, — потом ответ. Не удалось сжать — ответ без сжатия.
     */
    private CompletableFuture<String> compactThenReply(Request request, List<ChatMessage> history, int count) {
        ChatMessage last = history.get(count - 1);
        onTool(request, new ToolUse(COMPACT_ID, "context", "Сжимаю историю диалога", "", ToolUse.Status.RUNNING));
        CompletableFuture<String> summary = provider.summarize(history.subList(0, count));
        request.inner = summary;
        return summary.handle((text, error) -> text).thenCompose(text -> {
            synchronized (lock) {
                if (request.finished) {
                    return CompletableFuture.<String>failedFuture(new CancellationException());
                }
                if (text == null || text.isBlank()) {
                    onTool(request, new ToolUse(COMPACT_ID, "context", "Сжимаю историю диалога", "не удалось, ответ без сжатия",
                            ToolUse.Status.FAILED));
                } else {
                    int at = 0;
                    for (int i = 0; i < messages.size(); i++) {
                        if (messages.get(i) == last) {
                            at = i + 1;
                        }
                    }
                    messages.add(at, new ChatMessage(ChatRole.SUMMARY, text.strip(), Instant.now(), Status.DONE));
                    if (request.index >= at) {
                        request.index++;
                    }
                    onTool(request, new ToolUse(COMPACT_ID, "context", "История сжата", "сообщений: " + count
                            + " — модель дальше видит их краткое содержание", ToolUse.Status.DONE));
                    listeners.forEach(ChatListener::historyChanged);
                }
                return reply(request, history());
            }
        });
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
            if (request.activity.phase() != ReplyActivity.Phase.WRITING) {
                request.activity = request.activity.next(ReplyActivity.Phase.WRITING, "");
            }
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
            if (use.file() != null) {
                request.files.add(use.file());
                use = use.withFile(null);
            }
            if (existing >= 0) {
                use = use.withAt(request.tools.get(existing).at());
                request.tools.set(existing, use);
            } else {
                use = use.withAt(request.text.length());
                request.tools.add(use);
            }
            boolean working = use.status() == ToolUse.Status.RUNNING || use.status() == ToolUse.Status.AWAITING;
            request.activity = request.activity.next(working ? ReplyActivity.Phase.TOOL : ReplyActivity.Phase.THINKING,
                    working ? use.summary() : "");
            put(request, request.text.toString(), Status.STREAMING);
        }
    }

    private void onUsage(Request request, TokenUsage tokens) {
        synchronized (lock) {
            if (request.finished || tokens == null) {
                return;
            }
            usage = tokens;
        }
        listeners.forEach(listener -> listener.usageChanged(tokens));
    }

    private ChatMessage finish(Request request, String answer, Throwable error) {
        ChatMessage result;
        synchronized (lock) {
            request.finished = true;
            request.tools.replaceAll(ChatSession::interrupted);
            Throwable cause = unwrap(error);
            String partial = request.text.toString();
            if (cause == null) {
                String text = answer != null ? answer : partial;
                // Итоговый текст бывает короче потока (убраны пробелы и рассуждения в начале): сдвигаем места вызовов.
                int shift = partial.indexOf(text) > 0 ? partial.indexOf(text) : 0;
                request.tools.replaceAll(use -> use.at() < 0 ? use : use.withAt(Math.max(0, Math.min(text.length(), use.at() - shift))));
                result = put(request, text, Status.DONE);
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
            ChatMessage message = new ChatMessage(ChatRole.ASSISTANT, text, Instant.now(), status, List.of(), request.tools,
                    request.files);
            request.index = messages.size();
            add(message);
            return message;
        }
        ChatMessage message = messages.get(request.index).with(text, status).withTools(request.tools)
                .withAttachments(request.files);
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
        return use.status() == ToolUse.Status.RUNNING || use.status() == ToolUse.Status.AWAITING
                ? use.withProblem(ToolUse.Problem.INTERRUPTED, "")
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
        /** Файлы, которые модель приложила к ответу. */
        final List<Attachment> files = new ArrayList<>();
        /** Номер ответа ассистента в истории, -1 — ещё не добавлен. */
        int index = -1;
        boolean finished;
        CompletableFuture<String> reply;
        /** Запрос, который идёт сейчас: сжатие истории или сам ответ. */
        volatile CompletableFuture<String> inner;
        /** Под {@link ChatSession#lock}. */
        ReplyActivity activity = new ReplyActivity(ReplyActivity.Phase.THINKING, "", Instant.now());
    }
}
