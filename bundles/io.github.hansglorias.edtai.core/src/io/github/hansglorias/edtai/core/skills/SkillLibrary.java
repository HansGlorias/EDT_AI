package io.github.hansglorias.edtai.core.skills;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Все скиллы: встроенные (в плагине, эталонные), свои (в рабочей области,
 * их же создаёт модель по просьбе) и проекта ({@code <проект>/.edtai/skills},
 * лежат в git и общие для команды). При одинаковом имени свой заменяет
 * встроенный, а скилл проекта — оба. Файлы читаются заново при каждом
 * обращении: правку файла видно сразу.
 */
public final class SkillLibrary {

    /** Встроенные скиллы: файлы {@code builtin/<имя>.md} рядом с классом. */
    public static final List<String> BUILT_IN = List.of("create-skill", "query-optimize", "code-standards", "posting",
            "client-server", "method-docs");
    /** Каталог скиллов внутри проекта. */
    public static final String PROJECT_DIRECTORY = ".edtai/skills";

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    private static final String SUFFIX = ".md";
    private static final int MAX_BODY = 20_000;

    private final Path userDirectory;
    private final Supplier<List<Path>> projectDirectories;
    private final Set<String> reserved;

    /**
     * @param userDirectory      свои скиллы
     * @param projectDirectories каталоги скиллов открытых проектов
     * @param reserved           имена встроенных команд: скилл с таким именем не вызвать командой
     */
    public SkillLibrary(Path userDirectory, Supplier<List<Path>> projectDirectories, Set<String> reserved) {
        this.userDirectory = userDirectory;
        this.projectDirectories = projectDirectories;
        this.reserved = reserved;
    }

    public Path userDirectory() {
        return userDirectory;
    }

    /** Скиллы по имени; нечитаемые и без имени пропускаются. */
    public List<Skill> all() {
        Map<String, Skill> skills = new LinkedHashMap<>();
        builtIn().forEach(skill -> skills.put(skill.name(), skill));
        read(userDirectory, Skill.Source.USER).forEach(skill -> skills.put(skill.name(), skill));
        for (Path directory : projectDirectories.get()) {
            read(directory, Skill.Source.PROJECT).forEach(skill -> skills.put(skill.name(), skill));
        }
        return skills.values().stream().sorted(Comparator.comparing(Skill::name)).toList();
    }

    public Optional<Skill> find(String name) {
        String key = name.strip().toLowerCase(Locale.ROOT);
        return all().stream().filter(skill -> skill.name().equals(key)).findFirst();
    }

    /** Скиллы, чьё имя начинается с {@code prefix}: для подсказок в поле ввода. */
    public List<Skill> matching(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return all().stream().filter(skill -> skill.name().startsWith(lower)).toList();
    }

