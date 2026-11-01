package io.github.hansglorias.edtai.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.chat.ChatHistoryFile;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.llm.ModelChatProvider;
import io.github.hansglorias.edtai.core.llm.ModelClient;
import io.github.hansglorias.edtai.core.llm.ModelConnection;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;

/** Проверяет фикстуры и Java-механизмы. Ответы настоящей модели оцениваются отдельно. */
class DeveloperScenariosTest {

    private static final String ROOT = "/fixtures/developer-scenarios/";
    private static final String CALC = path("РасчетыЗаказов");
    private static final String BUFFER = path("БуферЗаказов");
    private static final List<String> MODULES = List.of("РасчетыЗаказов", "ОформлениеЗаказов", "ПодсказкиЗаказов",
            "БуферЗаказов", "ЗапросыТоваров", "ПроверкаЗаказов");

    @TempDir
    Path directory;
    private final Map<String, String> unsaved = new HashMap<>();
    private final List<EditTools.Change> changes = new ArrayList<>();
    private WorkspaceFiles workspace;
    private Map<String, Tool> tools;

    @BeforeEach
    void prepareSources() throws Exception {
        for (String module : MODULES) {
            Path file = directory.resolve(path(module));
            Files.createDirectories(file.getParent());
            Files.writeString(file, resource("project/" + path(module)));
        }
        workspace = new WorkspaceFiles() {
            @Override
            public List<String> projects() {
                return List.of("Сценарии1С");
            }

            @Override
            public Optional<Path> root(String project) {
                return project.equals("Сценарии1С") ? Optional.of(directory) : Optional.empty();
            }

            @Override
            public Optional<String> preferredProject() {
                return Optional.of("Сценарии1С");
            }

            @Override
            public Optional<String> unsavedText(String project, String file) {
                return Optional.ofNullable(unsaved.get(file));
            }

            @Override
            public Map<String, String> unsavedTexts(String project) {
                return Map.copyOf(unsaved);
            }

            @Override
            public Edit edit(String project, String file, TextChange change) throws ToolException {
                if (!unsaved.containsKey(file)) {
                    return WorkspaceFiles.super.edit(project, file, change);
                }
                String before = unsaved.get(file);
                TextChange.Replacement replacement = change.compute(before);
                String after = before.substring(0, replacement.offset()) + replacement.text()
                        + before.substring(replacement.offset() + replacement.length());
                unsaved.put(file, after);
                return new Edit(after, replacement, false, before);
            }
        };
        List<Tool> all = new ArrayList<>(ProjectTools.create(workspace, () -> 40_000));
        all.addAll(EditTools.create(workspace, changes::add));
        tools = all.stream().collect(Collectors.toMap(Tool::name, tool -> tool));
    }

    @Test
    void catalogHasFifteenCompleteCardsAndExistingReferences() throws Exception {
        List<Object> cards = Json.asList(Json.parseObject(resource("scenarios.json")).get("scenarios"));
        assertEquals(15, cards.size());
        for (int i = 0; i < cards.size(); i++) {
            Object card = cards.get(i);
            assertEquals("S%02d".formatted(i + 1), Json.stringAt(card, "id"));
            for (String field : List.of("state", "prompts", "failures")) {
                assertFalse(Json.asList(Json.at(card, field)).isEmpty(), field);
            }
            assertFalse(Json.asList(Json.at(card, "oracle", "facts")).isEmpty());
            for (Object change : Json.asList(Json.at(card, "allowed_changes"))) {
                assertTrue(Files.isRegularFile(directory.resolve(Json.stringAt(change, "path"))));
                assertFalse(resource(Json.stringAt(change, "expected")).isBlank());
            }
            if (Json.at(card, "attachment") instanceof String attachment) {
                assertFalse(resource(attachment).isBlank());
            }
        }
        List<String> lines = resource("project/" + CALC).lines().toList();
        assertTrue(lines.get(7).startsWith("Функция СуммаСоСкидкой"));
        assertEquals("\tВозврат Сумма / Количество;", lines.get(18));
        assertTrue(resource("stack.txt").contains("Модуль(19)"));
        assertTrue(resource("project/" + path("ОформлениеЗаказов")).lines().toList().get(17)
                .contains("РасчетыЗаказов.СуммаСоСкидкой"));
    }

