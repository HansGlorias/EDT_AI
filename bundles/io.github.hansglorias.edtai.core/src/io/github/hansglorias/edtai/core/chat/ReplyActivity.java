package io.github.hansglorias.edtai.core.chat;

import java.time.Duration;
import java.time.Instant;

/**
 * Что сейчас происходит с ответом: подготовка запроса, ожидание модели,
 * текст или инструмент — для ленты и строки состояния.
 *
 * @param detail  для {@link Phase#TOOL}: что делает инструмент
 * @param started когда начался ответ
 */
public record ReplyActivity(Phase phase, String detail, Instant started) {

    public enum Phase {
        /** Подготовка истории перед обращением к провайдеру. */
        PREPARING,
        /** Провайдер готовит или выполняет запрос; видимого текста пока нет. */
        THINKING,
        /** Модель пишет текст ответа. */
        WRITING,
        /** Выполняется инструмент или ждёт подтверждения. */
        TOOL
    }

    public ReplyActivity next(Phase newPhase, String newDetail) {
        return new ReplyActivity(newPhase, newDetail, started);
    }

    /** «ожидает модель… 12 с», «пишет ответ… 3 с», «Читает модуль X… 1 мин 5 с». */
    public String describe(Instant now) {
        String what = switch (phase) {
            case PREPARING -> "готовит запрос";
            case THINKING -> "ожидает модель";
            case WRITING -> "пишет ответ";
            case TOOL -> detail == null || detail.isBlank() ? "вызывает инструмент" : detail;
        };
        long seconds = Math.max(0, Duration.between(started, now).toSeconds());
        String elapsed = seconds < 60 ? seconds + " с" : seconds / 60 + " мин " + seconds % 60 + " с";
        return what + "… " + elapsed;
    }
}
