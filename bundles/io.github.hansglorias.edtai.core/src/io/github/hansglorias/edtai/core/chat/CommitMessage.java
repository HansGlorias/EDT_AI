package io.github.hansglorias.edtai.core.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Текст коммита из ответа модели — для окна коммита EGit. */
public final class CommitMessage {

    private static final Pattern FENCE = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})[^\\n]*\\n(.*?)\\n {0,3}\\1[`~]*[ \\t]*$", Pattern.DOTALL);

    private CommitMessage() {
    }

    /** Содержимое первого блока кода; без блока — ответ целиком. Пусто — модель текста не дала. */
    public static String extract(String answer) {
        String text = answer == null ? "" : answer.replace("\r\n", "\n");
        Matcher block = FENCE.matcher(text);
        return (block.find() ? block.group(2) : text).strip();
    }
}
