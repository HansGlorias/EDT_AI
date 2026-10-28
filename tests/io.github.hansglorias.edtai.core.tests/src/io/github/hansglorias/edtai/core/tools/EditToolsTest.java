package io.github.hansglorias.edtai.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditToolsTest {

    private static final String MODULE = "src/CommonModules/Общий/Module.bsl";

    @TempDir
    Path temp;

    private Tool edit;

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
        }).get(0);
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

    private String message(Map<String, Object> input) {
        return assertThrows(ToolException.class, () -> edit.call(input)).getMessage();
    }
}
