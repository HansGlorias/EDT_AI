package io.github.hansglorias.edtai.core.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Заглушка вместо модели для тестов: возвращает последнее сообщение
 * пользователя с префиксом {@link #PREFIX}, по словам, как будто модель
 * генерирует ответ.
 */
public final class EchoChatProvider implements ChatProvider {

    public static final String PREFIX = "Эхо: ";

    private static final Pattern WORD = Pattern.compile("\\S+\\s*|\\s+");

    private final Duration delay;

    public EchoChatProvider() {
        this(Duration.ofMillis(50));
    }

    /** @param delay пауза перед каждым словом ответа */
    public EchoChatProvider(Duration delay) {
        this.delay = delay;
    }

    @Override
    public String name() {
        return "эхо";
    }

    @Override
    public CompletableFuture<String> reply(List<ChatMessage> history, Consumer<String> onText) {
        String last = history.stream()
                .filter(message -> message.role() == ChatRole.USER)
                .reduce((first, second) -> second)
                .map(ChatMessage::text)
                .orElse("");
        String answer = PREFIX + last;
        List<String> words = new ArrayList<>();
        Matcher matcher = WORD.matcher(answer);
        while (matcher.find()) {
            words.add(matcher.group());
        }
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                for (String word : words) {
                    if (!delay.isZero()) {
                        Thread.sleep(delay.toMillis());
                    }
                    if (result.isDone()) {
                        return;
                    }
                    onText.accept(word);
                }
                result.complete(answer);
            } catch (InterruptedException e) {
                result.cancel(false);
            }
        }, "EDT AI: эхо");
        thread.setDaemon(true);
        thread.start();
        return result;
    }
}
