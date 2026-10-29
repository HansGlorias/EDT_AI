package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.documents.DocxWriter;

class AttachmentTest {

    @Test
    void imagesByExtensionTextOtherwise() {
        byte[] png = { (byte) 0x89, 'P', 'N', 'G', 0, 1, 2 };
        Attachment image = Attachment.file("Форма.PNG", png);
        assertTrue(image.isImage());
        assertEquals("image/png", image.mediaType());
        assertEquals(Base64.getEncoder().encodeToString(png), image.data());
        assertEquals("image/jpeg", Attachment.file("a.jpg", png).mediaType());
        assertEquals("[Приложено изображение: Форма.PNG]", image.promptText());

        byte[] utf8 = ("﻿Ошибка\r\nстрока 2").getBytes(StandardCharsets.UTF_8);
        Attachment log = Attachment.file("log.txt", utf8);
        assertFalse(log.isImage());
        assertEquals("Ошибка\nстрока 2", log.data(), "без BOM, переводы строк — \\n");
        assertEquals("log.txt, 2 строки", log.label());

        assertEquals("Выгрузка", Attachment.file("old.xml", "Выгрузка".getBytes(Charset.forName("windows-1251"))).data(),
                "старые файлы 1С — в Windows-1251");
        IllegalArgumentException binary = assertThrows(IllegalArgumentException.class,
                () -> Attachment.file("base.1cd", new byte[] { 1, 0, 2, 0 }));
        assertEquals("«base.1cd» — не текст: приложить можно картинки, текстовые файлы, документы Word, Excel, PowerPoint, "
                + "LibreOffice и PDF", binary.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Attachment.png("big.png", new byte[Attachment.MAX_IMAGE_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> Attachment.file("big.txt", "а".repeat(Attachment.MAX_TEXT_CHARS + 1)
                .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void documentsGoAsTheirText() {
        Attachment document = Attachment.file("Отчёт.docx", DocxWriter.fromMarkdown("# Итоги\n\nВсё хорошо."));

        assertEquals(Attachment.DOCUMENT, document.mediaType());
        assertTrue(document.isText());
        assertEquals("# Итоги\n\nВсё хорошо.", document.data());
        assertEquals("Отчёт.docx, 3 строки", document.label());
        assertEquals("Файл Отчёт.docx (текст документа):\n```\n# Итоги\n\nВсё хорошо.\n```", document.promptText());
        IllegalArgumentException old = assertThrows(IllegalArgumentException.class, () -> Attachment.file("Остатки.xls", new byte[] { 1 }));
        assertEquals("«Остатки.xls» — старый формат Office: сохраните как .xlsx и приложите снова", old.getMessage());
    }

    @Test
    void textGoesToPromptInFence() {
        Attachment file = Attachment.file("readme.md", "Пример:\n```bsl\nА = 1;\n```\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("Файл readme.md:\n````\nПример:\n```bsl\nА = 1;\n```\n````", file.promptText());
        assertEquals("readme.md, 4 строки", file.label());
        assertEquals("one.txt, 1 строка", Attachment.file("one.txt", "x".getBytes(StandardCharsets.UTF_8)).label());
        assertEquals("shot.png, 1 КБ", Attachment.png("shot.png", new byte[300]).label());

        ChatMessage question = new ChatMessage(ChatRole.USER, "Что не так?", java.time.Instant.EPOCH, ChatMessage.Status.DONE,
                List.of(), List.of(), List.of(Attachment.png("shot.png", new byte[3]), file));
        assertEquals(List.of("shot.png"), question.images().stream().map(Attachment::name).toList());
        assertTrue(question.modelText().startsWith("[Приложено изображение: shot.png]\n\nФайл readme.md:"), question.modelText());
        assertTrue(question.modelText().endsWith("````\n\nЧто не так?"), question.modelText());
    }

    @Test
    void binaryFilesFromModel() {
        byte[] bytes = new byte[3000];
        bytes[0] = 'P';
        Attachment file = Attachment.binary("Отчёт.docx", "application/octet-stream", bytes);
        assertFalse(file.isText() || file.isImage());
        assertArrayEquals(bytes, file.bytes());
        assertEquals("Отчёт.docx, 2 КБ", file.label());
        assertEquals("[Приложен файл: Отчёт.docx]", file.promptText());
        assertEquals("850 байт", Attachment.size(850));
        assertEquals("1,5 МБ", Attachment.size(1_572_864));
        assertThrows(IllegalArgumentException.class, () -> Attachment.binary("big", "application/zip", new byte[Attachment.MAX_FILE_BYTES + 1]));
        assertArrayEquals("Привет".getBytes(StandardCharsets.UTF_8), new Attachment("a.txt", "text/plain", "Привет").bytes());
    }
}
