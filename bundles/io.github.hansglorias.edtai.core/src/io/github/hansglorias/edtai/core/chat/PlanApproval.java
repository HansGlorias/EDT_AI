package io.github.hansglorias.edtai.core.chat;

import java.util.List;
import java.util.Optional;

/** Явно утверждать можно только завершённый ответ на последний запрос в режиме План. */
public final class PlanApproval {
    public static final String EXECUTE = "План утверждён. Выполни согласованный выше план. Учитывай ответы на уточнения."
            + " После правки перечитай изменённый код и проверь diff. Не расширяй задачу.";

    private PlanApproval() { }

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
