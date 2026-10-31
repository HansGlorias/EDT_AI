package io.github.hansglorias.edtai.core.chat;

/**
 * Режим работы модели в чате — как в агентах (Claude Code, Cursor): что ей
 * можно делать с проектом и спрашивать ли перед инструментами.
 */
public enum AgentMode {

    AGENT("Агент", "Правит модули и вызывает инструменты; перед инструментами MCP спрашивает"),
    AUTO("Авто", "Правит модули и вызывает инструменты MCP без вопроса"),
    PLAN("План", "Изучает проект и составляет план, ничего не меняет"),
    ASK("Вопросы", "Только отвечает: проект не меняет, правки предлагает кодом в ответе");

    private final String label;
    private final String description;

    AgentMode(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** Инструменты MCP — без вопроса. */
    public boolean autoApprove() {
        return this == AUTO;
    }

    /** Инструменты правки модели не передаются. */
    public boolean readOnly() {
        return this == PLAN || this == ASK;
    }

    /**
     * Режим из настроек; если его там ещё нет — из прежних флажков
     * «Авторежим» и «Только вопросы».
     */
    public static AgentMode parse(String stored, boolean autoApprove, boolean readOnly) {
        for (AgentMode mode : values()) {
            if (mode.name().equals(stored)) {
                return mode;
            }
        }
        return readOnly ? ASK : autoApprove ? AUTO : AGENT;
    }
}
