package io.github.hansglorias.edtai.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.review.Finding;

class ReviewToolsTest {

    private static final String MODULE = "src/CommonModules/Цены/Module.bsl";

    @TempDir
    Path temp;

    private Path module;
    private Tool tool;
    private final List<List<Finding>> published = new ArrayList<>();
    private final Map<String, String> unsaved = new HashMap<>();

    @BeforeEach
    void createProject() throws Exception {
        module = temp.resolve("Демо").resolve(MODULE);
        Files.createDirectories(module.getParent());
        Files.writeString(module, "Процедура А()\nКонецПроцедуры\n");
        tool = ReviewTools.create(new WorkspaceFiles() {
            @Override
            public List<String> projects() {
                return List.of("Демо");
            }

            @Override
            public Optional<Path> root(String project) {
                return Optional.of(temp.resolve(project));
            }

            @Override
            public Optional<String> unsavedText(String project, String path) {
                return Optional.ofNullable(unsaved.get(path));
            }
        }, published::add).get(0);
    }

    @Test
    void findingsGoToMarkersAndCard() throws Exception {
        Tool.Result result = tool.call(Map.of("findings", List.of(
                Map.of("module", "ОбщийМодуль.Цены", "line", 2, "severity", "warning", "title", "Нет комментария", "fix", "Описать"),
                Map.of("module", "ОбщийМодуль.Цены", "line", 1, "severity", "error", "title", "Запрос в цикле"),
                Map.of("module", "ОбщийМодуль.Нет", "line", 1, "severity", "error", "title", "Выдумано"))));

        assertEquals("report_review", tool.name());
        assertFalse(tool.modifies(), "доступен и в режимах «План» и «Вопросы»");
        List<Finding> marked = published.get(0);
        assertEquals(List.of("Запрос в цикле", "Нет комментария"), marked.stream().map(Finding::title).toList(), "сначала ошибки");
        assertEquals("src/CommonModules/Цены/Module.bsl", marked.get(0).path());
        assertEquals("ОбщийМодуль.Цены.Модуль", marked.get(0).module());
        assertEquals("1 ошибка · 1 замечание", result.outcome());
        assertTrue(result.text().contains("ОбщийМодуль.Нет: модуля нет в проекте"), result.text());
        assertEquals(Attachment.REVIEW, result.file().mediaType());
        assertEquals(marked, Finding.fromJson(result.file().data()));
    }

    @Test
    void missingNullAndNonArrayFindingsAreNotEmptyReview() {
        assertBadArguments(Map.of(), "findings");
        for (Object value : Arrays.asList(null, "[]", Map.of(), 1, true)) {
            assertBadArguments(Json.object("findings", value), "findings");
        }
    }

    @Test
    void everyFindingMustBeAnObject() {
        for (Object value : Arrays.asList(null, "находка", List.of(), 1, false)) {
            assertBadArguments(Map.of("findings", Arrays.asList(validFinding(), value)), "findings[1]");
        }
    }

    @Test
    void malformedLastFindingRejectsWholeBatchBeforePublishing() {
        for (String field : List.of("module", "line", "severity", "title")) {
            Map<String, Object> item = validFinding();
            item.remove(field);
            assertBadFinding(item, field);
            for (Object value : Arrays.asList(null, true, List.of(), Map.of())) {
                item = validFinding();
                item.put(field, value);
                assertBadFinding(item, field);
            }
        }
        for (String field : List.of("module", "severity", "title", "fix")) {
            Map<String, Object> item = validFinding();
            item.put(field, 42);
            assertBadFinding(item, field);
            if (!field.equals("fix")) {
                item.put(field, " \t");
                assertBadFinding(item, field);
            }
        }
        for (Object value : Arrays.asList(null, false, Map.of(), List.of())) {
            Map<String, Object> item = validFinding();
            item.put("fix", value);
            assertBadFinding(item, "fix");
        }
        Map<String, Object> item = validFinding();
        item.put("severity", "critical");
        assertBadFinding(item, "severity");
        assertBadArguments(Json.object("findings", List.of(validFinding()), "project", null), "project");
        assertBadArguments(Json.object("findings", List.of(validFinding()), "project", 42), "project");
    }

    @Test
    void lineMustBeAnExactPositiveIntegerWithoutOverflow() {
        for (Object value : List.of("2", 0, -1, new BigDecimal("1.5"), 4294967297L,
                new BigDecimal("1e100"), Double.NaN, Double.POSITIVE_INFINITY)) {
            Map<String, Object> item = validFinding();
            item.put("line", value);
            assertBadFinding(item, "line");
        }
    }

    @Test
    void explicitEmptyArrayProducesEmptyReview() throws Exception {
        Tool.Result result = tool.call(Map.of("findings", List.of()));
        assertEquals(List.of(List.of()), published);
        assertEquals("замечаний нет", result.outcome());
        assertEquals(List.of(), Finding.fromJson(result.file().data()));
    }

