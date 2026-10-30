package io.github.hansglorias.edtai.core.chat;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Команды поля ввода: {@code /review}, {@code /new}… — вместо пунктов меню. */
public enum SlashCommand {

    NEW("new", "новый чат"),
    ANSWER("answer", "вопрос и ответ: дать прямой ответ"),
    OPTIONS("options", "варианты: предложить несколько решений с плюсами и минусами"),
    RECOMMEND("recommend", "рекомендация: выбрать лучший вариант и обосновать"),
    PLAN("plan", "план: разложить решение на последовательные шаги"),
    REVIEW("review", "ревью незакоммиченных изменений; после команды можно уточнить, на что смотреть"),
    EXPLAIN("explain", "объяснить код: прикреплённый, выделенный в редакторе или названный после команды"),
    TESTS("tests", "тесты для кода: прикреплённого, выделенного в редакторе или названного после команды"),
    COMPACT("compact", "сжать историю: модель дальше видит краткое содержание вместо сообщений выше"),
    MCP("mcp", "MCP-серверы: подключение и инструменты"),
    MODEL("model", "сменить модель: список или /model имя"),
    SKILL("skill", "создать скилл по описанию: модель напишет инструкцию и сохранит"),
    SKILLS("skills", "скиллы: встроенные, свои и проекта"),
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

    /** Инструкция режима диалога; пусто для остальных команд. */
    public String dialoguePrompt() {
        return switch (this) {
            case ANSWER -> "Дай прямой ответ на вопрос. При необходимости укажи важные оговорки.";
            case OPTIONS -> "Предложи несколько вариантов решения. Для каждого укажи плюсы, минусы и когда его выбирать.";
            case RECOMMEND -> "Сравни возможные варианты, выбери лучший и обоснуй рекомендацию.";
            case PLAN -> "Составь план реализации: цель, допущения, последовательные шаги, зависимости, риски и критерии готовности. Не выполняй план и не изменяй файлы.";
            default -> "";
        };
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
