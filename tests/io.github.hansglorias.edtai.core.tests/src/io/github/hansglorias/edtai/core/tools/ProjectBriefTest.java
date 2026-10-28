package io.github.hansglorias.edtai.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectBriefTest {

    @TempDir
    Path temp;

    @Test
    void describesConfigurationAndCursor() throws Exception {
        Path demo = temp.resolve("Демо");
        Files.createDirectories(demo.resolve("src/Configuration"));
        Files.writeString(demo.resolve("src/Configuration/Configuration.mdo"), ProjectToolsTest.MDO);
        Path module = demo.resolve("src/CommonModules/Общий/Module.bsl");
        Files.createDirectories(module.getParent());
        Files.writeString(module, "Процедура А()\nКонецПроцедуры\n\nПроцедура Б()\n\tВозврат;\nКонецПроцедуры\n");

        assertEquals("""
                Среда: 1C:EDT. Проект в IDE: Демо.
                Конфигурация: Демо; Синоним: Демонстрационная; Версия: 1.0.0.1; Поставщик: Пример; Режим совместимости: 8.3.24;\
                 Вариант встроенного языка: Russian.
                Открыт в редакторе: ОбщийМодуль.Общий.Модуль (src/CommonModules/Общий/Module.bsl), курсор в методе Б, строка 5.""",
                ProjectBrief.describe(files("Демо", "src/CommonModules/Общий/Module.bsl", 5)));
        assertEquals("Среда: 1C:EDT. Проект в IDE: Демо.\nКонфигурация: Демо; Синоним: Демонстрационная; Версия: 1.0.0.1;"
                + " Поставщик: Пример; Режим совместимости: 8.3.24; Вариант встроенного языка: Russian.",
                ProjectBrief.describe(files(null, null, 0)), "без редактора — проект конфигурации");
        assertEquals("", ProjectBrief.describe(new WorkspaceFiles() {
            @Override
            public List<String> projects() {
                return List.of();
            }

            @Override
            public Optional<Path> root(String project) {
                return Optional.empty();
            }
        }));
    }

    private WorkspaceFiles files(String project, String active, int line) {
        return new WorkspaceFiles() {
            @Override
            public List<String> projects() {
                return List.of("Демо");
            }

            @Override
            public Optional<Path> root(String name) {
                return Optional.of(temp.resolve(name));
            }

            @Override
            public Optional<String> preferredProject() {
                return Optional.ofNullable(project);
            }

            @Override
            public Optional<String> activeFile() {
                return Optional.ofNullable(active);
            }

            @Override
            public int activeLine() {
                return line;
            }
        };
    }
}
