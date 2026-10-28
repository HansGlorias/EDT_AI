package io.github.hansglorias.edtai.core.llm;

/**
 * Ошибка обращения к модели. Сообщение — для пользователя, по-русски, без
 * токена.
 */
public class ModelException extends Exception {

    private static final long serialVersionUID = 1L;

    private final boolean rejectsTools;
    private boolean contextOverflow;

    public ModelException(String message) {
        this(message, null, false);
    }

    public ModelException(String message, Throwable cause) {
        this(message, cause, false);
    }

    /** @param rejectsTools сервер отклонил запрос из-за инструментов: он их не поддерживает */
    public ModelException(String message, Throwable cause, boolean rejectsTools) {
        super(message, cause);
        this.rejectsTools = rejectsTools;
    }

    /** Сервер отклонил запрос из-за инструментов: повторить без них. */
    /** Сервер ответил, что запрос не помещается в окно модели. */
    public boolean contextOverflow() {
        return contextOverflow;
    }

    static ModelException overflow(String message) {
        ModelException error = new ModelException(message, null, false);
        error.contextOverflow = true;
        return error;
    }

    public boolean rejectsTools() {
        return rejectsTools;
    }
}
