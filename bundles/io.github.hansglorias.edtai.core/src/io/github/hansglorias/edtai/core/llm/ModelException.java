package io.github.hansglorias.edtai.core.llm;

/**
 * Ошибка обращения к модели. Сообщение — для пользователя, по-русски, без
 * токена.
 */
public class ModelException extends Exception {

    private static final long serialVersionUID = 1L;

    public ModelException(String message) {
        super(message);
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