    @Test
    void searchReturnsTextMatchesRatherThanPretendingTheyAreReferences() throws Exception {
        Tool.Result result = tools.get("search_code").call(Map.of("query", "СуммаСоСкидкой("));
        assertEquals("найдено 7", result.outcome());
        assertTrue(result.text().contains("ОформлениеЗаказов.Модуль:8:"));
        assertTrue(result.text().contains("ОформлениеЗаказов.Модуль:18:"));
        assertTrue(result.text().contains("ПодсказкиЗаказов.Модуль:5:"));
        assertTrue(result.text().contains("ПодсказкиЗаказов.Модуль:8:"));
        // Отличить вызовы от комментария, строки и одноимённого метода должна реальная модель.
        assertUnchangedExcept(Map.of());
    }

    @Test
    void smallEditUsesRealToolAndPreservesBomAndLineEndings() throws Exception {
        for (String delimiter : List.of("\n", "\r\n")) {
            Files.writeString(directory.resolve(CALC), "\uFEFF" + resource("project/" + CALC).replace("\n", delimiter));
            edit(CALC, "/ 100, 2)", "/ 100, 0)");
            assertEquals("\uFEFF" + resource("expected/S05.bsl").replace("\n", delimiter),
                    Files.readString(directory.resolve(CALC)));
        }
        Files.writeString(directory.resolve(CALC), resource("expected/S05.bsl"));
        assertUnchangedExcept(Map.of(CALC, "expected/S05.bsl"));
        assertEquals(resource("expected/S05.bsl"), resource("expected/S15.bsl"));
    }

    @Test
    void ambiguousFragmentDoesNotWriteAndQualifiedFragmentChangesOnlyJournalText() throws Exception {
        String before = Files.readString(directory.resolve(CALC));
        assertTrue(assertThrows(ToolException.class, () -> edit(CALC, "Возврат \"Готово\";", "Возврат \"Завершено\";"))
                .getMessage().contains("2 раз"));
        assertEquals(before, Files.readString(directory.resolve(CALC)));
        assertTrue(changes.isEmpty());
        edit(CALC, "Функция ТекстДляЖурнала() Экспорт\n\tВозврат \"Готово\";",
                "Функция ТекстДляЖурнала() Экспорт\n\tВозврат \"Завершено\";");
        assertUnchangedExcept(Map.of(CALC, "expected/S06.bsl"));
    }

    @Test
    void unsavedAdapterKeepsUserTextAndLeavesDiskUntouched() throws Exception {
        unsaved.put(BUFFER, resource("states/S07-buffer.bsl"));
        assertTrue(tools.get("read_module").call(Map.of("module", BUFFER)).text().contains("Возврат 7;"));
        assertTrue(tools.get("search_code").call(Map.of("query", "билет 314")).text().contains("БуферЗаказов"));
        Tool.Result result = edit(BUFFER, "\"Цена без налога\"", "\"Цена с налогом\"");
        assertTrue(result.text().contains("не сохранён"), result.text());
        assertEquals(resource("expected/S07.bsl"), unsaved.get(BUFFER));
        assertUnchangedExcept(Map.of());
        // Здесь только контракт WorkspaceFiles; dirty и Ctrl+Z в настоящем EDT проверяются руками.
    }

