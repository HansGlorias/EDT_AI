package io.github.hansglorias.edtai.core.chat;

import java.util.Objects;

/**
 * Вызов инструмента моделью — для показа в ленте: что модель прочитала или
 * искала. Сам результат уходит модели и в историю диалога не сохраняется.
 *
 * @param id      идентификатор вызова в ответе модели
 * @param name    имя инструмента
 * @param summary что делается, по-русски: «Читает модуль ОбщийМодуль.Х.Модуль»
 * @param outcome итог коротко: «строки 1–120 из 120»; при ошибке — её текст
 */
public record ToolUse(String id, String name, String summary, String outcome, Status status) {

    public enum Status {
        RUNNING, DONE, FAILED
    }

    public ToolUse {
        id = Objects.requireNonNullElse(id, "");
        Objects.requireNonNull(name, "name");
        summary = Objects.requireNonNullElse(summary, name);
        outcome = Objects.requireNonNullElse(outcome, "");
        status = Objects.requireNonNullElse(status, Status.DONE);
    }

    /** Строка для ленты: «Читает модуль Х — строки 1–120 из 120». */
    public String label() {
        return switch (status) {
            case RUNNING -> summary + "…";
            case DONE -> outcome.isEmpty() ? summary : summary + " — " + outcome;
            case FAILED -> summary + " — ошибка: " + outcome;
        };
    }
}
