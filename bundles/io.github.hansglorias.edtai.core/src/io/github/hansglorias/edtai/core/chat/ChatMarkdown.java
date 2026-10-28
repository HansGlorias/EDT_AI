package io.github.hansglorias.edtai.core.chat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import io.github.hansglorias.edtai.core.context.ContextItem;

/**
 * Диалог в Markdown — чтобы сохранить в файл, приложить к задаче или
 * отправить коллеге: вопросы с прикреплённым кодом, ответы с вызовами
 * инструментов на своих местах.
 */
public final class ChatMarkdown {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private ChatMarkdown() {
    }

    public static String export(String title, List<ChatMessage> messages, ZoneId zone) {
        StringBuilder out = new StringBuilder("# ").append(title.strip()).append("\n\n");
        for (ChatMessage message : messages) {
            out.append("## ").append(heading(message)).append(time(message.time(), zone)).append("\n\n");
            switch (message.role()) {
                case USER -> {
                    paragraph(out, message.text());
                    for (ContextItem item : message.context()) {
                        out.append("Прикреплено: `").append(item.label()).append("` (").append(item.project()).append('/')
                                .append(item.path()).append(")\n\n");
                        code(out, item.language(), item.text());
                    }
                }
                case ASSISTANT -> answer(out, message);
                default -> paragraph(out, message.text());
            }
        }
        return out.toString().stripTrailing() + "\n";
    }

    private static String heading(ChatMessage message) {
        return switch (message.role()) {
            case USER -> "Вопрос";
            case ASSISTANT -> message.status() == ChatMessage.Status.STOPPED ? "Ответ (остановлен)" : "Ответ";
            case ERROR -> "Ошибка";
            case SUMMARY -> "Краткое содержание диалога выше";
        };
    }

    private static String time(Instant time, ZoneId zone) {
        return Instant.EPOCH.equals(time) ? "" : " · " + TIME.format(time.atZone(zone));
    }

    private static void answer(StringBuilder out, ChatMessage message) {
        List<ToolUse> calls = message.tools().stream().filter(ToolUse::isCall).toList();
        for (AnswerParts.Part part : AnswerParts.split(message.text(), calls)) {
            if (part.isText()) {
                paragraph(out, part.text());
            } else {
                for (ToolUse use : part.tools()) {
                    out.append("- → ").append(use.label().replace("\n", " ")).append('\n');
                }
                out.append('\n');
            }
        }
    }

    private static void paragraph(StringBuilder out, String text) {
        String stripped = text.strip();
        if (!stripped.isEmpty()) {
            out.append(stripped).append("\n\n");
        }
    }

    /** Блок кода; ограда длиннее любой серии обратных кавычек внутри. */
    private static void code(StringBuilder out, String language, String text) {
        String fence = "```";
        while (text.contains(fence)) {
            fence += "`";
        }
        out.append(fence).append(language).append('\n').append(text.stripTrailing()).append('\n').append(fence).append("\n\n");
    }
}
