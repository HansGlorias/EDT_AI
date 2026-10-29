package io.github.hansglorias.edtai.core.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DocumentTextTest {

    @Test
    void wordDocumentAsMarkdown() {
        byte[] docx = DocxWriter.fromMarkdown("""
                # Отчёт по остаткам

                Склад **Главный**, дата 29.10.2026.

                - первый пункт
                - второй пункт

                | Товар | Кол-во |
                |---|--:|
                | Хлеб «Бородинский» | 5 |
                | А \\| Б | 7 |

                ## Итоги
                """);

        assertEquals("""
                # Отчёт по остаткам

                Склад Главный, дата 29.10.2026.

                - первый пункт
                - второй пункт

                | Товар | Кол-во |
                | --- | --- |
                | Хлеб «Бородинский» | 5 |
                | А \\| Б | 7 |

                ## Итоги""", DocumentText.extract("Отчёт.docx", docx));
    }

    @Test
    void excelSheetsAsTabSeparatedRows() {
        byte[] xlsx = XlsxWriter.fromText("""
                ## Остатки

                | Товар | Кол-во | Поступление |
                |---|---|---|
                | Молоко | 10,5 | 15.03.2026 |
                | Хлеб | | 01.01.2026 |
                """);

        assertEquals("""
                ## Лист «Остатки»

                Товар\tКол-во\tПоступление
                Молоко\t10.5\t2026-03-15
                Хлеб\t\t2026-01-01""", DocumentText.extract("остатки.XLSX", xlsx));
    }

    @Test
    void excelDatesAndNumbers() {
        assertTrue(DocumentText.isDateFormat("dd.mm.yyyy"));
        assertTrue(DocumentText.isDateFormat("[$-419]d mmmm yyyy"));
        assertTrue(DocumentText.isDateFormat("h:mm"));
        assertFalse(DocumentText.isDateFormat("#,##0.00\" руб.\""));
        assertFalse(DocumentText.isDateFormat("0%"));
        assertEquals("2026-10-29", DocumentText.serialDate("46324"));
        assertEquals("2026-10-29 12:00", DocumentText.serialDate("46324.5"));
        assertEquals("1899-12-31", DocumentText.serialDate("1"));
    }

    @Test
    void presentationBySlides() {
        byte[] pptx = PptxWriter.fromMarkdown("""
                # Итоги квартала

                Кратко

                ## Продажи

                - рост 10%
                - новые клиенты
                """);

        assertEquals("""
                ## Слайд 1

                Итоги квартала
                Кратко

                ## Слайд 2

                Продажи
                рост 10%
                новые клиенты""", DocumentText.extract("Итоги.pptx", pptx));
    }

    @Test
    void libreOfficeTextAndSpreadsheet() {
        String office = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
                + "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" "
                + "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\"";
        byte[] odt = odf("<office:document-content " + office + "><office:body><office:text>"
                + "<text:h text:outline-level=\"2\">Заголовок</text:h>"
                + "<text:p>Первый<text:s text:c=\"2\"/>абзац<text:line-break/>с переносом</text:p>"
                + "<text:list><text:list-item><text:p>пункт</text:p></text:list-item></text:list>"
                + "<table:table><table:table-header-rows><table:table-row><table:table-cell><text:p>А</text:p></table:table-cell>"
                + "<table:table-cell><text:p>Б</text:p></table:table-cell></table:table-row></table:table-header-rows>"
                + "<table:table-row><table:table-cell><text:p>1</text:p></table:table-cell>"
                + "<table:table-cell><text:p>2</text:p></table:table-cell></table:table-row></table:table>"
                + "</office:text></office:body></office:document-content>");

        assertEquals("""
                ## Заголовок

                Первый  абзац
                с переносом

                - пункт

                | А | Б |
                | --- | --- |
                | 1 | 2 |""", DocumentText.extract("Записка.odt", odt));

        byte[] ods = odf("<office:document-content " + office + "><office:body><office:spreadsheet>"
                + "<table:table table:name=\"Склад\">"
                + "<table:table-row><table:table-cell office:value-type=\"string\"><text:p>Товар</text:p></table:table-cell>"
                + "<table:table-cell table:number-columns-repeated=\"2\"/>"
                + "<table:table-cell office:value-type=\"date\" office:date-value=\"2026-10-29\"><text:p>29.10.26</text:p></table:table-cell>"
                + "<table:table-cell table:number-columns-repeated=\"16380\"/></table:table-row>"
                + "<table:table-row table:number-rows-repeated=\"1048575\"><table:table-cell table:number-columns-repeated=\"16384\"/>"
                + "</table:table-row></table:table></office:spreadsheet></office:body></office:document-content>");

        assertEquals("## Лист «Склад»\n\nТовар\t\t\t2026-10-29", DocumentText.extract("склад.ods", ods));
    }

    @Test
    void problemsAreExplained() {
        IllegalArgumentException old = assertThrows(IllegalArgumentException.class, () -> DocumentText.extract("Отчёт.doc", new byte[1]));
        assertEquals("«Отчёт.doc» — старый формат Office: сохраните как .docx и приложите снова", old.getMessage());
        IllegalArgumentException broken = assertThrows(IllegalArgumentException.class,
                () -> DocumentText.extract("Отчёт.docx", "не zip".getBytes(StandardCharsets.UTF_8)));
        assertEquals("«Отчёт.docx» не прочитать: не zip-пакет", broken.getMessage());
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> DocumentText.extract("Пусто.docx", DocxWriter.fromMarkdown("")));
        assertEquals("В «Пусто.docx» нет текста", empty.getMessage());
        assertFalse(DocumentText.isDocument("log.txt"));
        assertTrue(DocumentText.isDocument("Счёт.PDF"));
    }

    @Test
    void longTextIsCut() {
        String line = "строка текста\n";
        String text = DocumentText.truncate(line.repeat(DocumentText.MAX_CHARS / line.length() + 100));

        assertTrue(text.length() < DocumentText.MAX_CHARS + 200, String.valueOf(text.length()));
        assertTrue(text.endsWith("тыс.]"), text.substring(text.length() - 100));
        assertTrue(text.contains("строка текста\n\n[… дальше не поместилось: показаны первые 199 тыс. символов из 201 тыс.]"),
                text.substring(text.length() - 120));
    }

    private static byte[] odf(String content) {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("mimetype", "application/vnd.oasis.opendocument.text");
        parts.put("content.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + content);
        return Ooxml.zip(parts);
    }
}
