package io.github.hansglorias.edtai.core.chat;

import java.util.Objects;

/** Безопасная идентичность подключения, зафиксированного для ответа; без адреса и секретов. */
public record ReplyModel(String protocol, String model) {
    public ReplyModel {
        Objects.requireNonNull(protocol);
        Objects.requireNonNull(model);
    }
}
