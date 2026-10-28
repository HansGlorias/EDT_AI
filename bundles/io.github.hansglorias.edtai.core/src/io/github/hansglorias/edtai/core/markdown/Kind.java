package io.github.hansglorias.edtai.core.markdown;

/** Вид оформления отрезка текста. */
public enum Kind {
    BOLD,
    ITALIC,
    STRIKE,
    /** Код внутри строки. */
    CODE,
    /** Заголовок; уровень — в {@link Span#data()}. */
    HEADING,
    /** Ссылка; адрес — в {@link Span#data()}. */
    LINK,
    /** Код блока. */
    CODE_BLOCK,
    /** Строка над блоком кода: язык и действия. */
    CODE_HEADER,
    /** Действие над блоком кода; {@link Span#data()} — {@code действие:номер блока}. */
    ACTION,
    QUOTE,
    /** Таблица: показывается моноширинным шрифтом. */
    TABLE,
    /** Маркер списка, разделитель — второстепенный текст. */
    MARKER
}
