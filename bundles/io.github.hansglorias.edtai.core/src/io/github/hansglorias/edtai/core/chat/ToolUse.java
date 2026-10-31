package io.github.hansglorias.edtai.core.chat;

import java.util.Objects;
import java.util.Set;

/**
 * Вызов инструмента моделью — для показа в ленте: что модель прочитала или
 * искала. Сам результат уходит модели и в историю диалога не сохраняется.
 *
 * @param id      идентификатор вызова в ответе модели
 * @param name    имя инструмента
 * @param summary что делается, по-русски: «Читает модуль ОбщийМодуль.Х.Модуль»
 * @param outcome итог коротко: «строки 1–120 из 120»; при ошибке — её текст
 * @param at      сколько символов текста ответа было написано до вызова: там строка вызова и стоит в ленте;
 *                -1 — не известно (старые диалоги), строка над текстом
 * @param file    файл, который вызов приложил к ответу; сессия переносит его в сообщение, в историю не пишется
 * @param usage   токены шага модели, на котором она решила вызвать инструмент (у первого вызова шага);
 *                {@code null} — не известно или не первый вызов шага
 * @param problem что за ошибка у {@link Status#FAILED}; {@code null} — не известно (старые диалоги),
 *                тогда в ленте «ошибка: текст»
 */
public record ToolUse(String id, String name, String summary, String outcome, Status status, int at, Attachment file,
        TokenUsage usage, Problem problem) {

    public enum Status {
        /** Ждёт, пока пользователь разрешит или отклонит вызов. */
        AWAITING, RUNNING, DONE, FAILED
    }

    /** Почему вызов не удался: коротко в строке ленты, подробности — в {@link #outcome}. */
    public enum Problem {
        NO_TOOL("нет такого инструмента"),
        BAD_ARGUMENTS("неверные аргументы"),
        REPEATED("повтор, не выполнен"),
        DENIED("отклонено"),
        SERVER("сервер недоступен"),
        TIMEOUT("нет ответа"),
        FAILED("ошибка инструмента"),
        INTERRUPTED("прервано");

        private final String label;

        Problem(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Сколько текста ошибки в строке ленты; целиком — в подсказке. */
    public static final int SHORT_OUTCOME = 120;

    public ToolUse {
        id = Objects.requireNonNullElse(id, "");
        Objects.requireNonNull(name, "name");
        summary = Objects.requireNonNullElse(summary, name);
        outcome = Objects.requireNonNullElse(outcome, "");
        status = Objects.requireNonNullElse(status, Status.DONE);
    }

    public ToolUse(String id, String name, String summary, String outcome, Status status, int at, Attachment file,
            TokenUsage usage) {
        this(id, name, summary, outcome, status, at, file, usage, null);
    }

    public ToolUse(String id, String name, String summary, String outcome, Status status, int at, Attachment file) {
        this(id, name, summary, outcome, status, at, file, null);
    }

    /** Неудачный вызов: {@code detail} — подробности, может быть пустым. */
    public static ToolUse failed(String id, String name, String summary, Problem problem, String detail) {
        return new ToolUse(id, name, summary, detail, Status.FAILED, -1, null, null, problem);
    }

    public ToolUse(String id, String name, String summary, String outcome, Status status, int at) {
        this(id, name, summary, outcome, status, at, null);
    }

    public ToolUse(String id, String name, String summary, String outcome, Status status) {
        this(id, name, summary, outcome, status, -1);
    }

    public ToolUse withAt(int newAt) {
        return new ToolUse(id, name, summary, outcome, status, newAt, file, usage, problem);
    }

    public ToolUse withFile(Attachment newFile) {
        return new ToolUse(id, name, summary, outcome, status, at, newFile, usage, problem);
    }

    public ToolUse withUsage(TokenUsage newUsage) {
        return new ToolUse(id, name, summary, outcome, status, at, file, newUsage, problem);
    }

    /** Тот же вызов неудачным: токены и место в ленте сохраняются. */
    public ToolUse withProblem(Problem newProblem, String detail) {
        return new ToolUse(id, name, summary, detail, Status.FAILED, at, file, usage, newProblem);
    }

    /** Служебные строки ленты (сервер не подключился, окно модели), а не вызовы инструментов. */
    private static final Set<String> NOTICES = Set.of("tools", "mcp", "context");

    /** Вызов инструмента, а не служебная строка. */
    public boolean isCall() {
        return !NOTICES.contains(name);
    }

    /** Строка для ленты: «Читает модуль Х — строки 1–120 из 120 · ↑12k ↓310». */
    public String label() {
        return text() + (usage == null ? "" : " · " + usage.describeStep());
    }

    private String text() {
        return switch (status) {
            case AWAITING -> summary + " — ждёт разрешения";
            case RUNNING -> summary + "…";
            case DONE -> outcome.isEmpty() ? summary : summary + " — " + outcome;
            case FAILED -> problem == null ? summary + " — ошибка: " + outcome
                    : summary + " — " + problem.label() + (outcome.isBlank() ? "" : ": " + shortOutcome());
        };
    }

    /** Первая строка ошибки, не длиннее {@link #SHORT_OUTCOME}. */
    private String shortOutcome() {
        String line = outcome.strip().lines().findFirst().orElse("");
        return line.length() <= SHORT_OUTCOME ? line : line.substring(0, SHORT_OUTCOME - 1).stripTrailing() + "…";
    }

    /** Подробности ошибки для подсказки, если в строку ленты влезло не всё; иначе {@code null}. */
    public String details() {
        if (status != Status.FAILED || outcome.isBlank()) {
            return null;
        }
        String shown = problem == null ? outcome : shortOutcome();
        return shown.equals(outcome.strip()) ? null : outcome.strip();
    }
}
