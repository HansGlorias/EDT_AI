package io.github.hansglorias.edtai.core.settings;

/**
 * Протокол обмена с сервером модели.
 */
public enum Protocol {

    /** OpenAI-совместимый API: {@code /v1/chat/completions}. */
    OPENAI_COMPATIBLE("OpenAI-совместимый (/v1/chat/completions)"),

    /** Anthropic Messages API: {@code /v1/messages}. */
    ANTHROPIC("Anthropic Messages (/v1/messages)");

    private final String label;

    Protocol(String label) {
        this.label = label;
    }

    /** Название для показа пользователю. */
    public String label() {
        return label;
    }

    /** Разбирает сохранённое значение; неизвестное превращается в значение по умолчанию. */
    public static Protocol parse(String value) {
        if (value != null) {
            for (Protocol protocol : values()) {
                if (protocol.name().equals(value)) {
                    return protocol;
                }
            }
        }
        return AiSettings.DEFAULT_PROTOCOL;
    }
}
