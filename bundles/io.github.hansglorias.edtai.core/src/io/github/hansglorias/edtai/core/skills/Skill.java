package io.github.hansglorias.edtai.core.skills;

import java.util.Objects;

/**
 * Скилл — готовая инструкция для типовой задачи: как оптимизировать
 * запрос, как проверить модуль на стандарты. Файл Markdown с заголовком
 * {@code name} и {@code description}; модель видит список описаний и читает
 * инструкцию, когда задача подходит, пользователь вызывает его командой
 * {@code /имя}.
 *
 * @param name        имя для команды: латиница, цифры и дефис
 * @param description когда применять — по нему модель выбирает скилл
 * @param body        инструкция
 * @param location    откуда загружен: путь файла или «встроенный»
 */
public record Skill(String name, String description, String body, Source source, String location) {

    /** Откуда скилл; при одинаковых именах побеждает более поздний в этом порядке. */
    public enum Source {
        BUILT_IN("встроенный"), USER("свой"), PROJECT("проекта");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public Skill {
        Objects.requireNonNull(name, "name");
        description = Objects.requireNonNullElse(description, "").strip();
        body = Objects.requireNonNullElse(body, "").strip();
        Objects.requireNonNull(source, "source");
        location = Objects.requireNonNullElse(location, "");
    }

    /** Команда в поле ввода: {@code /query-optimize}. */
    public String command() {
        return "/" + name;
    }

    /** Файл скилла: заголовок с именем и описанием, затем инструкция. */
    public String toMarkdown() {
        return "---\nname: " + name + "\ndescription: " + description.replace('\n', ' ') + "\n---\n\n" + body + "\n";
    }
}
