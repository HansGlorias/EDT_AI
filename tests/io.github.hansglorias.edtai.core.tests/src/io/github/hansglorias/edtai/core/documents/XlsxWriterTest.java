package io.github.hansglorias.edtai.core.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class XlsxWriterTest {

    @Test
    void markdownTablesBecomeSheets() throws Exception {
        Map<String, String> parts = DocxWriterTest.unzip(XlsxWriter.fromText("""
                ## Остатки: склад [1]

                | Товар | Кол-во | Цена | Сумма | Поступление | Код |
                |---|---:|---:|---:|---|---|
                | **Стол** | 12 | 4 500,50 | =B2*C2 | 15.03.2026 | 000123 |
                | Итого = всё | =SUM(B2:B2) | | | 31.02.2026 | |

                Текст между таблицами не попадает в книгу.

                | Без заголовка |
                |---|
                | а & б |
                """));

        assertEquals(List.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml",
                "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"), List.copyOf(parts.keySet()));
        for (String xml : parts.values()) {
            DocxWriterTest.parse(xml);
        }
        String workbook = parts.get("xl/workbook.xml");
        assertTrue(workbook.contains("<sheet name=\"Остатки склад 1\" sheetId=\"1\" r:id=\"rId1\"/>"), workbook);
        assertTrue(workbook.contains("<sheet name=\"Лист2\" sheetId=\"2\""), workbook);
        assertTrue(workbook.contains("'Остатки склад 1'!$A$1:$F$3"), "автофильтр на всю таблицу");

        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Товар</t>"), "шапка");
        assertTrue(sheet.contains("<c r=\"A2\" s=\"2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">Стол</t>"), "без разметки");
        assertTrue(sheet.contains("<c r=\"B2\" s=\"2\"><v>12</v></c><c r=\"C2\" s=\"2\"><v>4500.50</v></c>"), sheet);
        assertTrue(sheet.contains("<c r=\"D2\" s=\"2\"><f>B2*C2</f></c>"), "формула");
        assertTrue(sheet.contains("<c r=\"E2\" s=\"3\"><v>46096</v></c>"), "дата — номер дня Excel");
        assertTrue(sheet.contains("<t xml:space=\"preserve\">000123</t>"), "код с нулями — текст");
        assertTrue(sheet.contains("<t xml:space=\"preserve\">Итого = всё</t>"), "«=» внутри текста — не формула");
        assertTrue(sheet.contains("<f>SUM(B2:B2)</f>"));
        assertTrue(sheet.contains("<t xml:space=\"preserve\">31.02.2026</t>"), "несуществующая дата — текст");
        assertTrue(sheet.contains("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"));
        assertTrue(parts.get("xl/worksheets/sheet2.xml").contains("а &amp; б"));
    }

    @Test
    void csvWithQuotesAndSeparators() {
        assertEquals(List.of(List.of("Товар", "Комментарий"), List.of("Стол", "с ящиком; белый"), List.of("Шкаф", "двери \"купе\"\nновый")),
                XlsxWriter.csv("﻿Товар;Комментарий\r\nСтол;\"с ящиком; белый\"\n\nШкаф;\"двери \"\"купе\"\"\nновый\"\n"));
        assertEquals(List.of(List.of("a", "b"), List.of("1", "2")), XlsxWriter.csv("a,b\n1,2"));
        assertEquals(List.of(List.of("a", "b,c")), XlsxWriter.csv("a\tb,c"));
        assertEquals("Лист1", XlsxWriter.sheets("x;y\n1;2").get(0).name());
    }

    @Test
    void numbersDatesAndColumns() {
        assertEquals("1234567.5", XlsxWriter.number("1 234 567,5"));
        assertEquals("-12.25", XlsxWriter.number("-12.25"));
        assertEquals("0", XlsxWriter.number("0"));
        assertNull(XlsxWriter.number("0012"));
        assertNull(XlsxWriter.number("1234567890123456"), "длиннее 15 цифр Excel округлит");
        assertNull(XlsxWriter.number("12 34"));
        assertEquals(45658L, XlsxWriter.date("01.01.2025"));
        assertNull(XlsxWriter.date("2025-01-01"));
        assertEquals(List.of("A", "Z", "AA", "AZ", "BA"), List.of(XlsxWriter.column(0), XlsxWriter.column(25), XlsxWriter.column(26),
                XlsxWriter.column(51), XlsxWriter.column(52)));
    }

    @Test
    void sameHeadingsGetUniqueNames() {
        List<XlsxWriter.Sheet> sheets = XlsxWriter.sheets("## Итоги\n\n| a |\n|---|\n| 1 |\n\n## итоги\n\n| b |\n|---|\n| 2 |\n\n## "
                + "Очень длинное название листа больше тридцати одного знака\n\n| c |\n|---|\n| 3 |");
        assertEquals(List.of("Итоги", "итоги (2)", "Очень длинное название листа бо"), sheets.stream().map(XlsxWriter.Sheet::name).toList());
    }
}
