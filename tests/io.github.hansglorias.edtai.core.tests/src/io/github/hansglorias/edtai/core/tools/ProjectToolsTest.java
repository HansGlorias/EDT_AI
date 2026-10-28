package io.github.hansglorias.edtai.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.json.Json;

class ProjectToolsTest {

    static final String MDO = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mdclass:Configuration xmlns:mdclass="http://g5.1c.ru/v8/dt/metadata/mdclass" uuid="00000000-0000-0000-0000-000000000000">
              <name>Демо</name>
              <synonym>
                <key>en</key>
                <value>Demo</value>
              </synonym>
              <synonym>
                <key>ru</key>
                <value>Демонстрационная</value>
              </synonym>
              <scriptVariant>Russian</scriptVariant>
              <vendor>Пример</vendor>
              <version>1.0.0.1</version>
              <compatibilityMode>8.3.24</compatibilityMode>
              <catalogs>Catalog.Номенклатура</catalogs>
            </mdclass:Configuration>
            """;

    @TempDir
    Path temp;

    private final Map<String, String> unsaved = new HashMap<>();
    private String preferred;
    private int limit = 40_000;
    private Map<String, Tool> tools;

    @BeforeEach
    void createProjects() throws Exception {
        Path demo = temp.resolve("Демо");
        write(demo, "src/Configuration/Configuration.mdo", MDO);
        write(demo, "src/CommonModules/Общий/Module.bsl", "﻿Процедура Сообщить1() Экспорт\n\tСообщить(\"Привет\");\nКонецПроцедуры\n");
        write(demo, "src/Catalogs/Номенклатура/ObjectModule.bsl", "Процедура ПередЗаписью(Отказ)\n\tСООБЩИТЬ(\"Запись\");\nКонецПроцедуры");
        write(demo, "src/Catalogs/Номенклатура/ManagerModule.bsl", "");
        write(demo, "src/Catalogs/Номенклатура/Forms/ФормаЭлемента/Module.bsl", "&НаКлиенте\nПроцедура Открыть()\nКонецПроцедуры\n");
        Files.createDirectories(demo.resolve("src/Documents/Заказ"));
        Files.createDirectories(temp.resolve("Альфа"));
        WorkspaceFiles files = new WorkspaceFiles() {
            @Override
            public List<String> projects() {
                return List.of("Альфа", "Демо");
            }

            @Override
            public Optional<Path> root(String project) {
                return Optional.of(temp.resolve(project));
            }

            @Override
            public Optional<String> preferredProject() {
                return Optional.ofNullable(preferred);
            }

            @Override
            public Optional<String> unsavedText(String project, String path) {
                return Optional.ofNullable(unsaved.get(project + "/" + path));
            }

            @Override
            public Map<String, String> unsavedTexts(String project) {
                Map<String, String> texts = new HashMap<>();
                unsaved.forEach((key, text) -> {
                    if (key.startsWith(project + "/")) {
                        texts.put(key.substring(project.length() + 1), text);
                    }
                });
                return texts;
            }
        };
        tools = new HashMap<>();
        for (Tool tool : ProjectTools.create(files, () -> limit)) {
            tools.put(tool.name(), tool);
        }
    }

    @Test
    void toolsHaveSchemas() {
        assertEquals(List.of("list_objects", "project_info", "read_module", "search_code"), tools.keySet().stream().sorted().toList());
        assertEquals(List.of("module"), tools.get("read_module").inputSchema().get("required"));
        assertEquals("object", tools.get("project_info").inputSchema().get("type"));
    }

    @Test
    void projectInfoPrefersConfigurationProject() throws Exception {
        Tool.Result result = call("project_info");

        assertEquals("Демо 1.0.0.1", result.outcome());
        String text = result.text();
        for (String line : List.of("Проект: Демо", "Проекты рабочей области: Альфа, Демо", "Конфигурация: Демо",
                "Синоним: Демонстрационная", "Версия: 1.0.0.1", "Поставщик: Пример", "Режим совместимости: 8.3.24",
                "Вариант встроенного языка: Russian", "  ОбщийМодуль: 1", "  Справочник: 1", "  Документ: 1")) {
            assertTrue(text.contains(line + "\n") || text.endsWith(line), line + " в\n" + text);
        }

        preferred = "Альфа";
        assertTrue(call("project_info").text().contains("Файла src/Configuration/Configuration.mdo нет"));
        assertTrue(call("project_info", "project", "Демо").text().contains("Конфигурация: Демо"));
        assertThrows(ToolException.class, () -> call("project_info", "project", "Нет"));
    }

    @Test
    void listObjects() throws Exception {
        assertTrue(call("list_objects").text().contains("  Справочник: 1"));
        assertEquals(new Tool.Result("Справочник: 1\nНоменклатура", "1"), call("list_objects", "type", "Catalog"));
        assertEquals("0", call("list_objects", "type", "справочник", "filter", "товар").outcome());
        assertEquals("Документ: 1\nЗаказ", call("list_objects", "type", "Documents").text());
        ToolException error = assertThrows(ToolException.class, () -> call("list_objects", "type", "Вещь"));
        assertTrue(error.getMessage().contains("Справочник, Документ"), error.getMessage());
    }

    @Test
    void readModuleByNameAndPath() throws Exception {
        Tool.Result whole = call("read_module", "module", "ОбщийМодуль.Общий");
        assertEquals("строки 1–3 из 3", whole.outcome());
        assertEquals("ОбщийМодуль.Общий.Модуль (проект Демо, src/CommonModules/Общий/Module.bsl), весь модуль, строки 1–3:\n"
                + "1  Процедура Сообщить1() Экспорт\n2  \tСообщить(\"Привет\");\n3  КонецПроцедуры\n", whole.text());

        Tool.Result line = call("read_module", "module", "Справочник.Номенклатура.Форма.ФормаЭлемента", "first_line", 2, "last_line", 2);
        assertTrue(line.text().endsWith("строки 2–2 из 3:\n2  Процедура Открыть()\n"), line.text());
        assertEquals("строки 1–3 из 3",
                call("read_module", "module", "Демо/src/Catalogs/Номенклатура/ObjectModule.bsl").outcome());
        assertEquals("строки 1–3 из 3", call("read_module", "module", "src\\Catalogs\\Номенклатура\\ObjectModule.bsl").outcome());
    }

    @Test
    void readModuleUsesUnsavedTextAndLimit() throws Exception {
        unsaved.put("Демо/src/CommonModules/Общий/Module.bsl", "А = 1;\nБ = 2;\nВ = 3;\nГ = 4;");
        assertTrue(call("read_module", "module", "ОбщийМодуль.Общий").text().contains("4  Г = 4;"));

        limit = 20;
        Tool.Result cut = call("read_module", "module", "ОбщийМодуль.Общий");
        assertEquals("строки 1–2 из 4", cut.outcome());
        assertTrue(cut.text().endsWith("(дальше не поместилось в лимит: продолжение — first_line=3)\n"), cut.text());
    }

    @Test
    void readModuleExplainsWhatIsMissing() {
        assertTrue(message("read_module", "module", "Справочник.Номенклатура.МодульНабораЗаписей").contains(
                "У объекта есть: Справочник.Номенклатура.Форма.ФормаЭлемента.Модуль, Справочник.Номенклатура.МодульМенеджера,"
                        + " Справочник.Номенклатура.МодульОбъекта"));
        assertTrue(message("read_module", "module", "Документ.Заказ").contains("У объекта нет модулей"));
        assertTrue(message("read_module", "module", "Справочник.Товары").startsWith("Объекта Справочник.Товары в проекте нет"));
        assertTrue(message("read_module", "module", "Что-то").startsWith("Не понял имя модуля"));
        assertEquals("Путь вне проекта: ../Альфа/x.bsl", message("read_module", "module", "../Альфа/x.bsl"));
        assertEquals("В модуле строк: 3, строки 10 нет", message("read_module", "module", "ОбщийМодуль.Общий", "first_line", 10));
    }

    @Test
    void searchCodeIgnoresCase() throws Exception {
        Tool.Result found = call("search_code", "query", "сообщить(");

        assertEquals("найдено 2", found.outcome());
        assertEquals("«сообщить(»: совпадений — 2, модулей — 2\n"
                + "Справочник.Номенклатура.МодульОбъекта:2: СООБЩИТЬ(\"Запись\");\n"
                + "ОбщийМодуль.Общий.Модуль:2: Сообщить(\"Привет\");", found.text());
        assertEquals("найдено 1", call("search_code", "query", "сообщить(", "type", "ОбщийМодуль").outcome());
        assertTrue(call("search_code", "query", "Процедура", "max_results", 1).text().contains(", показаны первые 1\n"));
        assertEquals("Не задано, что искать (query)", message("search_code", "query", " "));
    }

    @Test
    void searchCodeSeesUnsavedChanges() throws Exception {
        unsaved.put("Демо/src/CommonModules/Общий/Module.bsl", "Процедура Сообщить1() Экспорт\n\tНовое = ИзРедактора;\nКонецПроцедуры\n");

        assertEquals("«изредактора»: совпадений — 1, модулей — 1\nОбщийМодуль.Общий.Модуль:2: Новое = ИзРедактора;",
                call("search_code", "query", "изредактора").text());
        assertEquals("найдено 1", call("search_code", "query", "сообщить(").outcome(), "старый текст модуля не ищется");
    }

    @Test
    void configurationInfoOfExtension() throws Exception {
        ConfigurationInfo info = ConfigurationInfo.parse("<Configuration xmlns='x'><name>Расш</name><namePrefix>Расш_</namePrefix>"
                + "<configurationExtensionPurpose>Customization</configurationExtensionPurpose></Configuration>");

        assertTrue(info.isExtension());
        assertEquals(List.of("Расширение: Расш", "Префикс имён расширения: Расш_", "Назначение расширения: Customization"),
                info.describe());
        assertThrows(java.io.IOException.class, () -> ConfigurationInfo.parse("не xml"));
        assertThrows(java.io.IOException.class, () -> ConfigurationInfo.parse(
                "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"));
    }

    private Tool.Result call(String tool, Object... arguments) throws ToolException {
        return tools.get(tool).call(Json.object(arguments));
    }

    private String message(String tool, Object... arguments) {
        return assertThrows(ToolException.class, () -> call(tool, arguments)).getMessage();
    }

    private static void write(Path project, String path, String text) throws Exception {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
