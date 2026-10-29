package io.github.hansglorias.edtai.core.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PptxWriterTest {

    private static final String DECK = """
            # Обмен с УТ
            Настройка и ошибки

            ## Шаги

            1. Откройте **настройки**
            2. Создайте узел
               - вложенный пункт

            ## Ошибки

            | Код | Решение |
            |:---:|---|
            | 1 | Проверьте `адрес` |

            ---

            ```bsl
            Сообщить("<&>");
            ```
            """;

    @Test
    void headingsAndRulesSplitSlides() {
        List<PptxWriter.Slide> slides = PptxWriter.slides(DECK);
        assertEquals(4, slides.size());
        assertEquals("Обмен с УТ", slides.get(0).title());
        assertEquals(List.of("Шаги", "Ошибки"), List.of(slides.get(1).title(), slides.get(2).title()));
        assertEquals(null, slides.get(3).title(), "после --- — слайд без заголовка");
        assertEquals(1, PptxWriter.slides("").size(), "пустая презентация — один пустой слайд");
        assertEquals(1, PptxWriter.slides("Просто текст").size());
    }

    @Test
    void packageIsValid() throws Exception {
        Map<String, String> parts = DocxWriterTest.unzip(PptxWriter.fromMarkdown(DECK));
        for (String xml : parts.values()) {
            DocxWriterTest.parse(xml);
        }
        assertTrue(parts.keySet().containsAll(List.of("[Content_Types].xml", "ppt/presentation.xml", "ppt/theme/theme1.xml",
                "ppt/slideMasters/slideMaster1.xml", "ppt/slideLayouts/slideLayout1.xml", "ppt/slides/slide4.xml",
                "ppt/slides/_rels/slide4.xml.rels")), parts.keySet().toString());
        assertTrue(parts.get("[Content_Types].xml").contains("/ppt/slides/slide4.xml"));
        assertEquals(4, parts.get("ppt/presentation.xml").split("<p:sldId ", -1).length - 1);

        String title = parts.get("ppt/slides/slide1.xml");
        assertTrue(title.contains("algn=\"ctr\"") && title.contains(">Обмен с УТ<") && title.contains(">Настройка и ошибки<"),
                "титульный слайд по центру");
        String steps = parts.get("ppt/slides/slide2.xml");
        assertTrue(steps.contains("<a:buAutoNum type=\"arabicPeriod\"/>"), "нумерованный список");
        assertTrue(steps.contains("lvl=\"1\"") && steps.contains("<a:buChar char=\"–\"/>"), "вложенный пункт");
        assertTrue(steps.contains("b=\"1\" dirty=\"0\"><a:latin typeface=\"+mn-lt\"/><a:cs typeface=\"+mn-lt\"/></a:rPr><a:t>настройки<"),
                steps);
        String table = parts.get("ppt/slides/slide3.xml");
        assertTrue(table.contains("<a:tbl>") && table.contains("<a:gridCol w=") && table.contains(">Проверьте <"), table);
        String code = parts.get("ppt/slides/slide4.xml");
        assertTrue(code.contains("<a:latin typeface=\"Consolas\"/>") && code.contains("Сообщить(&quot;&lt;&amp;&gt;&quot;);"), code);
        assertFalse(code.contains("Обмен"), "заголовок первого слайда сюда не попал");
    }
}
