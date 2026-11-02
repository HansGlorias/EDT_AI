package io.github.hansglorias.edtai.core.llm;

import java.util.OptionalInt;
import io.github.hansglorias.edtai.core.settings.Protocol;

/** Отправляемый лимит ответа; отсутствие значения не ограничивает генерацию на сервере. */
final class OutputPolicy {
    private OutputPolicy() { }

    static OptionalInt maxTokens(ModelConnection connection) {
        if (connection.maxTokens() > 0) return OptionalInt.of(connection.maxTokens());
        return connection.protocol() == Protocol.ANTHROPIC
                ? OptionalInt.of(ModelConnection.ANTHROPIC_DEFAULT_MAX_TOKENS) : OptionalInt.empty();
    }
}
