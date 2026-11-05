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
 * @param attachments файлы и скриншоты, приложенные к сообщению пользователя
 * @param stats   у ответа ассистента: сколько он шёл и на что ушло время и токены; {@code null} — не известно
 *                (старые диалоги, вопросы)
 */
public record ChatMessage(ChatRole role, String text, Instant time, Status status, List<ContextItem> context,
        List<ToolUse> tools, List<Attachment> attachments, ReplyStats stats, ReplyModel replyModel) {

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
        attachments = List.copyOf(Objects.requireNonNullElse(attachments, List.of()));
    }

    public ChatMessage(ChatRole role, String text, Instant time, Status status, List<ContextItem> context,
            List<ToolUse> tools, List<Attachment> attachments, ReplyStats stats) {
        this(role, text, time, status, context, tools, attachments, stats, null);
    }

    public ChatMessage(ChatRole role, String text, Instant time, Status status, List<ContextItem> context, List<ToolUse> tools,
            List<Attachment> attachments) {
        this(role, text, time, status, context, tools, attachments, null);
    }

    public ChatMessage(ChatRole role, String text, Instant time, Status status, List<ContextItem> context, List<ToolUse> tools) {
        this(role, text, time, status, context, tools, List.of());
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
        return new ChatMessage(role, newText, time, newStatus, context, tools, attachments, stats, replyModel);
    }

    public ChatMessage withTools(List<ToolUse> newTools) {
        return new ChatMessage(role, text, time, status, context, newTools, attachments, stats, replyModel);
    }

    /** Только место для ответа в живой ленте; сохранять и передавать модели нечего. */
    public boolean isEmptyAnswer() {
        return role == ChatRole.ASSISTANT && text.isBlank() && tools.isEmpty() && attachments.isEmpty();
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

    /**
     * Ответ без следов инструментов в любом месте: небольшие модели, видя след у прошлых ответов,
     * пишут его посреди своего текста — с выдуманными вызовами. Незакрытый след остаётся как есть.
     */
    public static String stripTraces(String answer) {
        String text = stripTrace(answer);
        int from = text.indexOf(TRACE);
        while (from >= 0) {
            int depth = 0;
            int end = -1;
            for (int i = from; i < text.length() && end < 0; i++) {
                char c = text.charAt(i);
                if (c == '[') {
                    depth++;
                } else if (c == ']' && --depth == 0) {
                    end = i + 1;
                }
            }
            if (end < 0) {
                break;
            }
            String before = text.substring(0, from).stripTrailing();
            String after = text.substring(end).stripLeading();
            text = before.isEmpty() ? after : after.isEmpty() ? before : before + "\n\n" + after;
            from = text.indexOf(TRACE, before.length());
        }
        return text;
    }

    /** Так модель видит краткое содержание: как сообщение пользователя. */
    public static final String SUMMARY_PREFIX = "[Краткое содержание предыдущей части диалога — сообщения выше уже не передаются]\n";

    public ChatMessage withReplyModel(ReplyModel identity) {
        return new ChatMessage(role, text, time, status, context, tools, attachments, stats, identity);
    }

    public ChatMessage withStats(ReplyStats newStats) {
        return new ChatMessage(role, text, time, status, context, tools, attachments, newStats, replyModel);
    }

    public ChatMessage withContext(List<ContextItem> newContext) {
        return new ChatMessage(role, text, time, status, newContext, tools, attachments, stats, replyModel);
    }

    public ChatMessage withAttachments(List<Attachment> newAttachments) {
        return new ChatMessage(role, text, time, status, context, tools, newAttachments, stats, replyModel);
    }

    /** Картинки вопроса: модель получает их отдельными блоками перед текстом. Файлы ответа модели не уходят. */
    public List<Attachment> images() {
        return role == ChatRole.ASSISTANT ? List.of() : attachments.stream().filter(Attachment::isImage).toList();
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
            String trace = tools.stream().filter(use -> use.isCall() && !use.id().contains("/child/")).map(ToolUse::traceLabel)
                    .collect(Collectors.joining("; "));
            return trace.isEmpty() ? text : TRACE + trace + "]\n\n" + text;
        }
        String files = attachments.stream().map(Attachment::promptText).collect(Collectors.joining("\n\n"));
        String question = ContextPrompt.compose(text, context);
        return files.isEmpty() ? question : files + "\n\n" + question;
    }
}
