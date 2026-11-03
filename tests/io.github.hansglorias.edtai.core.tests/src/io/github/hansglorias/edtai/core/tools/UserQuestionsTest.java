package io.github.hansglorias.edtai.core.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.tools.UserQuestions.Answer;

class UserQuestionsTest {
    private static Map<String, Object> question() {
        return Json.object("id", "empty", "text", "Что возвращать?", "options", List.of(
                Json.object("id", "error", "text", "Исключение"), Json.object("id", "zero", "text", "Ноль")));
    }

    @Test
    void validatesTheEntireFormBeforeShowingAnything() {
        AtomicInteger shown = new AtomicInteger();
        Tool tool = UserQuestions.tool(request -> { shown.incrementAndGet(); return new CompletableFuture<>(); });
        List<Map<String, Object>> invalid = List.of(Map.of(), Json.object("questions", null),
                Json.object("questions", List.of()), Json.object("questions", List.of(question(), question())),
                Json.object("questions", List.of(question(), "not object")),
                Json.object("questions", List.of(Json.object("id", "x", "text", "?", "options", List.of("x")))),
                Json.object("questions", List.of(Json.object("id", "x", "text", "?", "options", null))),
                Json.object("questions", List.of(Json.object("id", "x", "text", true))),
                Json.object("questions", List.of(Json.object("id", "../bad", "text", "?"))),
                Json.object("questions", List.of(Json.object("id", "x", "text", " "))),
                Json.object("questions", List.of(Json.object("id", "x", "text", "?", "unknown", true))),
                Json.object("questions", List.of(question()), "approve", true));
        for (var input : invalid) {
            ToolException failure = assertThrows(ToolException.class, () -> tool.call(input), input.toString());
            assertEquals(ToolUse.Problem.BAD_ARGUMENTS, failure.problem());
        }
        assertEquals(0, shown.get());
    }

    @Test
    void acceptsThreeIndependentQuestionsAndFreeTextWithoutOptions() throws Exception {
        var request = UserQuestions.parse(Json.object("questions", List.of(question(),
                Json.object("id", "scope", "text", "Где?"), Json.object("id", "test", "text", "Как проверить?"))));
        String result = UserQuestions.result(request, List.of(new Answer("empty", "zero", "Ноль"),
                new Answer("scope", "", "Только здесь"), new Answer("test", "", "Вручную")));
        assertEquals("zero", Json.stringAt(Json.parse(result), "answers", 0, "option_id"));
        assertEquals("Только здесь", Json.stringAt(Json.parse(result), "answers", 1, "text"));
        assertThrows(ToolException.class, () -> UserQuestions.result(request, List.of()));
    }

    @Test
    void rejectsForeignIdsAndOptionTextButAllowsOwnAnswer() throws Exception {
        var request = UserQuestions.parse(Json.object("questions", List.of(question())));
        for (Answer answer : List.of(new Answer("other", "", "текст"), new Answer("empty", "bad", "Ноль"),
                new Answer("empty", "zero", "не тот вариант"), new Answer("empty", "", " "))) {
            assertThrows(ToolException.class, () -> UserQuestions.result(request, List.of(answer)));
        }
        assertTrue(UserQuestions.result(request, List.of(new Answer("empty", "", "Свой текст"))).contains("Свой текст"));
    }
}
