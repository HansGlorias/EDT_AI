package io.github.hansglorias.edtai.core.markdown;

/**
 * Блок кода в отрисованном тексте.
 *
 * @param language язык из строки открытия блока, может быть пустым
 * @param code     текст кода без обрамления
 * @param start    начало строки заголовка блока
 * @param end      конец кода (не включая)
 */
public record CodeBlock(String language, String code, int start, int end) {
}
