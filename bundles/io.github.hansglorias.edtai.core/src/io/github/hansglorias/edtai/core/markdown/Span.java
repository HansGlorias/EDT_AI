package io.github.hansglorias.edtai.core.markdown;

/**
 * Оформление отрезка {@code [start, start + length)} текста.
 *
 * @param data адрес ссылки, уровень заголовка, язык или действие — в зависимости от вида; иначе пустая строка
 */
public record Span(int start, int length, Kind kind, String data) {

    public int end() {
        return start + length;
    }
}
