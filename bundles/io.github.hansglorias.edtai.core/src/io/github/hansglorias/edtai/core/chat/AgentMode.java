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

    /** Инструкция к каждому вопросу в режиме «План». */
    static final String PLAN_INSTRUCTION = "Составь план реализации: цель, допущения, шаги по порядку (какие объекты, модули"
            + " и процедуры менять), риски, как проверить результат. Изучать проект инструментами можно, но план не выполняй"
            + " и файлы не меняй.";

    /** Что приложить к вопросу в этом режиме: у «Плана» — его инструкция (в ленте «режим «План»»), иначе {@code null}. */
    public Attachment attachment() {
        return this == PLAN ? Attachment.mode(label, PLAN_INSTRUCTION) : null;
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
