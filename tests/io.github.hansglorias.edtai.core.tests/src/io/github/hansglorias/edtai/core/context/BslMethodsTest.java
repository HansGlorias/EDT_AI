package io.github.hansglorias.edtai.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.context.BslMethods.Method;

class BslMethodsTest {

    private static final String MODULE = String.join("\n",
            "#Область ПрограммныйИнтерфейс", // 0
            "", // 1
            "// Возвращает цену.", // 2
            "//", // 3
            "// Параметры:", // 4
            "//   Товар - СправочникСсылка.Номенклатура", // 5
            "&НаСервере", // 6
            "Функция Цена(Товар) Экспорт", // 7
            "\tТекст = \"КонецФункции в строке\";", // 8
            "\t// КонецФункции в комментарии", // 9
            "\tЗапрос = \"ВЫБРАТЬ", // 10
            "\t|  Цена", // 11
            "\t|КонецФункции", // 12
            "\t|\"\"кавычки\"\" ИЗ Цены\";", // 13
            "\tВозврат 0;", // 14
            "КонецФункции", // 15
            "", // 16
            "&НаКлиенте", // 17
            "Асинх Процедура Открыть(Команда)", // 18
            "\tЖдать Предупреждение(\"Привет\");", // 19
            "КонецПроцедуры // Открыть", // 20
            "", // 21
            "Procedure English() Export", // 22
            "EndProcedure", // 23
            "", // 24
            "#КонецОбласти"); // 25

    @Test
    void findsMethodsWithHeaderComments() {
        assertEquals(List.of(new Method("Цена", 2, 15), new Method("Открыть", 17, 20), new Method("English", 22, 23)),
                BslMethods.methods(MODULE));
    }

    @Test
    void enclosingMethodOfLine() {
        assertEquals("Цена", BslMethods.enclosing(MODULE, 3).orElseThrow().name());
        assertEquals("Цена", BslMethods.enclosing(MODULE, 12).orElseThrow().name());
        assertEquals("Цена", BslMethods.enclosing(MODULE, 15).orElseThrow().name());
        assertEquals("Открыть", BslMethods.enclosing(MODULE, 18).orElseThrow().name());
        assertEquals(Optional.empty(), BslMethods.enclosing(MODULE, 0));
        assertEquals(Optional.empty(), BslMethods.enclosing(MODULE, 16));
        assertEquals(Optional.empty(), BslMethods.enclosing(MODULE, 25));
    }

    @Test
    void unclosedMethodEndsBeforeNextOne() {
        String text = "Процедура А()\n\tБ();\n\n&НаСервере\nПроцедура В()\nКонецПроцедуры\nПроцедура Г()\n\tД();\n\n";

        assertEquals(List.of(new Method("А", 0, 1), new Method("В", 3, 5), new Method("Г", 6, 7)), BslMethods.methods(text));
    }

    @Test
    void oneLineMethodAndCaseInsensitiveKeywords() {
        assertEquals(List.of(new Method("Пусто", 0, 0), new Method("Ф", 1, 3)),
                BslMethods.methods("Процедура Пусто() КонецПроцедуры\nфункция Ф()\r\n\tвозврат 1;\r\nконецфункции"));
    }

    @Test
    void blankLineSeparatesCommentFromMethod() {
        assertEquals(List.of(new Method("А", 2, 3)), BslMethods.methods("// не про метод\n\nПроцедура А()\nКонецПроцедуры"));
    }

    @Test
    void wordsLikeKeywordsAreNotMethods() {
        assertEquals(List.of(), BslMethods.methods("ПроцедураОбработки = 1;\nА = \"Процедура Б()\";\n// Функция В()"));
    }

    @Test
    void stringsAndCommentsAreBlanked() {
        assertEquals(List.of("А = \"     \"; ", "Б = " + " ".repeat(10) + ";"), BslMethods.code(List.of("А = \"текст\"; // комм", "Б = '20260101';")));
    }
}
