package io.github.hansglorias.edtai.core.tools;

import io.github.hansglorias.edtai.core.chat.ToolUse;

/** Инструмент не смог выполнить вызов. Сообщение уходит модели и показывается в ленте. */
public class ToolException extends Exception {

    private static final long serialVersionUID = 1L;

    private final ToolUse.Problem problem;

    public ToolException(String message) {
        this(ToolUse.Problem.FAILED, message);
    }

    /** @param problem что случилось: сервер недоступен, нет ответа… — для подписи в ленте */
    public ToolException(ToolUse.Problem problem, String message) {
        super(message);
        this.problem = problem;
    }

    public ToolUse.Problem problem() {
        return problem;
    }
}