    @Test
    void lineRangeUsesCurrentUnsavedBufferInBothDirections() throws Exception {
        // Диск — 2 строки, буфер — 3; строка 3 существует только в редакторе.
        unsaved.put(MODULE, "А\r\nБ\r\nВ\r\n");
        Map<String, Object> item = validFinding();
        item.put("line", new BigDecimal("3.0"));
        Tool.Result result = tool.call(Map.of("findings", List.of(item)));
        assertEquals(3, Finding.fromJson(result.file().data()).get(0).line());
        published.clear();

        // Теперь буфер короче диска: строка 2 уже удалена пользователем.
        unsaved.put(MODULE, "А\n");
        assertUnresolved(List.of(validFinding()), "строки 2 нет");
        assertEquals("Процедура А()\nКонецПроцедуры\n", Files.readString(module), "ревью не сохраняет буфер");
    }

    @Test
    void lineBoundariesMatchReadModuleWithLfCrLfAndFinalNewline() throws Exception {
        for (String delimiter : List.of("\n", "\r\n", "\r")) {
            for (String ending : List.of("", delimiter)) {
                Files.writeString(module, "\uFEFFА" + delimiter + "Б" + ending);
                Tool.Result result = tool.call(Map.of("findings", List.of(validFinding())));
                assertEquals(2, Finding.fromJson(result.file().data()).get(0).line());
                published.clear();
                Map<String, Object> item = validFinding();
                item.put("line", 3);
                assertUnresolved(List.of(item), "в модуле строк: 2");
            }
        }
        Files.writeString(module, "");
        Map<String, Object> item = validFinding();
        item.put("line", 1);
        tool.call(Map.of("findings", List.of(item)));
        published.clear();
        assertUnresolved(List.of(validFinding()), "в модуле строк: 1");
    }

    @Test
    void unresolvedReferencesAreSkippedButNeverReportedAsCleanReview() throws Exception {
        List<Map<String, Object>> rejected = new ArrayList<>();
        for (String name : List.of("ОбщийМодуль.Нет", "ВыдуманныйМодуль", "../outside.bsl", "src/Configuration.mdo")) {
            Map<String, Object> item = validFinding();
            item.put("module", name);
            rejected.add(item);
        }
        // Файл снаружи действительно существует: его нельзя принимать в ревью проекта.
        Files.writeString(temp.resolve("outside.bsl"), "А\nБ\n");
        Files.writeString(temp.resolve("Демо/src/Configuration.mdo"), "А\nБ\n");
        Map<String, Object> pastEnd = validFinding();
        pastEnd.put("line", 3);
        rejected.add(pastEnd);

        assertUnresolved(rejected, "Не принято ни одной находки");
        List<Map<String, Object>> mixed = new ArrayList<>(rejected);
        mixed.add(validFinding());
        Tool.Result result = tool.call(Map.of("findings", mixed));
        assertEquals(1, published.size());
        assertEquals(1, published.get(0).size());
        assertEquals(published.get(0), Finding.fromJson(result.file().data()));
        assertTrue(result.text().contains("Не приняты"), result.text());
        assertTrue(result.text().contains("строки 3 нет"), result.text());
    }

    @Test
    void malformedReviewDoesNotReplacePreviouslyPublishedFindings() throws Exception {
        tool.call(Map.of("findings", List.of(validFinding())));
        List<List<Finding>> before = List.copyOf(published);
        assertThrows(ToolException.class, () -> tool.call(Map.of("findings", List.of(validFinding(), Map.of()))));
        assertEquals(before, published);
    }

    private Map<String, Object> validFinding() {
        return new HashMap<>(Map.of("module", "ОбщийМодуль.Цены", "line", 2, "severity", "warning", "title", "Нет комментария"));
    }

    private void assertBadFinding(Map<String, Object> item, String field) {
        assertBadArguments(Map.of("findings", List.of(validFinding(), item)), "findings[1]." + field);
    }

    private void assertBadArguments(Map<String, Object> input, String field) {
        ToolException error = assertThrows(ToolException.class, () -> tool.call(input));
        assertEquals(ToolUse.Problem.BAD_ARGUMENTS, error.problem());
        assertTrue(error.getMessage().contains("«" + field + "»"), error.getMessage());
        assertTrue(published.isEmpty(), "ошибочный список не передаётся обработчику маркеров");
    }

    private void assertUnresolved(List<?> findings, String reason) {
        ToolException error = assertThrows(ToolException.class, () -> tool.call(Map.of("findings", findings)));
        assertEquals(ToolUse.Problem.FAILED, error.problem(), "форма верна, но ссылки не разрешились");
        assertTrue(error.getMessage().contains(reason), error.getMessage());
        assertTrue(published.isEmpty());
    }
}
