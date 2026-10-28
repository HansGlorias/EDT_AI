package io.github.hansglorias.edtai.core.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MarkdownTest {

    @Test
    void plainTextKeepsLineBreaksAndCollapsesBlankLines() {
        RichText text = Markdown.render("первая\nвторая\n\n\n\nтретья\n\n");

        assertEquals("первая\nвторая\n\nтретья", text.text());
        assertTrue(text.spans().isEmpty());
    }

    @Test
    void emphasis() {
        RichText text = Markdown.render("**жирный**, *курсив*, _тоже_, ***оба***, ~~нет~~ и __жирный__");

        assertEquals("жирный, курсив, тоже, оба, нет и жирный", text.text());
        assertEquals(List.of("жирный", "оба", "жирный"), styled(text, Kind.BOLD));
        assertEquals(List.of("курсив", "тоже", "оба"), styled(text, Kind.ITALIC));
        assertEquals(List.of("нет"), styled(text, Kind.STRIKE));
    }

    @Test
    void underscoreInsideWordsIsNotItalic() {
        RichText text = Markdown.render("Переменная имя_модуля_сервер и snake_case_name, но _курсив_.");

        assertEquals("Переменная имя_модуля_сервер и snake_case_name, но курсив.", text.text());
        assertEquals(List.of("курсив"), styled(text, Kind.ITALIC));
    }

    @Test
    void unclosedOrSpacedMarkupStaysLiteral() {
        assertEquals("2 * 3 * 4 и **начало", Markdown.render("2 * 3 * 4 и **начало").text());
        assertEquals("a ** b", Markdown.render("a ** b").text());
        assertEquals("`не закрыт", Markdown.render("`не закрыт").text());
        assertTrue(Markdown.render("2 * 3 * 4 и **начало").spans().isEmpty());
    }

    @Test
    void nestedEmphasis() {
        RichText text = Markdown.render("**жирный и *курсив* внутри**");

        assertEquals("жирный и курсив внутри", text.text());
        assertEquals(List.of("жирный и курсив внутри"), styled(text, Kind.BOLD));
        assertEquals(List.of("курсив"), styled(text, Kind.ITALIC));
    }

    @Test
    void inlineCodeIsNotParsedInside() {
        RichText text = Markdown.render("Вызов `Найти(*Строка*, \"_\")` и ``код с ` внутри``");

        assertEquals("Вызов Найти(*Строка*, \"_\") и код с ` внутри", text.text());
        assertEquals(List.of("Найти(*Строка*, \"_\")", "код с ` внутри"), styled(text, Kind.CODE));
        assertEquals(List.of(), styled(text, Kind.ITALIC));
    }

    @Test
    void escapes() {
        assertEquals("*не курсив* и \\n", Markdown.render("\\*не курсив\\* и \\n").text());
    }

    @Test
    void links() {
        RichText text = Markdown.render("См. [документацию](https://its.example.invalid/db \"ИТС\"), <https://a.example.invalid/x>"
                + " и https://b.example.invalid/path?q=1. Ещё (https://c.example.invalid/a_(b)) и [](https://d.example.invalid)");

        assertEquals("См. документацию, https://a.example.invalid/x и https://b.example.invalid/path?q=1. Ещё"
                + " (https://c.example.invalid/a_(b)) и https://d.example.invalid", text.text());
        assertEquals(List.of("https://its.example.invalid/db", "https://a.example.invalid/x", "https://b.example.invalid/path?q=1",
                "https://c.example.invalid/a_(b)", "https://d.example.invalid"),
                text.spans().stream().filter(span -> span.kind() == Kind.LINK).map(Span::data).toList());
        assertEquals("документацию", styled(text, Kind.LINK).get(0));
    }

    @Test
    void notEveryHttpIsLink() {
        RichText text = Markdown.render("протокол http:// и слово xhttps://example.invalid");

        assertEquals(List.of(), styled(text, Kind.LINK));
    }

    @Test
    void headings() {
        RichText text = Markdown.render("# Первый\n## Второй **жирный** ##\n#### Четвёртый\n#без пробела");

        assertEquals("Первый\nВторой жирный\nЧетвёртый\n#без пробела", text.text());
        assertEquals(List.of("Первый:1", "Второй жирный:2", "Четвёртый:4"), text.spans().stream()
                .filter(span -> span.kind() == Kind.HEADING)
                .map(span -> text.text().substring(span.start(), span.end()) + ":" + span.data()).toList());
        assertEquals(List.of("жирный"), styled(text, Kind.BOLD));
    }

    @Test
    void lists() {
        RichText text = Markdown.render("- раз\n  * вложенный\n    + ещё глубже\n1. первый\n2) второй\n- [ ] задача\n- [x] сделано");

        assertEquals("• раз\n  ◦ вложенный\n    • ещё глубже\n1. первый\n2) второй\n• ☐ задача\n• ☑ сделано", text.text());
        assertEquals(List.of("•", "◦", "•", "1.", "2)", "• ☐", "• ☑"), styled(text, Kind.MARKER));
    }

    @Test
    void quotesAndRules() {
        RichText text = Markdown.render("> цитата *с курсивом*\n>> вложенная\n\n---\n* * *\nтекст");

        assertEquals("│ цитата с курсивом\n│ вложенная\n\n" + Markdown.RULE_TEXT + "\n" + Markdown.RULE_TEXT + "\nтекст", text.text());
        assertEquals(List.of("│ цитата с курсивом", "│ вложенная"), styled(text, Kind.QUOTE));
    }

    @Test
    void codeBlocks() {
        String markdown = "Пример:\n```bsl\nПроцедура Тест()\n    // **не жирный**\nКонецПроцедуры\n```\nи ещё\n~~~\nx = 1\n~~~";

        RichText text = Markdown.render(markdown, List.of("Копировать", "Вставить"));

        assertEquals("Пример:\nbsl   Копировать   Вставить\nПроцедура Тест()\n    // **не жирный**\nКонецПроцедуры\nи ещё\n"
                + Markdown.CODE_LABEL + "   Копировать   Вставить\nx = 1", text.text());
        assertEquals(2, text.codeBlocks().size());
        CodeBlock first = text.codeBlocks().get(0);
        assertEquals("bsl", first.language());
        assertEquals("Процедура Тест()\n    // **не жирный**\nКонецПроцедуры", first.code());
        assertEquals("bsl   Копировать   Вставить\n" + first.code(), text.text().substring(first.start(), first.end()));
        assertEquals("", text.codeBlocks().get(1).language());
        assertEquals(List.of(first.code(), "x = 1"), styled(text, Kind.CODE_BLOCK));
        assertEquals(List.of("Копировать:0", "Вставить:0", "Копировать:1", "Вставить:1"),
                text.spans().stream().filter(span -> span.kind() == Kind.ACTION).map(Span::data).toList());
        assertEquals(List.of(), styled(text, Kind.BOLD));
    }

    @Test
    void unclosedCodeBlockRunsToEnd() {
        RichText text = Markdown.render("Сейчас:\n```bsl\nА = 1;\n\nБ = 2;");

        assertEquals("Сейчас:\nbsl\nА = 1;\n\nБ = 2;", text.text());
        assertEquals("А = 1;\n\nБ = 2;", text.codeBlocks().get(0).code());
    }

    @Test
    void emptyCodeBlock() {
        RichText text = Markdown.render("```\n```");

        assertEquals(Markdown.CODE_LABEL + "\n", text.text());
        assertEquals("", text.codeBlocks().get(0).code());
    }

    @Test
    void tablesAreAligned() {
        String markdown = "| Функция | Что делает |\n|---|:---:|\n| `ТекущаяДата()` | время сервера |\n| Сейчас | **нет** |\nпосле";

        RichText text = Markdown.render(markdown);

        assertEquals("Функция       │ Что делает\n"
                + "──────────────┼──────────────\n"
                + "ТекущаяДата() │ время сервера\n"
                + "Сейчас        │ нет\n"
                + "после", text.text());
        assertEquals(1, styled(text, Kind.TABLE).size());
        assertEquals(List.of("ТекущаяДата()"), styled(text, Kind.CODE));
        assertTrue(styled(text, Kind.BOLD).contains("нет"));
        assertTrue(styled(text, Kind.BOLD).get(0).startsWith("Функция"));
    }

    @Test
    void tableWithoutOuterPipesAndEscapedPipe() {
        RichText text = Markdown.render("a | b\n--|--\nx \\| y | `c|d`");

        assertEquals("a     │ b\n──────┼────\nx | y │ c|d", text.text());
    }

    @Test
    void pipeWithoutSeparatorIsText() {
        assertEquals("a | b\nc | d", Markdown.render("a | b\nc | d").text());
    }

    @Test
    void segmentsMergeOverlappingStyles() {
        RichText text = Markdown.render("**жирный [ссылка](https://e.example.invalid)** и `код`");

        List<Segment> segments = text.segments();

        assertEquals(3, segments.size());
        assertEquals(Set.of(Kind.BOLD), segments.get(0).kinds());
        assertEquals("жирный ", text.text().substring(segments.get(0).start(), segments.get(0).start() + segments.get(0).length()));
        assertEquals(Set.of(Kind.BOLD, Kind.LINK), segments.get(1).kinds());
        assertEquals("https://e.example.invalid", segments.get(1).link());
        assertTrue(segments.get(2).monospace());
        assertEquals("код", text.text().substring(segments.get(2).start()));
    }

    @Test
    void crlfAndNull() {
        assertEquals("a\nb", Markdown.render("a\r\nb").text());
        assertEquals("", Markdown.render(null).text());
    }

    private static List<String> styled(RichText text, Kind kind) {
        return text.spans().stream()
                .filter(span -> span.kind() == kind)
                .map(span -> text.text().substring(span.start(), span.end()))
                .toList();
    }
}
