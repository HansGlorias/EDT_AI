package io.github.hansglorias.edtai.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.documents.DocxWriter;

class FileToolsTest {

    private final Tool tool = FileTools.create().get(0);

    @Test
    void wordDocumentFromMarkdown() throws Exception {
        assertEquals("attach_file", tool.name());
        assertFalse(tool.modifies() || tool.needsApproval(), "проект не меняет — и в режиме «только вопросы»");
        assertEquals("Готовит файл Отчёт.docx", tool.summary(Map.of("name", "Отчёт.docx")));

        Tool.Result result = tool.call(Map.of("name", "Отчёт.docx", "content", "# Итоги\n\nВсё хорошо."));

        Attachment file = result.file();
        assertEquals("Отчёт.docx", file.name());
        assertEquals(DocxWriter.MEDIA_TYPE, file.mediaType());
        assertEquals("PK", new String(file.bytes(), 0, 2, StandardCharsets.US_ASCII));
        assertTrue(result.outcome().endsWith(" КБ") || result.outcome().endsWith(" байт"), result.outcome());
        assertTrue(result.text().startsWith("Файл «Отчёт.docx» (" + result.outcome() + ") приложен к ответу"), result.text());
    }

    @Test
    void textFilesAsIs() throws Exception {
        Attachment csv = tool.call(Map.of("name", "остатки.csv", "content", "Товар;Количество\r\nСтол;3")).file();
        assertEquals("text/csv", csv.mediaType());
        byte[] bytes = csv.bytes();
        assertEquals(0xEF, bytes[0] & 0xFF, "BOM: Excel узнаёт UTF-8");
        assertEquals("\uFEFFТовар;Количество\nСтол;3", csv.data());

        Attachment module = tool.call(Map.of("name", "Модуль.bsl", "content", "Процедура А()\nКонецПроцедуры")).file();
        assertEquals("text/plain", module.mediaType());
        assertEquals("Процедура А()\nКонецПроцедуры", new String(module.bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void badNamesAndFormats() {
        assertEquals("отчёт.txt", FileTools.fileName("C:\\Users\\x\\..\\отчёт.txt"));
        assertEquals("a_b.md", FileTools.fileName("../a?b.md"));
        assertEquals("env", FileTools.fileName(".env"));
        assertTrue(FileTools.fileName("а".repeat(300) + ".docx").endsWith("а.docx"));
        assertEquals(120, FileTools.fileName("а".repeat(300) + ".docx").length());

        ToolException pdf = assertThrows(ToolException.class, () -> tool.call(Map.of("name", "отчёт.pdf", "content", "x")));
        assertTrue(pdf.getMessage().startsWith("Формат .pdf сделать не могу"), pdf.getMessage());
        assertThrows(ToolException.class, () -> tool.call(Map.of("name", "отчёт", "content", "x")));
        assertThrows(ToolException.class, () -> tool.call(Map.of("name", "big.txt", "content", "x".repeat(Attachment.MAX_TEXT_CHARS + 1))));
    }
}
