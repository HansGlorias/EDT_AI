package io.github.hansglorias.edtai.core.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModuleSearchTest {

    private static final String NAME = "ОбщийМодуль.ОбщегоНазначения.Модуль";
    private static final String PATH = "/Демо/src/CommonModules/ОбщегоНазначения/Module.bsl";

    @Test
    void substringOfNameOrPathAnyCase() {
        assertTrue(new ModuleSearch("назначения").matches(NAME, PATH));
        assertTrue(new ModuleSearch("commonmodules").matches(NAME, PATH));
        assertTrue(new ModuleSearch("Демо").matches(NAME, PATH));
        assertFalse(new ModuleSearch("Номенклатура").matches(NAME, PATH));
    }

    @Test
    void everyWordMustMatch() {
        assertTrue(new ModuleSearch("общий назнач").matches(NAME, PATH));
        assertTrue(new ModuleSearch("ОбщийМодуль.ОбщегоНазначения").matches(NAME, PATH));
        assertFalse(new ModuleSearch("общий справочник").matches(NAME, PATH));
    }

    @Test
    void camelCaseAbbreviation() {
        assertTrue(new ModuleSearch("ОбщМод").matches(NAME, PATH));
        assertTrue(new ModuleSearch("ОбНаз").matches(NAME, PATH));
        assertTrue(new ModuleSearch("ComMod").matches(NAME, PATH));
        assertFalse(new ModuleSearch("НазОбщ").matches(NAME, PATH));
        assertTrue(new ModuleSearch("СпрНом").matches("Справочник.Номенклатура.МодульОбъекта", "/Демо/src/Catalogs/Номенклатура/ObjectModule.bsl"));
    }

    @Test
    void yoAndEmptyQuery() {
        assertTrue(new ModuleSearch("отчёт").matches("Отчет.Продажи.МодульОбъекта", "/Демо/src/Reports/Продажи/ObjectModule.bsl"));
        assertTrue(new ModuleSearch("  ").isEmpty());
        assertTrue(new ModuleSearch("*").matches(NAME, PATH));
    }
}
