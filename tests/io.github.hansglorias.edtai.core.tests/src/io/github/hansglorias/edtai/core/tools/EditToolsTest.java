package io.github.hansglorias.edtai.core.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
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

import io.github.hansglorias.edtai.core.chat.ToolUse;

class EditToolsTest {

    private static final String MODULE = "src/CommonModules/Общий/Module.bsl";

    @TempDir
    Path temp;

    private Tool edit;
    private int editCalls;
    private final List<EditTools.Change> changes = new ArrayList<>();

    @BeforeEach
    void createProject() throws Exception {
        Path module = temp.resolve("Демо").resolve(MODULE);
        Files.createDirectories(module.getParent());
        // Как в EDT: BOM и переводы строк Windows.
        Files.writeString(module, "﻿Процедура А()\r\n\tВозврат;\r\nКонецПроцедуры\r\n\r\nПроцедура Б()\r\n\tВозврат;\r\nКонецПроцедуры\r\n",
                StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("Демо/src/Configuration.mdo"), "<x/>");
        edit = EditTools.create(new WorkspaceFiles() {
            @Override
            public List<String> projects() {
                return List.of("Демо");
            }

            @Override
            public Optional<Path> root(String project) {
                return Optional.of(temp.resolve(project));
            }

            @Override
            public Edit edit(String project, String path, TextChange change) throws ToolException {
                editCalls++;
                return WorkspaceFiles.super.edit(project, path, change);
            }
        }, changes::add).get(0);
    }

    @Test
    void replacesUniqueFragmentKeepingLineEndingsAndBom() throws Exception {
        Tool.Result result = edit.call(Map.of("module", "ОбщийМодуль.Общий", "old_text", "Процедура Б()\n\tВозврат;",
                "new_text", "Процедура Б()\n\tСообщить(\"Б\");\n\tВозврат;"));

        assertEquals("Изменено: ОбщийМодуль.Общий.Модуль, строки 5–7; файл сохранён.", result.text());
        assertEquals("строки 5–7", result.outcome());
        assertEquals("﻿Процедура А()\r\n\tВозврат;\r\nКонецПроцедуры\r\n\r\nПроцедура Б()\r\n\tСообщить(\"Б\");\r\n\tВозврат;\r\n"
                + "КонецПроцедуры\r\n", Files.readString(temp.resolve("Демо").resolve(MODULE), StandardCharsets.UTF_8));
        assertEquals("edit_module", edit.name());
        assertTrue(edit.modifies(), "в режиме «только вопросы» не передаётся");
        assertEquals(List.of("module", "old_text", "new_text"), edit.inputSchema().get("required"));
    }

    @Test
    void ambiguousMissingAndForbiddenEdits() {
        assertTrue(message(Map.of("module", MODULE, "old_text", "\tВозврат;", "new_text", "")).contains("встречается в модуле 2 раз"));
        assertTrue(message(Map.of("module", MODULE, "old_text", "2  \tВозврат;", "new_text", "")).contains("без номеров строк"));
        assertTrue(message(Map.of("module", "src/Configuration.mdo", "old_text", "<x/>", "new_text", "")).startsWith(
                "Менять можно только модули .bsl"));
        assertTrue(message(Map.of("module", "ОбщийМодуль.Нет", "old_text", "а", "new_text", "б")).startsWith("Модуля "));
    }

    @Test
    void invalidArgumentsNeverReachEditorOrChangeFile() throws Exception {
        byte[] before = Files.readAllBytes(temp.resolve("Демо").resolve(MODULE));
        for (String field : List.of("module", "old_text", "new_text", "project")) {
            for (Object value : Arrays.asList(null, 42, true, List.of(), Map.of())) {
                Map<String, Object> input = validInput();
                input.put(field, value);
                assertBadArgument(input, field);
            }
            if (!field.equals("project")) {
                Map<String, Object> input = validInput();
                input.remove(field);
                assertBadArgument(input, field);
            }
        }
        Map<String, Object> blankModule = validInput();
        blankModule.put("module", " \t");
        assertBadArgument(blankModule, "module");
        Map<String, Object> emptyOldText = validInput();
        emptyOldText.put("old_text", "");
        assertBadArgument(emptyOldText, "old_text");

        assertEquals(0, editCalls, "даже буфер редактора не должен открываться на правку");
        assertTrue(changes.isEmpty(), "нет уведомления о правке");
        assertArrayEquals(before, Files.readAllBytes(temp.resolve("Демо").resolve(MODULE)));
    }

    @Test
    void explicitEmptyNewTextDeletesFragmentWithLfAndCrLf() throws Exception {
        Path file = temp.resolve("Демо").resolve(MODULE);
        for (String delimiter : List.of("\n", "\r\n")) {
            String before = "\uFEFFПервая" + delimiter + "\tУдалить;" + delimiter + "Последняя" + delimiter;
            Files.writeString(file, before, StandardCharsets.UTF_8);
            Tool.Result result = edit.call(Map.of("module", MODULE, "old_text", "\tУдалить;\n", "new_text", ""));
            assertEquals("\uFEFFПервая" + delimiter + "Последняя" + delimiter, Files.readString(file));
            assertTrue(result.outcome().contains("удалено"));
        }
        assertEquals(2, changes.size());
    }

    @Test
    void whitespaceFragmentsAndReplacementAreNotTrimmed() throws Exception {
        Path file = temp.resolve("Демо").resolve(MODULE);
        Files.writeString(file, "А\n \t\nБ\n");
        edit.call(Map.of("module", MODULE, "old_text", " \t", "new_text", "  \t "));
        assertEquals("А\n  \t \nБ\n", Files.readString(file));
    }

    private Map<String, Object> validInput() {
        return new HashMap<>(Map.of("module", MODULE, "old_text", "Процедура Б()", "new_text", "Процедура В()"));
    }

    private void assertBadArgument(Map<String, Object> input, String field) {
        ToolException error = assertThrows(ToolException.class, () -> edit.call(input));
        assertEquals(ToolUse.Problem.BAD_ARGUMENTS, error.problem());
        assertTrue(error.getMessage().contains("«" + field + "»"), error.getMessage());
    }

    private String message(Map<String, Object> input) {
        return assertThrows(ToolException.class, () -> edit.call(input)).getMessage();
    }
}