    @Test
    void queryEditRetainsOtherClausesAndDataRubricHasBothBoundaries() throws Exception {
        String query = path("ЗапросыТоваров");
        edit(query, "параметры Товары и МинимальнаяЦена задаёт",
                "параметры Товары, МинимальнаяЦена и МаксимальнаяЦена задаёт");
        edit(query, "\t|\tИ Товары.Цена >= &МинимальнаяЦена",
                "\t|\tИ Товары.Цена >= &МинимальнаяЦена\n\t|\tИ Товары.Цена <= &МаксимальнаяЦена");
        assertUnchangedExcept(Map.of(query, "expected/S09.bsl"));
        Map<String, Object> data = Json.parseObject(resource("query-data.json"));
        List<Object> rows = Json.asList(data.get("rows"));
        for (boolean upper : List.of(false, true)) {
            List<String> codes = rows.stream().filter(row -> Boolean.TRUE.equals(Json.at(row, "Активен")))
                    .filter(row -> ((Number) Json.at(row, "Цена")).intValue() >= 150)
                    .filter(row -> !upper || ((Number) Json.at(row, "Цена")).intValue() <= 200)
                    .map(row -> Json.stringAt(row, "Код")).sorted().toList();
            assertEquals(Json.asList(data.get(upper ? "bounded" : "baseline")), codes);
        }
        // Это проверка данных рубрики, а не компиляция или исполнение запроса 1С.
    }

    @Test
    void reviewFixtureProducesRealGitDiffAtTheExpectedLine() throws Exception {
        String file = path("ПроверкаЗаказов");
        try (Git git = Git.init().setDirectory(directory.toFile()).call()) {
            git.getRepository().getConfig().setBoolean("core", null, "autocrlf", false);
            git.getRepository().getConfig().save();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("исходники сценариев").setAuthor("Тест", "test@example.invalid")
                    .setCommitter("Тест", "test@example.invalid").setSign(false).call();
            Files.writeString(directory.resolve(file), resource("states/S11-review.bsl"));
            Tool diff = GitTools.create(workspace, () -> 40_000).stream()
                    .filter(tool -> tool.name().equals("git_diff")).findFirst().orElseThrow();
            String text = diff.call(Map.of("module", file)).text();
            assertTrue(text.contains("-\tВозврат Количество > 0;"), text);
            assertTrue(text.contains("+\tВозврат Количество >= 0;"), text);
            assertEquals("\tВозврат Количество >= 0;", Files.readString(directory.resolve(file)).lines().toList().get(6));
        }
        assertUnchangedExcept(Map.of(file, "states/S11-review.bsl"));
    }

    @Test
    void fixedLongHistoryLoadsAndExceedsCompactionThresholdAtEveryWindow() throws Exception {
        Path historyFile = directory.resolve("history.json");
        Files.writeString(historyFile, resource("long-chat.json"));
        var history = new ChatHistoryFile(historyFile).load();
        assertEquals(41, history.size());
        assertTrue(history.get(0).text().contains("Осталось только округление"));
        for (int window : List.of(8192, 16384, 32768)) {
            ModelConnection connection = new ModelConnection(Protocol.OPENAI_COMPATIBLE, "http://localhost", "test-model", "",
                    2048, "", new TrustSettings(false, Optional.empty()), window);
            ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient());
            assertTrue(provider.compactCount(history) > 0, "окно " + window);
        }
    }

    private Tool.Result edit(String module, String oldText, String newText) throws ToolException {
        return tools.get("edit_module").call(Map.of("module", module, "old_text", oldText, "new_text", newText));
    }

    private void assertUnchangedExcept(Map<String, String> expected) throws Exception {
        for (String module : MODULES) {
            String file = path(module);
            assertEquals(resource(expected.getOrDefault(file, "project/" + file)),
                    Files.readString(directory.resolve(file)), file);
        }
    }

    private static String path(String module) {
        return "src/CommonModules/" + module + "/Module.bsl";
    }

    private static String resource(String name) throws IOException {
        try (var stream = DeveloperScenariosTest.class.getResourceAsStream(ROOT + name)) {
            assertNotNull(stream, name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
