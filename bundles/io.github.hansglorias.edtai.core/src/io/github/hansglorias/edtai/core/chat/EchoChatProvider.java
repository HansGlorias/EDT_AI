package io.github.hansglorias.edtai.core.chat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Заглушка вместо модели: асинхронно возвращает последнее сообщение
 * пользователя с префиксом {@link #PREFIX}.
 */
public final class EchoChatProvider implements ChatProvider {

    public static final String PREFIX = "Эхо: ";

    private final Executor executor;

    public EchoChatProvider() {
        this(Duration.ofMillis(300));
    }

    /** @param delay искусственная задержка, чтобы было видно асинхронность */
    public EchoChatProvider(Duration delay) {
        this.executor = CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public String name() {
        return "эхо";
    }

    @Override
    public CompletableFuture<String> reply(List<ChatMessage> history) {
        String last = history.stream()
                .filter(message -> message.role() == ChatRole.USER)
                .reduce((first, second) -> second)
                .map(ChatMessage::text)
                .orElse("");
        return CompletableFuture.supplyAsync(() -> PREFIX + last, executor);
    }
}
