package io.github.hansglorias.edtai.core.tools;

import java.util.concurrent.CompletableFuture;

import io.github.hansglorias.edtai.core.chat.ToolUse;

/** Спрашивает пользователя, можно ли вызвать инструмент. */
@FunctionalInterface
public interface ToolApprover {

    enum Decision {
        ALLOW,
        /** Разрешить этот инструмент до конца диалога. */
        ALLOW_IN_CHAT,
        DENY
    }

    /** Разрешает всё: для тестов и инструментов без подтверждения. */
    ToolApprover ALLOW_ALL = (use, arguments) -> CompletableFuture.completedFuture(Decision.ALLOW);

    /**
     * @param use       вызов, о котором спрашиваем: имя и описание для показа
     * @param arguments аргументы вызова, JSON текстом
     * @return решение; отмена future — как отказ
     */
    CompletableFuture<Decision> approve(ToolUse use, String arguments);
}
