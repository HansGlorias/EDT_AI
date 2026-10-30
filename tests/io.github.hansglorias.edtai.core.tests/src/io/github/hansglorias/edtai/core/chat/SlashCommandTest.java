package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SlashCommandTest {

    @Test
    void parsesKnownCommandsWithArgument() {
        assertEquals(Optional.of(new SlashCommand.Invocation(SlashCommand.REVIEW, "только модуль Общий")),
                SlashCommand.parse("  /Review   только модуль Общий\n"));
        assertEquals(Optional.of(new SlashCommand.Invocation(SlashCommand.NEW, "")), SlashCommand.parse("/new"));
        assertEquals(Optional.empty(), SlashCommand.parse("/home/user/файл"));
        assertEquals(Optional.empty(), SlashCommand.parse("что такое /new?"));
    }

    @Test
    void matchesByPrefix() {
        assertEquals(List.of(SlashCommand.ANSWER, SlashCommand.ATTACH, SlashCommand.AUTO), SlashCommand.matching("a"));
        assertEquals(List.of(SlashCommand.values()), SlashCommand.matching(""));
        assertEquals(List.of(), SlashCommand.matching("x"));
        assertEquals(List.of(SlashCommand.READONLY), SlashCommand.matching("READ"));
    }

    @Test
    void completionAtCaret() {
        assertEquals(Optional.of(new InputCompletion(InputCompletion.Kind.MODULE, 7, "Спр.Ном")),
                InputCompletion.at("смотри @Спр.Ном", 15));
        assertEquals(Optional.of(new InputCompletion(InputCompletion.Kind.MODULE, 0, "")), InputCompletion.at("@", 1));
        assertEquals(Optional.empty(), InputCompletion.at("почта a@b", 9));
        assertEquals(Optional.empty(), InputCompletion.at("смотри @Спр и", 13));
        assertEquals(Optional.of(new InputCompletion(InputCompletion.Kind.COMMAND, 0, "rev")), InputCompletion.at("/rev", 4));
        assertEquals(Optional.empty(), InputCompletion.at("/review ", 8));
        assertEquals(Optional.empty(), InputCompletion.at("/home/us", 8));
        assertEquals(Optional.empty(), InputCompletion.at("текст /rev", 10));
    }

    @Test
    void completionReplacesTypedPart() {
        InputCompletion completion = InputCompletion.at("смотри @Спр и", 11).orElseThrow();
        assertEquals(new InputCompletion.Applied("смотри @Справочник.Номенклатура и", 32),
                completion.apply("смотри @Спр и", 11, "@Справочник.Номенклатура"));
        InputCompletion command = InputCompletion.at("/re", 3).orElseThrow();
        assertEquals(new InputCompletion.Applied("/review ", 8), command.apply("/re", 3, "/review"));
    }

    @Test
    void tokensAndActivityForStatusLine() {
        assertEquals("950", TokenUsage.format(950));
        assertEquals("1,2k", TokenUsage.format(1234));
        assertEquals("45k", TokenUsage.format(45_499));
        assertEquals("1,5M", TokenUsage.format(1_500_000));
        TokenUsage usage = TokenUsage.step(100_000, 900, false).plus(TokenUsage.step(44_000, 1200, false));
        assertEquals(new TokenUsage(144_000, 2100, 45_200, false), usage);
        assertEquals("контекст 45k из 200k · ответ ↑144k ↓2,1k", usage.describe(200_000));
        assertEquals("контекст ≈45k · ответ ↑≈188k ↓≈3,3k", usage.plus(TokenUsage.step(44_000, 1200, true)).describe(0));
        assertEquals("контекст ≈3,0k из 32k", TokenUsage.describeEstimate(3000, 32_768));

        Instant start = Instant.parse("2026-10-28T10:00:00Z");
        ReplyActivity activity = new ReplyActivity(ReplyActivity.Phase.THINKING, "", start);
        assertEquals("думает… 5 с", activity.describe(start.plusSeconds(5)));
        assertEquals("пишет ответ… 1 мин 5 с", activity.next(ReplyActivity.Phase.WRITING, "").describe(start.plusSeconds(65)));
        assertEquals("Читает модуль Общий… 0 с", activity.next(ReplyActivity.Phase.TOOL, "Читает модуль Общий").describe(start));
    }
}
