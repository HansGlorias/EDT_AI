package io.github.hansglorias.edtai.core.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;

/** PDF собираем в тесте сами: так видно, какие конструкции разбираются. */
class PdfTextTest {

    /** Составной шрифт: ToUnicode — «С», пробел, запятая и диапазон а…я с кода 0020; ширина глифа 500. */
    private static final String CMAP = """
            /CIDInit /ProcSet findresource begin 12 dict begin begincmap
            1 begincodespacerange <0000> <FFFF> endcodespacerange
            3 beginbfchar <0001> <0421> <0002> <0020> <0003> <002C> endbfchar
            1 beginbfrange <0020> <003F> <0430> endbfrange
            endcmap CMapName currentdict /CMap defineresource pop end end
            """;

    @Test
    void textFromCompositeAndSimpleFonts() throws Exception {
        // «Склад С» — bfchar и bfrange; запятая — в новом BT вплотную: та же строка, без пробела.
        String content = """
                BT /F1 10 Tf 50 700 Td <0001002A002B00200024 00020001> Tj ET
                BT /F1 10 Tf 85.1 700 Td <0003> Tj ET
                BT /F2 10 Tf 12 TL 50 680 Td [(Price) -300 (list)] TJ (next) ' ET
                BT /F2 10 Tf 1 0 0 1 50 660 Tm (A) Tj 1 0 0 1 200 660 Tm (B) Tj ET
                """;
        Pdf pdf = new Pdf();
        int catalog = pdf.add("<< /Type /Catalog /Pages 2 0 R >>");
        pdf.add("<< /Type /Pages /Kids [3 0 R] /Count 1 /Resources << /Font << /F1 5 0 R /F2 8 0 R >> >> >>");
        pdf.add("<< /Type /Page /Parent 2 0 R /Contents 4 0 R >>");
        pdf.stream("", content, true);
        pdf.add("<< /Type /Font /Subtype /Type0 /BaseFont /Arial /Encoding /Identity-H /DescendantFonts [6 0 R] /ToUnicode 7 0 R >>");
        pdf.add("<< /Type /Font /Subtype /CIDFontType2 /DW 500 >>");
        pdf.stream("", CMAP, true);
        pdf.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding /FirstChar 32 /Widths [250] >>");
        assertEquals(1, catalog);

        assertEquals("""
                Склад С,
                Price list
                next
                A\tB""", PdfText.extract(pdf.bytes()));
    }

    @Test
    void objectStreamsAndDifferences() throws Exception {
        // Шрифт в потоке объектов (PDF 1.5), кириллица по именам глифов Adobe.
        Pdf pdf = new Pdf();
        pdf.add("<< /Type /Catalog /Pages 2 0 R >>");
        pdf.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        pdf.add("<< /Type /Page /Parent 2 0 R /Contents 4 0 R /Resources << /Font << /F1 6 0 R >> >> >>");
        pdf.stream("", "BT /F1 12 Tf 10 10 Td (\\101\\102 OK) Tj ET", false);
        String font = "<< /Type /Font /Subtype /Type1 /Encoding << /Differences [65 /afii10017 /uni0444] >> >>";
        pdf.stream("/Type /ObjStm /N 1 /First 4", "6 0 " + font, true);

        assertEquals("Аф OK", PdfText.extract(pdf.bytes()));
    }

    @Test
    void encryptedAndBroken() {
        Pdf pdf = new Pdf();
        pdf.add("<< /Type /Catalog >>");
        byte[] encrypted = (new String(pdf.bytes(), StandardCharsets.ISO_8859_1) + "trailer << /Root 1 0 R /Encrypt 2 0 R >>")
                .getBytes(StandardCharsets.ISO_8859_1);

        assertEquals("PDF зашифрован — снимите защиту или приложите страницы картинками",
                assertThrows(java.io.IOException.class, () -> PdfText.extract(encrypted)).getMessage());
        IllegalArgumentException scan = assertThrows(IllegalArgumentException.class, () -> DocumentText.extract("Скан.pdf", pdf.bytes()));
        assertEquals("В «Скан.pdf» нет текста (скан?): приложите страницы картинками", scan.getMessage());
        IllegalArgumentException notPdf = assertThrows(IllegalArgumentException.class,
                () -> DocumentText.extract("Счёт.pdf", "просто текст".getBytes(StandardCharsets.UTF_8)));
        assertEquals("«Счёт.pdf» не прочитать: это не PDF", notPdf.getMessage());
    }

    @Test
    void lexer() {
        PdfText.Lexer lexer = new PdfText.Lexer("<< /A (a\\)b\\(c\\\\) /B <4142> /C [1 0 R -.5 /N#20x] /D true >>"
                .getBytes(StandardCharsets.ISO_8859_1), 0);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = (java.util.Map<String, Object>) lexer.value();

        assertEquals("a)b(c\\", new String(((PdfText.Bytes) map.get("A")).value(), StandardCharsets.ISO_8859_1));
        assertEquals("AB", new String(((PdfText.Bytes) map.get("B")).value(), StandardCharsets.ISO_8859_1));
        assertEquals(List.of(new PdfText.Ref(1), -0.5, new PdfText.Name("N x")), map.get("C"));
        assertEquals(Boolean.TRUE, map.get("D"));
        assertThrows(IllegalStateException.class, () -> new PdfText.Lexer("[".repeat(100_000).getBytes(StandardCharsets.ISO_8859_1), 0)
                .value(), "битый файл — ошибка, а не переполнение стека");
        assertEquals(new PdfText.Op("Tj"), new PdfText.Lexer(")".repeat(100_000).concat(" Tj").getBytes(StandardCharsets.ISO_8859_1), 0)
                .contentToken());
    }

    /** Объекты по порядку номеров с 1; таблица xref не нужна — разбор её не читает. */
    private static final class Pdf {

        private final List<byte[]> objects = new ArrayList<>();

        int add(String dictionary) {
            objects.add(dictionary.getBytes(StandardCharsets.ISO_8859_1));
            return objects.size();
        }

        void stream(String dictionary, String data, boolean flate) {
            byte[] raw = data.getBytes(StandardCharsets.UTF_8);
            if (flate) {
                Deflater deflater = new Deflater();
                deflater.setInput(raw);
                deflater.finish();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                while (!deflater.finished()) {
                    out.write(buffer, 0, deflater.deflate(buffer));
                }
                raw = out.toByteArray();
            }
            ByteArrayOutputStream object = new ByteArrayOutputStream();
            object.writeBytes(("<< " + dictionary + (flate ? " /Filter /FlateDecode" : "") + " /Length " + raw.length + " >>\nstream\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            object.writeBytes(raw);
            object.writeBytes("\nendstream".getBytes(StandardCharsets.ISO_8859_1));
            objects.add(object.toByteArray());
        }

        byte[] bytes() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes("%PDF-1.7\n".getBytes(StandardCharsets.ISO_8859_1));
            for (int i = 0; i < objects.size(); i++) {
                out.writeBytes(((i + 1) + " 0 obj\n").getBytes(StandardCharsets.ISO_8859_1));
                out.writeBytes(objects.get(i));
                out.writeBytes("\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));
            }
            out.writeBytes("%%EOF\n".getBytes(StandardCharsets.ISO_8859_1));
            return out.toByteArray();
        }
    }
}
