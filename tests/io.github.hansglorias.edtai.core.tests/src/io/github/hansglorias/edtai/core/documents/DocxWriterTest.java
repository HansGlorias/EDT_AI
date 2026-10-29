package io.github.hansglorias.edtai.core.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class DocxWriterTest {

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Test
    void markdownBecomesWordDocument() throws Exception {
        Map<String, String> parts = unzip(DocxWriter.fromMarkdown("""
                # Обмен с УТ

                Абзац с **жирным**, *курсивом*, `кодом` и [ссылкой](https://example.invalid/a?b=1&c=2).
                Вторая строка.

                1. Первый
                2. Второй
                   - вложенный

                Текст между списками.

                1. Снова с единицы

                | Код | Описание |
                |:---:|---------:|
                | 1 | Нет связи |

                > Цитата

                ```bsl
                Сообщить("<&>");
                ```
                """));

        assertEquals(List.of("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/numbering.xml",
                "word/_rels/document.xml.rels"), List.copyOf(parts.keySet()));
        for (Map.Entry<String, String> part : parts.entrySet()) {
            parse(part.getValue()); // каждая часть — правильный XML
        }
        Document document = parse(parts.get("word/document.xml"));
        List<String> paragraphs = new ArrayList<>();
        NodeList list = document.getElementsByTagNameNS(W, "p");
        for (int i = 0; i < list.getLength(); i++) {
            Element p = (Element) list.item(i);
            if (p.getParentNode().getLocalName().equals("body")) {
                paragraphs.add(style(p) + ": " + text(p));
            }
        }
        assertEquals(List.of("Heading1: Обмен с УТ", "Normal: Абзац с жирным, курсивом, кодом и ссылкой.\nВторая строка.",
                "ListParagraph#2/0: Первый", "ListParagraph#2/0: Второй", "ListParagraph#1/1: вложенный",
                "Normal: Текст между списками.", "ListParagraph#3/0: Снова с единицы", "Normal: ", "Quote: Цитата",
                "Code: Сообщить(\"<&>\");"), paragraphs);

        NodeList cells = document.getElementsByTagNameNS(W, "tc");
        assertEquals(4, cells.getLength());
        assertEquals("Нет связи", text((Element) ((Element) cells.item(3)).getElementsByTagNameNS(W, "p").item(0)));
        String xml = parts.get("word/document.xml");
        assertTrue(xml.contains("<w:tblHeader/>"), "шапка таблицы повторяется на страницах");
        assertTrue(xml.contains("<w:jc w:val=\"center\"/>") && xml.contains("<w:jc w:val=\"right\"/>"), "выравнивание колонок");
        assertTrue(xml.contains("<w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">жирным"), xml);
        assertTrue(xml.contains("<w:hyperlink r:id=\"rIdLink1\">"), xml);
        assertTrue(parts.get("word/_rels/document.xml.rels").contains("Target=\"https://example.invalid/a?b=1&amp;c=2\" TargetMode=\"External\""));
        String numbering = parts.get("word/numbering.xml");
        assertTrue(numbering.contains("<w:num w:numId=\"3\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride"),
                "второй нумерованный список — с единицы");
    }

    @Test
    void textIsEscapedAndControlCharactersDropped() throws Exception {
        String xml = unzip(DocxWriter.fromMarkdown("A & B <c> \"d\"\u0001 \uD83D\uDE00")).get("word/document.xml");
        parse(xml);
        assertTrue(xml.contains("A &amp; B &lt;c&gt; &quot;d&quot; \uD83D\uDE00"), xml);
        assertEquals("A & B <c> \"d\"", DocxWriter.escape("A & B <c> \"d\"\u0001").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\""));
    }

    @Test
    void emptyMarkdownGivesValidDocument() throws Exception {
        Document document = parse(unzip(DocxWriter.fromMarkdown("")).get("word/document.xml"));
        assertEquals(1, document.getElementsByTagNameNS(W, "sectPr").getLength());
        assertFalse(unzip(DocxWriter.fromMarkdown(null)).isEmpty());
    }

    private static String style(Element p) {
        NodeList styles = p.getElementsByTagNameNS(W, "pStyle");
        String style = styles.getLength() == 0 ? "Normal" : ((Element) styles.item(0)).getAttributeNS(W, "val");
        NodeList numbers = p.getElementsByTagNameNS(W, "numId");
        if (numbers.getLength() > 0) {
            style += "#" + ((Element) numbers.item(0)).getAttributeNS(W, "val") + "/"
                    + ((Element) p.getElementsByTagNameNS(W, "ilvl").item(0)).getAttributeNS(W, "val");
        }
        return style;
    }

    /** Текст абзаца: {@code w:t}, переводы строк {@code w:br}. */
    private static String text(Element p) {
        StringBuilder text = new StringBuilder();
        NodeList all = p.getElementsByTagNameNS(W, "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (element.getLocalName().equals("t")) {
                text.append(element.getTextContent());
            } else if (element.getLocalName().equals("br")) {
                text.append('\n');
            }
        }
        return text.toString();
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    static Map<String, String> unzip(byte[] bytes) throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }
}
