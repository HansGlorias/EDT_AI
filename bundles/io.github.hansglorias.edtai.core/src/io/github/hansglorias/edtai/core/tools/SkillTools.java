package io.github.hansglorias.edtai.core.tools;

import static io.github.hansglorias.edtai.core.tools.ProjectTools.schema;
import static io.github.hansglorias.edtai.core.tools.ProjectTools.string;
import static io.github.hansglorias.edtai.core.tools.ProjectTools.text;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.github.hansglorias.edtai.core.skills.Skill;
import io.github.hansglorias.edtai.core.skills.SkillLibrary;

/**
 * Скиллы для модели: прочитать инструкцию ({@code skill}) и сохранить новую
 * по просьбе пользователя ({@code save_skill}). Список скиллов с описаниями
 * модель видит в системном промпте ({@link SkillLibrary#prompt()}).
 * Проект скиллы не меняют, поэтому доступны и в режиме «только вопросы».
 */
public final class SkillTools {

    private SkillTools() {
    }

    public static List<Tool> create(SkillLibrary library) {
        return List.of(new Read(library), new Save(library));
    }

    private record Read(SkillLibrary library) implements Tool {

        @Override
        public String name() {
            return "skill";
        }

        @Override
        public String description() {
            return "Читает скилл — проверенную инструкцию для типовой задачи (список скиллов — в системном промпте). Вызывай,"
                    + " когда задача пользователя подходит под описание скилла, и следуй инструкции.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema(Map.of("name", string("Имя скилла из списка")), List.of("name"));
        }

        @Override
        public String summary(Map<String, Object> input) {
            return "Читает скилл " + text(input, "name");
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            String name = text(input, "name");
            Skill skill = library.find(name).orElseThrow(() -> new ToolException("Скилла «" + name + "» нет. Есть: "
                    + String.join(", ", library.all().stream().map(Skill::name).toList())));
            return new Result(skill.body(), skill.source().label());
        }
    }

    private record Save(SkillLibrary library) implements Tool {

        @Override
        public String name() {
            return "save_skill";
        }

        @Override
        public String description() {
            return "Сохраняет скилл пользователя (новый или заменяет свой с тем же именем). Вызывай, только когда пользователь"
                    + " просит создать или изменить скилл, и по правилам скилла create-skill. Потом его можно вызвать командой"
                    + " /имя.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema(Map.of("name", string("Имя: латиница в нижнем регистре, цифры и дефис, например query-optimize"),
                    "description", string("Одна строка: когда применять скилл"),
                    "instructions", string("Инструкция в Markdown: шаги, правила, формат ответа")),
                    List.of("name", "description", "instructions"));
        }

        @Override
        public String summary(Map<String, Object> input) {
            return "Сохраняет скилл " + text(input, "name");
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            try {
                Skill skill = library.save(text(input, "name"), text(input, "description"), text(input, "instructions"));
                return new Result("Скилл сохранён: " + skill.location() + ". Вызов: " + skill.command(), skill.command());
            } catch (IllegalArgumentException e) {
                throw new ToolException(e.getMessage());
            } catch (IOException e) {
                throw new ToolException("Не удалось сохранить скилл: " + e.getMessage());
            }
        }
    }
}
