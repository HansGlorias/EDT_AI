package io.github.hansglorias.edtai.core.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.tools.SkillTools;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolException;

class SkillLibraryTest {

    @TempDir
    Path temp;

    private SkillLibrary library(Path... projects) {
        return new SkillLibrary(temp.resolve("user"), () -> List.of(projects), Set.of("review", "new"));
    }

    @Test
    void builtInSkillsAreComplete() {
        List<Skill> skills = SkillLibrary.builtIn();
        assertEquals(SkillLibrary.BUILT_IN, skills.stream().map(Skill::name).toList(), "имя в файле совпадает с именем файла");
        for (Skill skill : skills) {
            assertFalse(skill.description().isBlank(), skill.name());
            assertTrue(skill.body().length() > 500, skill.name() + ": инструкция есть");
            assertEquals(Skill.Source.BUILT_IN, skill.source());
        }
        String prompt = library().prompt();
        assertTrue(prompt.startsWith("Скиллы — проверенные инструкции"), prompt);
        assertTrue(prompt.contains("\n- query-optimize — Оптимизация запросов 1С"), prompt);
    }

    @Test
    void disabledSkillsAreHidden() {
        Set<String> off = SkillLibrary.parseDisabled(" HTML-field\n\nposting\n");
        assertEquals(Set.of("html-field", "posting"), off);
        assertEquals("html-field\nposting", SkillLibrary.formatDisabled(off));
        SkillLibrary library = new SkillLibrary(temp.resolve("user"), List::of, Set.of(), () -> off);

        assertEquals(SkillLibrary.BUILT_IN.size(), library.everything().size());
        assertEquals(SkillLibrary.BUILT_IN.size() - 2, library.all().size());
        assertTrue(library.find("posting").isEmpty(), "выключенный не вызвать");
        assertFalse(library.prompt().contains("- posting"), "и модель его не видит");
        assertTrue(library.prompt().contains("- bsl-style — Стиль кода 1С"), library.prompt());
        assertTrue(library.describe().contains("/posting — "), "в списке есть");
        assertTrue(library.describe().contains("(выключен)"));
    }

    @Test
    void userAndProjectSkillsOverrideBuiltIn() throws Exception {
        Path project = Files.createDirectories(temp.resolve("Демо/.edtai/skills"));
        Files.writeString(project.resolve("posting.md"), "---\nname: posting\ndescription: \"Проведение по-нашему\"\n---\nНаша схема.\n",
                StandardCharsets.UTF_8);
        Files.writeString(project.resolve("broken.md"), "без заголовка", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("BadName.md"), "---\nname: Плохое Имя\n---\nтекст", StandardCharsets.UTF_8);
        SkillLibrary library = library(project);

        Skill mine = library.save("Print-Form", "Печатные формы", "Шаг 1.\r\nШаг 2.");
        assertEquals("print-form", mine.name());
        assertEquals("---\nname: print-form\ndescription: Печатные формы\n---\n\nШаг 1.\r\nШаг 2.\n",
                Files.readString(temp.resolve("user/print-form.md")));
        assertEquals("Шаг 1.\nШаг 2.", library.find("print-form").orElseThrow().body(), "переводы строк — \\n");
        library.save("query-optimize", "Свой вариант", "Своё.");

        assertEquals(Skill.Source.USER, library.find("query-optimize").orElseThrow().source(), "свой заменяет встроенный");
        Skill posting = library.find("POSTING").orElseThrow();
        assertEquals(Skill.Source.PROJECT, posting.source(), "скилл проекта заменяет встроенный");
        assertEquals("Проведение по-нашему", posting.description());
        assertEquals(SkillLibrary.BUILT_IN.size() + 1, library.all().size(), "битые и с плохим именем пропущены");
        assertEquals(List.of("platform-gotchas", "posting", "print-form"), library.matching("p").stream().map(Skill::name).toList());

        String described = library.describe();
        assertTrue(described.startsWith("Встроенные:\n  /access-rights — "), described);
        assertTrue(described.contains("Свои (" + temp.resolve("user") + "):\n  /print-form — Печатные формы\n  /query-optimize — Свой"),
                described);
        assertTrue(described.contains("Проекта:\n  /posting — Проведение по-нашему (" + project.resolve("posting.md") + ")"), described);
        assertFalse(described.contains("/query-optimize — Оптимизация"), "заменённый встроенный не показывается");

        assertThrows(IllegalArgumentException.class, () -> library.save("плохое имя", "о", "т"));
        assertThrows(IllegalArgumentException.class, () -> library.save("review", "о", "т"), "встроенная команда");
        assertThrows(IllegalArgumentException.class, () -> library.save("empty", " ", "т"));
    }

    @Test
    void modelReadsAndSavesSkills() throws Exception {
        SkillLibrary library = library();
        Map<String, Tool> tools = Map.of("skill", SkillTools.create(library).get(0), "save_skill", SkillTools.create(library).get(1));
        assertFalse(tools.get("skill").modifies() || tools.get("save_skill").modifies(), "проект не меняют");

        Tool.Result read = tools.get("skill").call(Map.of("name", "method-docs"));
        assertTrue(read.text().startsWith("# Комментарии к методам"), read.text());
        assertEquals("встроенный", read.outcome());
        ToolException missing = assertThrows(ToolException.class, () -> tools.get("skill").call(Map.of("name", "нет")));
        assertTrue(missing.getMessage().contains("Есть: access-rights, bsl-style, client-server"), missing.getMessage());

        Tool.Result saved = tools.get("save_skill").call(Map.of("name", "print-form", "description", "Печатные формы",
                "instructions", "# Печать\nШаги."));
        assertEquals("/print-form", saved.outcome());
        assertEquals("Сохраняет скилл print-form", tools.get("save_skill").summary(Map.of("name", "print-form")));
        assertEquals("# Печать\nШаги.", library.find("print-form").orElseThrow().body());
        assertThrows(ToolException.class, () -> tools.get("save_skill").call(Map.of("name", "new", "description", "о",
                "instructions", "т")));
    }
}
