package io.github.hansglorias.edtai.core.llm;

/**
 * Событие Server-Sent Events.
 *
 * @param event имя события из поля {@code event:}, пустая строка — не задано
 * @param data  данные: строки {@code data:} через перевод строки
 */
record SseEvent(String event, String data) {
}
