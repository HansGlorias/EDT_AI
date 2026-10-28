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

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitToolsTest {

    private static final String MODULE = "src/CommonModules/Общий/Module.bsl";

    @TempDir
    Path temp;

    private final Map<String, Tool> tools = new HashMap<>();
    private RevCommit first;

    @BeforeEach
    void createRepository() throws Exception {
        // Проект — подкаталог репозитория, как часто бывает с проектами EDT.
        Path project = temp.resolve("repo/Демо");
        write(project.resolve(MODULE), "Процедура Сообщить1() Экспорт\n\tСообщить(\"Привет\");\nКонецПроцедуры\n");
        Files.createDirectories(temp.resolve("Вне"));
        try (Git git = Git.init().setDirectory(temp.resolve("repo").toFile()).call()) {
            // Глобальный ~/.gitconfig машины не должен влиять на тест (подпись коммитов, gpg.format=ssh).
            StoredConfig config = git.getRepository().getConfig();
            config.setString("gpg", null, "format", "openpgp");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            git.add().addFilepattern(".").call();
            first = commit(git, "Первый модуль");
            write(project.resolve(MODULE), "Процедура Сообщить1() Экспорт\n\tСообщить(\"Пока\");\nКонецПроцедуры\n");
            git.add().addFilepattern(".").call();
            commit(git, "Прощание вместо приветствия");
        }
        write(project.resolve(MODULE), "Процедура Сообщить1() Экспорт\n\tСообщить(\"Снова привет\");\nКонецПроцедуры\n");
        write(project.resolve("src/CommonModules/Новый/Module.bsl"), "// новый\n");
        WorkspaceFiles files = new WorkspaceFiles() {
            @Override
            public List<String> projects() {
                return List.of("Вне", "Демо");
            }

            @Override
            public Optional<Path> root(String name) {
                return Optional.of(name.equals("Демо") ? project : temp.resolve(name));
            }

            @Override
            public Optional<String> preferredProject() {
                return Optional.of("Демо");
            }
        };
        for (Tool tool : GitTools.create(files, () -> 40_000)) {
            tools.put(tool.name(), tool);
        }
    }

    @Test
    void statusShowsChangedAndNewModules() throws Exception {
        Tool.Result status = tools.get("git_status").call(Map.of());

        assertTrue(status.text().contains("последний коммит") && status.text().contains("Прощание вместо приветствия"), status.text());
        assertTrue(status.text().contains("Изменены (1):\n  ОбщийМодуль.Общий.Модуль (" + MODULE + ")"), status.text());
        assertTrue(status.text().contains("Новые (1):\n  ОбщийМодуль.Новый.Модуль (src/CommonModules/Новый/Module.bsl)"), status.text());
        assertEquals("изменено файлов: 2", status.outcome());
    }

    @Test
    void diffOfWorkingCopyAndOfCommit() throws Exception {
        Tool.Result working = tools.get("git_diff").call(Map.of("module", "ОбщийМодуль.Общий"));
        assertTrue(working.text().contains("-\tСообщить(\"Пока\");\n+\tСообщить(\"Снова привет\");"), working.text());
        assertTrue(working.text().contains("Демо/" + MODULE), working.text());
        assertEquals("файлов: 1", working.outcome(), "неотслеживаемый новый модуль в дифф не входит");

        Tool.Result commit = tools.get("git_diff").call(Map.of("commit", first.name().substring(0, 8)));
        assertTrue(commit.text().startsWith("Коммит " + first.abbreviate(7).name()), commit.text());
        assertTrue(commit.text().contains("+\tСообщить(\"Привет\");"), commit.text());

        assertEquals("Коммит «нет-такого» не найден",
                assertThrows(ToolException.class, () -> tools.get("git_diff").call(Map.of("commit", "нет-такого"))).getMessage());
    }

    @Test
    void logOfModule() throws Exception {
        Tool.Result log = tools.get("git_log").call(Map.of("module", MODULE, "max_count", 1));

        assertEquals("коммитов: 1", log.outcome());
        assertTrue(log.text().matches("[0-9a-f]{7} \\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d Тестов: Прощание вместо приветствия"), log.text());
        assertEquals("коммитов: 2", tools.get("git_log").call(Map.of()).outcome());
    }

    @Test
    void projectOutsideRepository() {
        ToolException error = assertThrows(ToolException.class, () -> tools.get("git_status").call(Map.of("project", "Вне")));
        assertEquals("Проект «Вне» не в git-репозитории", error.getMessage());
    }

    private static RevCommit commit(Git git, String message) throws Exception {
        return git.commit().setMessage(message).setAuthor("Тестов", "test@example.invalid")
                .setCommitter("Тестов", "test@example.invalid").setSign(false).call();
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
