package io.github.hansglorias.edtai.core.chat;

public enum ChatRole {
    USER,
    ASSISTANT,
    /** Сообщение об ошибке: в модель не передаётся, только показывается. */
    ERROR,
    /**
     * Краткое содержание диалога до этого места, сделанное моделью: дальше
     * модель получает его вместо сообщений выше.
     */
    SUMMARY
}
