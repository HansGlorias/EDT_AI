package io.github.hansglorias.edtai.core.tools;

/** Инструмент не смог выполнить вызов. Сообщение уходит модели и показывается в ленте. */
public class ToolException extends Exception {

    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }
}
