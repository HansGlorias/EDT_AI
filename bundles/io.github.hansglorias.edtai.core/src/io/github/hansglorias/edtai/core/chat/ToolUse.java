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
 */
public record ToolUse(String id, String name, String summary, String outcome, Status status, int at, Attachment file) {

    public enum Status {
        /** Ждёт, пока пользователь разрешит или отклонит вызов. */
        AWAITING, RUNNING, DONE, FAILED
    }

    public ToolUse {
        id = Objects.requireNonNullElse(id, "");
        Objects.requireNonNull(name, "name");
        summary = Objects.requireNonNullElse(summary, name);
        outcome = Objects.requireNonNullElse(outcome, "");
        status = Objects.requireNonNullElse(status, Status.DONE);
    }

    public ToolUse(String id, String name, String summary, String outcome, Status status, int at) {
        this(id, name, summary, outcome, status, at, null);
    }

    public ToolUse(String id, String name, String summary, String outcome, Status status) {
        this(id, name, summary, outcome, status, -1);
    }

    public ToolUse withAt(int newAt) {
        return new ToolUse(id, name, summary, outcome, status, newAt, file);
    }

    public ToolUse withFile(Attachment newFile) {
        return new ToolUse(id, name, summary, outcome, status, at, newFile);
    }

    /** Служебные строки ленты (сервер не подключился, окно модели), а не вызовы инструментов. */
    private static final Set<String> NOTICES = Set.of("tools", "mcp", "context");

    /** Вызов инструмента, а не служебная строка. */
    public boolean isCall() {
        return !NOTICES.contains(name);
    }

    /** Строка для ленты: «Читает модуль Х — строки 1–120 из 120». */
    public String label() {
        return switch (status) {
            case AWAITING -> summary + " — ждёт разрешения";
            case RUNNING -> summary + "…";
            case DONE -> outcome.isEmpty() ? summary : summary + " — " + outcome;
            case FAILED -> summary + " — ошибка: " + outcome;
        };
    }
}
