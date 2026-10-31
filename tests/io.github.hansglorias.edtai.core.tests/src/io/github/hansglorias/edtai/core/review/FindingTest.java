package io.github.hansglorias.edtai.core.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class FindingTest {

    private static Finding finding(String module, int line, Finding.Severity severity) {
        return new Finding("Демо", "src/x.bsl", module, line, severity, "суть", "");
    }

    @Test
    void summaryCountsBySeverity() {
        assertEquals("замечаний нет", Finding.summary(List.of()));
        assertEquals("1 ошибка · 2 замечания", Finding.summary(List.of(finding("А", 1, Finding.Severity.ERROR),
                finding("А", 2, Finding.Severity.WARNING), finding("Б", 3, Finding.Severity.WARNING))));
        assertEquals("5 замечаний", Finding.Severity.WARNING.count(5));
        assertEquals("11 советов", Finding.Severity.INFO.count(11));
        assertEquals("21 ошибка", Finding.Severity.ERROR.count(21));
        assertEquals("3 совета", Finding.Severity.INFO.count(3));
    }

    @Test
    void errorsFirstThenByModuleAndLine() {
        List<Finding> sorted = Finding.sorted(List.of(finding("Б", 5, Finding.Severity.WARNING), finding("А", 9, Finding.Severity.INFO),
                finding("Б", 2, Finding.Severity.ERROR), finding("А", 7, Finding.Severity.WARNING)));
        assertEquals(List.of("Б:2", "А:7", "Б:5", "А:9"), sorted.stream().map(item -> item.module() + ":" + item.line()).toList());
    }

    @Test
    void storedAsJson() {
        List<Finding> findings = List.of(new Finding("Демо", "src/CommonModules/Цены/Module.bsl", "ОбщийМодуль.Цены", 42,
                Finding.Severity.ERROR, "Запрос в цикле", "Вынести выборку до цикла"), finding("Б", 0, Finding.Severity.INFO));
        List<Finding> loaded = Finding.fromJson(Finding.toJson(findings));
        assertEquals(findings, loaded);
        assertEquals(1, loaded.get(1).line(), "строка не меньше первой");
        assertEquals(Finding.Severity.ERROR, Finding.Severity.of("critical"));
        assertEquals(Finding.Severity.WARNING, Finding.Severity.of("непонятно"));
    }
}
