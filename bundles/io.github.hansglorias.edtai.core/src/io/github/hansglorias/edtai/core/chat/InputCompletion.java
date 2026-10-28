package io.github.hansglorias.edtai.core.chat;

import java.util.Optional;

/**
 * Что дописывать в поле ввода у курсора: модуль после {@code @} или
 * команду после {@code /} в начале сообщения.
 *
 * @param start  где начинается заменяемое: позиция {@code @} или {@code /}
 * @param prefix что набрано после {@code @} или {@code /}
 */
public record InputCompletion(Kind kind, int start, String prefix) {

    public enum Kind {
        MODULE, COMMAND
    }

    /** Текст поля после подстановки и где поставить курсор. */
    public record Applied(String text, int caret) {
    }

    /**
     * @param caret позиция курсора
     * @return пусто — у курсора нечего дописывать
     */
    public static Optional<InputCompletion> at(String text, int caret) {
        if (caret < 0 || caret > text.length()) {
            return Optional.empty();
        }
        int start = caret;
        while (start > 0 && !Character.isWhitespace(text.charAt(start - 1)) && text.charAt(start - 1) != '@') {
            start--;
        }
        if (start > 0 && text.charAt(start - 1) == '@' && (start == 1 || Character.isWhitespace(text.charAt(start - 2)))) {
            return Optional.of(new InputCompletion(Kind.MODULE, start - 1, text.substring(start, caret)));
        }
        String before = text.substring(0, caret);
        if (before.stripLeading().startsWith("/") && before.strip().indexOf(' ') < 0 && !before.endsWith(" ")) {
            int slash = before.indexOf('/');
            String prefix = before.substring(slash + 1);
            return prefix.contains("/") ? Optional.empty() : Optional.of(new InputCompletion(Kind.COMMAND, slash, prefix));
        }
        return Optional.empty();
    }

    /**
     * Заменяет набранное от {@link #start()} до курсора на {@code completion}
     * и пробел после него.
     */
    public Applied apply(String text, int caret, String completion) {
        String after = text.substring(caret);
        String inserted = after.startsWith(" ") ? completion : completion + " ";
        String result = text.substring(0, start) + inserted + after;
        return new Applied(result, start + inserted.length() + (after.startsWith(" ") ? 1 : 0));
    }
}
