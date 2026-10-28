package io.github.hansglorias.edtai.core.chat;

public enum ChatRole {
    USER,
    ASSISTANT,
    /** Сообщение об ошибке: в модель не передаётся, только показывается. */
    ERROR
}
