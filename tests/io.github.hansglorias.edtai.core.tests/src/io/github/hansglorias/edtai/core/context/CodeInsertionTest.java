package io.github.hansglorias.edtai.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CodeInsertionTest {

    @Test
    void indentsFollowingLinesAndUsesDocumentDelimiter() {
        assertEquals("Если А Тогда\r\n\t\tБ();\r\n\tКонецЕсли;",
                CodeInsertion.adapt("Если А Тогда\n\tБ();\nКонецЕсли;", "\t", "\r\n"));
    }

    @Test
    void indentIsLeadingWhitespaceBeforeCursor() {
        assertEquals("  \t", CodeInsertion.indent("  \tА = "));
        assertEquals("", CodeInsertion.indent("А = 1;"));
        assertEquals("Б();\n  В();", CodeInsertion.adapt("Б();\r\nВ();", "  А = ", "\n"));
    }

    @Test
    void emptyLinesStayEmptyAndColumnZeroKeepsCode() {
        assertEquals("А();\n\n\tБ();", CodeInsertion.adapt("А();\n\nБ();", "\t", "\n"));
        assertEquals("А();\nБ();", CodeInsertion.adapt("А();\rБ();", "", "\n"));
        assertEquals("одна строка", CodeInsertion.adapt("одна строка", "\t\t", "\r\n"));
    }
}
