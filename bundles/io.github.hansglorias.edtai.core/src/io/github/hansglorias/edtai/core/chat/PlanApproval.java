package io.github.hansglorias.edtai.core.chat;

import java.util.List;
import java.util.Optional;

/** Явно утверждать можно только завершённый ответ на последний запрос в режиме План. */
public final class PlanApproval {
    public static final String EXECUTE = "План утверждён. Выполни согласованный выше план. Учитывай ответы на уточнения."
            + " После правки перечитай изменённый код и проверь diff. Не расширяй задачу.";

    /** Пользователь исправил план в файле: выполнять его редакцию, а не предложенную моделью. */
    public static final String EXECUTE_EDITED = "План утверждён с правками пользователя. Выполни именно утверждённую"
            + " редакцию ниже — она заменяет план, предложенный выше. Учитывай ответы на уточнения."
            + " После правки перечитай изменённый код и проверь diff. Не расширяй задачу.";

    private PlanApproval() { }

    /**
     * Поручение выполнить план.
     *
     * @param decisions ответы на уточнения по строке; пусто — не было
     * @param edited    исправленный пользователем план; {@code null} или пусто — выполнять предложенный
     */
    public static String execute(String decisions, String edited) {
        boolean own = edited != null && !edited.isBlank();
        return (own ? EXECUTE_EDITED : EXECUTE)
                + (decisions == null || decisions.isBlank() ? "" : "\nОтветы на уточнения:\n" + decisions)
                + (own ? "\n\nУтверждённый план:\n" + edited.strip() : "");
    }

    public static Optional<ChatMessage> candidate(List<ChatMessage> messages) {
        if (messages.size() < 2) return Optional.empty();
        ChatMessage answer = messages.get(messages.size() - 1);
        ChatMessage question = messages.get(messages.size() - 2);
        boolean plan = question.role() == ChatRole.USER && question.attachments().stream().anyMatch(attachment ->
                attachment.mediaType().equals(Attachment.MODE) && attachment.name().equals(AgentMode.PLAN.label()));
        return plan && answer.role() == ChatRole.ASSISTANT && answer.status() == ChatMessage.Status.DONE
                && !answer.text().isBlank() ? Optional.of(answer) : Optional.empty();
    }
}