    /**
     * Сохраняет свой скилл; такой уже есть — заменяется.
     *
     * @throws IllegalArgumentException имя не подходит или нет описания или инструкции
     */
    public Skill save(String name, String description, String body) throws IOException {
        String key = name.strip().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(key).matches()) {
            throw new IllegalArgumentException("Имя скилла — латиница в нижнем регистре, цифры и дефис, до 40 символов: «"
                    + name + "»");
        }
        if (reserved.contains(key)) {
            throw new IllegalArgumentException("«" + key + "» — встроенная команда, выберите другое имя");
        }
        if (description.isBlank() || body.isBlank()) {
            throw new IllegalArgumentException("Нужны описание (когда применять) и инструкция");
        }
        if (body.length() > MAX_BODY) {
            throw new IllegalArgumentException("Инструкция длиннее " + MAX_BODY / 1000 + " тыс. символов — сократите");
        }
        Files.createDirectories(userDirectory);
        Path file = userDirectory.resolve(key + SUFFIX);
        Skill skill = new Skill(key, description, body, Skill.Source.USER, file.toString());
        Files.writeString(file, skill.toMarkdown(), StandardCharsets.UTF_8);
        return skill;
    }

    /**
     * Для системного промпта: список скиллов с описаниями; модель читает
     * нужный инструментом {@code skill}. Пусто — скиллов нет.
     */
    public String prompt() {
        List<Skill> skills = all();
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("Скиллы — проверенные инструкции для типовых задач. Если задача пользователя"
                + " подходит под скилл, сначала прочитай его инструментом skill и следуй ему. Если пользователь просит"
                + " создать или изменить скилл — сначала прочитай скилл create-skill, потом save_skill.\n");
        for (Skill skill : skills) {
            text.append("- ").append(skill.name()).append(" — ").append(skill.description()).append('\n');
        }
        return text.toString().strip();
    }

    /** Для команды {@code /skills}: скиллы по источникам и как создать свой. */
    public String describe() {
        List<Skill> skills = all();
        StringBuilder text = new StringBuilder();
        for (Skill.Source source : Skill.Source.values()) {
            List<Skill> group = skills.stream().filter(skill -> skill.source() == source).toList();
            if (group.isEmpty()) {
                continue;
            }
            text.append(switch (source) {
                case BUILT_IN -> "Встроенные";
                case USER -> "Свои (" + userDirectory + ")";
                case PROJECT -> "Проекта";
            }).append(":\n");
            for (Skill skill : group) {
                text.append("  ").append(skill.command()).append(" — ").append(skill.description());
                if (source == Skill.Source.PROJECT) {
                    text.append(" (").append(skill.location()).append(')');
                }
                text.append('\n');
            }
            text.append('\n');
        }
        text.append("Создать свой: /skill и описание — модель напишет инструкцию и сохранит. Скилл проекта (общий для команды"
                + " через git) — файл .md в каталоге ").append(PROJECT_DIRECTORY).append(" проекта, в том же формате.");
        return text.toString();
    }

    /** Встроенные скиллы из ресурсов плагина. */
    public static List<Skill> builtIn() {
        List<Skill> skills = new ArrayList<>();
        for (String name : BUILT_IN) {
            try (InputStream in = SkillLibrary.class.getResourceAsStream("builtin/" + name + SUFFIX)) {
                if (in == null) {
                    throw new IllegalStateException("Нет встроенного скилла " + name);
                }
                skills.add(parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), Skill.Source.BUILT_IN,
                        Skill.Source.BUILT_IN.label()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return skills;
    }

    private static List<Skill> read(Path directory, Skill.Source source) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        List<Skill> skills = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(SUFFIX)).sorted().toList()) {
                try {
                    Skill skill = parse(Files.readString(file, StandardCharsets.UTF_8), source, file.toString());
                    if (NAME.matcher(skill.name()).matches()) {
                        skills.add(skill);
                    }
                } catch (IOException | IllegalArgumentException e) {
                    // Битый файл не мешает остальным скиллам.
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return skills;
    }

    /**
     * Файл скилла: заголовок между строками {@code ---} с {@code name:} и
     * {@code description:}, дальше — инструкция.
     *
     * @throws IllegalArgumentException нет заголовка или имени
     */
    public static Skill parse(String text, Skill.Source source, String location) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("﻿")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.startsWith("---\n")) {
            throw new IllegalArgumentException("Нет заголовка ---");
        }
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            throw new IllegalArgumentException("Заголовок не закрыт ---");
        }
        Map<String, String> header = new LinkedHashMap<>();
        for (String line : normalized.substring(4, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                header.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), unquote(line.substring(colon + 1).strip()));
            }
        }
        String name = header.getOrDefault("name", "").toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("В заголовке нет name");
        }
        int bodyStart = normalized.indexOf('\n', end + 1);
        String body = bodyStart < 0 ? "" : normalized.substring(bodyStart + 1);
        return new Skill(name, header.getOrDefault("description", ""), body, source, location);
    }

    private static String unquote(String value) {
        return value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))
                ? value.substring(1, value.length() - 1)
                : value;
    }
}
