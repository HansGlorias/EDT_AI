package io.github.hansglorias.edtai.core.llm;

import java.util.regex.Pattern;

/**
 * Пишет ли модель, что уже внесла правки: «добавил», «изменения внесены», «что сделал». Небольшие модели
 * сообщают о правках, не вызвав инструмент изменения, — пользователь видит «готово» и пустой diff.
 * Отрицание («не менял», «не добавил») правкой не считается.
 */
final class ChangeClaims {

    private static final Pattern CLAIM = Pattern.compile("(?<!не )(?<!\\p{L})(внес(?:ла|ли)?\\b|внёс|внесен[аоы]?\\b|внесён"
            + "|добавил[аи]?\\b|добавлен[аоы]?\\b|изменил[аи]?\\b|исправил[аи]?\\b|поправил[аи]?\\b|заменил[аи]?\\b"
            + "|удалил[аи]?\\b|переписал[аи]?\\b|вставил[аи]?\\b|что сделал)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private ChangeClaims() {
    }

    static boolean claims(String text) {
        return text != null && CLAIM.matcher(text).find();
    }
}
