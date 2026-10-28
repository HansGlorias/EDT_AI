package io.github.hansglorias.edtai.core.chat;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import io.github.hansglorias.edtai.core.context.ContextItem;
import io.github.hansglorias.edtai.core.context.ContextPrompt;

/**
 * Сообщение диалога.
 *
 * @param status  для ответа ассистента: идёт генерация, готов или остановлен
 * @param context фрагменты кода, прикреплённые к сообщению пользователя
 * @param tools   инструменты, которые модель вызвала, пока готовила этот ответ
 */
public record ChatMessage(ChatRole role, String text, Instant time, Status status, List<ContextItem> context,
        List<ToolUse> tools) {

    public enum Status {
        /** Сообщение целиком. */
        DONE,
        /** Ответ ещё генерируется, текст будет расти. */
        STREAMING,
        /** Генерацию остановили или она оборвалась: текст неполный. */
        STOPPED
    }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(time, "time");
        status = Objects.requireNonNullElse(status, Status.DONE);
        context = List.copyOf(Objects.requireNonNullElse(context, List.of()));
        tools = List.copyOf(Objects.requireNonNullElse(tools, List.of()));
    }

    public ChatMessage(ChatRole role, String text, Instant time, Status status) {
        this(role, text, time, status, List.of(), List.of());
    }

    public ChatMessage(ChatRole role, String text, Instant time, Status status, List<ContextItem> context) {
        this(role, text, time, status, context, List.of());
    }

    public static ChatMessage of(ChatRole role, String text) {
        return new ChatMessage(role, text, Instant.now(), Status.DONE);
    }

    public ChatMessage with(String newText, Status newStatus) {
        return new ChatMessage(role, newText, time, newStatus, context, tools);
    }

    public ChatMessage withTools(List<ToolUse> newTools) {
        return new ChatMessage(role, text, time, status, context, newTools);
    }

    /** Начало следа инструментов в ответе, каким его видит модель. */
    public static final String TRACE = "[Вызванные инструменты: ";

    /**
     * Ответ без следа инструментов в начале: модель видит его у прошлых
     * ответов и иногда начинает писать сама.
     */
    public static String stripTrace(String answer) {
        String text = answer.stripLeading();
        if (!text.startsWith(TRACE)) {
            return answer;
        }
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return text.substring(i + 1).stripLeading();
            }
        }
        return answer;
    }

    /** Так модель видит краткое содержание: как сообщение пользователя. */
    public static final String SUMMARY_PREFIX = "[Краткое содержание предыдущей части диалога — сообщения выше уже не передаются]\n";

    public ChatMessage withContext(List<ContextItem> newContext) {
        return new ChatMessage(role, text, time, status, newContext, tools);
    }

    /**
     * Текст для модели: у вопроса — фрагменты кода, затем сам текст; у
     * ответа — перед текстом короткий след вызванных инструментов, чтобы в
     * следующих ответах модель знала, что уже смотрела (сами результаты в
     * историю не входят).
     */
    public String modelText() {
        if (role == ChatRole.SUMMARY) {
            return SUMMARY_PREFIX + text;
        }
        if (role == ChatRole.ASSISTANT) {
            String trace = tools.stream().filter(ToolUse::isCall).map(ToolUse::label).collect(Collectors.joining("; "));
            return trace.isEmpty() ? text : TRACE + trace + "]\n\n" + text;
        }
        return ContextPrompt.compose(text, context);
    }
}
