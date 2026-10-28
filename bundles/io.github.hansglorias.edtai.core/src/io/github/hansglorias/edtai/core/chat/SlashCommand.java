package io.github.hansglorias.edtai.core.chat;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Команды поля ввода: {@code /review}, {@code /new}… — вместо пунктов меню. */
public enum SlashCommand {

    NEW("new", "новый чат"),
    REVIEW("review", "ревью незакоммиченных изменений; после команды можно уточнить, на что смотреть"),
    COMMIT("commit", "сообщение коммита для незакоммиченных изменений"),
    ATTACH("attach", "прикрепить модуль целиком (или @ и имя модуля)"),
    AUTO("auto", "авторежим: вызывать инструменты MCP без вопроса — включить или выключить"),
    READONLY("readonly", "только вопросы: модель не меняет проект — включить или выключить");

    /**
     * Команда в поле ввода.
     *
     * @param argument текст после команды, может быть пустым
     */
    public record Invocation(SlashCommand command, String argument) {
    }

    private final String word;
    private final String description;

    SlashCommand(String word, String description) {
        this.word = word;
        this.description = description;
    }

    /** Как набирается: {@code /review}. */
    public String text() {
        return "/" + word;
    }

    public String description() {
        return description;
    }

    /** Команда, если текст начинается с известной; иначе — обычное сообщение (например, путь «/home»). */
    public static Optional<Invocation> parse(String text) {
        String stripped = text.strip();
        if (!stripped.startsWith("/")) {
            return Optional.empty();
        }
        int end = 1;
        while (end < stripped.length() && !Character.isWhitespace(stripped.charAt(end))) {
            end++;
        }
        String word = stripped.substring(1, end).toLowerCase(Locale.ROOT);
        String argument = stripped.substring(end).strip();
        return Arrays.stream(values()).filter(command -> command.word.equals(word)).findFirst()
                .map(command -> new Invocation(command, argument));
    }

    /** Команды, начинающиеся с {@code prefix} (без «/»). */
    public static List<SlashCommand> matching(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(command -> command.word.startsWith(lower)).toList();
    }
}
