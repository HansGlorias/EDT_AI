package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanApprovalTest {
    @Test
    void onlyCompletedPlanCanBeApproved() {
        ChatMessage question = new ChatMessage(ChatRole.USER, "задача", java.time.Instant.now(), ChatMessage.Status.DONE,
                List.of(), List.of(), List.of(AgentMode.PLAN.attachment()));
        ChatMessage answer = ChatMessage.of(ChatRole.ASSISTANT, "план");
        assertEquals(answer, PlanApproval.candidate(List.of(question, answer)).orElseThrow());
        assertTrue(PlanApproval.candidate(List.of(question, answer.with("план", ChatMessage.Status.STOPPED))).isEmpty());
        assertTrue(PlanApproval.candidate(List.of(question, answer.with("план", ChatMessage.Status.STREAMING))).isEmpty());
        assertTrue(PlanApproval.candidate(List.of(question, answer.with("", ChatMessage.Status.DONE))).isEmpty());
        assertTrue(PlanApproval.candidate(List.of(ChatMessage.of(ChatRole.USER, "обычный вопрос"), answer)).isEmpty());
        assertTrue(PlanApproval.candidate(List.of(question, answer, ChatMessage.of(ChatRole.ERROR, "ошибка"))).isEmpty());
    }
}
