package io.github.hansglorias.edtai.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ContextPromptTest {

    private static final String PATH = "src/CommonModules/Цены/Module.bsl";

    @Test
    void questionWithoutContextIsUnchanged() {
        assertEquals("Как дела?", ContextPrompt.compose("Как дела?", List.of()));
    }

    @Test
    void fragmentsGoBeforeQuestion() {
        ContextItem lines = ContextItem.of("Демо", PATH, 10, 11, 40, "А = 1;\r\nБ = 2;");
        ContextItem whole = ContextItem.of("Демо", "notes/readme.txt", 1, 1, 1, "текст");

        assertEquals("ОбщийМодуль.Цены.Модуль (проект Демо, src/CommonModules/Цены/Module.bsl), строки 10–11:\n"
                + "```bsl\nА = 1;\nБ = 2;\n```\n\n"
                + "notes/readme.txt (проект Демо), весь модуль:\n"
                + "```txt\nтекст\n```\n\n"
                + "Что делает этот код?", ContextPrompt.compose("Что делает этот код?", List.of(lines, whole)));
    }

    @Test
    void fenceIsLongerThanBackticksInside() {
        assertEquals("```", ContextPrompt.fence("А = 1; // `код`"));
        assertEquals("````", ContextPrompt.fence("// ```bsl\n// ```"));
        assertEquals("``````", ContextPrompt.fence("`````"));
    }

    @Test
    void labelsAndDescriptions() {
        ContextItem lines = ContextItem.of("Демо", PATH, 10, 25, 40, "x");
        assertEquals("ОбщийМодуль.Цены.Модуль: 10–25", lines.label());
        assertEquals("ОбщийМодуль.Цены.Модуль: 7", ContextItem.of("Демо", PATH, 7, 7, 40, "x").label());
        assertEquals("ОбщийМодуль.Цены.Модуль", ContextItem.of("Демо", PATH, 1, 40, 40, "x").label());
        assertEquals("Демо/" + PATH + "\nстроки 10–25 из 40, 1 символ", lines.description());
        assertEquals("other/file.bsl: 2–3", ContextItem.of("Демо", "other/file.bsl", 2, 3, 5, "").label());
        assertEquals("bsl", lines.language());
        assertEquals("", ContextItem.of("Демо", "dir.d/Makefile", 1, 1, 1, "").language());
    }

    @Test
    void truncatesByWholeLines() {
        ContextItem item = ContextItem.of("Демо", PATH, 10, 13, 100, "1111\n2222\n3333\n4444");

        ContextItem cut = item.truncate(10);

        assertEquals("1111\n2222", cut.text());
        assertEquals(11, cut.lastLine());
        assertEquals(2, cut.omittedLines());
        assertEquals(13, cut.requestedLastLine());
        assertEquals("ОбщийМодуль.Цены.Модуль: 10–13 (обрезано)", cut.label());
        assertTrue(cut.description().endsWith("Не поместилось в лимит контекста: 2 строки"), cut.description());
        assertTrue(ContextPrompt.compose("?", List.of(cut))
                .contains("```\n(обрезано по лимиту контекста: показаны строки 10–11, не показаны 12–13 из 100)\n"));
        assertEquals(item, item.truncate(19));
    }

    @Test
    void fitSharesLimitInOrder() {
        ContextItem first = ContextItem.of("Демо", PATH, 1, 2, 10, "aaaa\nbbbb");
        ContextItem second = ContextItem.of("Демо", PATH, 5, 6, 10, "cccc\ndddd");
        ContextItem third = ContextItem.of("Демо", PATH, 8, 8, 10, "eeee");

        List<ContextItem> fitted = ContextPrompt.fit(List.of(first, second, third), 16);

        assertEquals(first, fitted.get(0));
        assertEquals("cccc", fitted.get(1).text());
        assertEquals(1, fitted.get(1).omittedLines());
        assertEquals("", fitted.get(2).text());
        assertEquals(7, fitted.get(2).lastLine());
        String prompt = ContextPrompt.compose("?", fitted);
        assertTrue(prompt.contains("строки 8:\n(не поместилось в лимит контекста: строки 8 из 10)\n"), prompt);
        assertFalse(prompt.contains("eeee"));
    }

    @Test
    void sameRangeIgnoresTruncation() {
        ContextItem item = ContextItem.of("Демо", PATH, 1, 2, 10, "aaaa\nbbbb");

        assertTrue(item.sameRange(item.truncate(4)));
        assertFalse(item.sameRange(ContextItem.of("Демо", PATH, 1, 3, 10, "")));
        assertFalse(item.sameRange(ContextItem.of("Другой", PATH, 1, 2, 10, "")));
    }

    @Test
    void plurals() {
        assertEquals("строка", ContextItem.plural(21, "строка", "строки", "строк"));
        assertEquals("строки", ContextItem.plural(3, "строка", "строки", "строк"));
        assertEquals("строк", ContextItem.plural(12, "строка", "строки", "строк"));
        assertEquals("строк", ContextItem.plural(0, "строка", "строки", "строк"));
    }
}
