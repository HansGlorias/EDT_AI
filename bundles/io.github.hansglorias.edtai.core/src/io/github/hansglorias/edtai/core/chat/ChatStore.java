package io.github.hansglorias.edtai.core.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.json.JsonException;

/**
 * Все диалоги: по файлу {@link ChatHistoryFile} на диалог в одном каталоге.
 * Название диалога — начало первого вопроса или данное пользователем
 * (хранится отдельно, в {@code titles.json}: файл диалога переписывается на
 * каждом сообщении), порядок — по времени последнего изменения.
 */
public final class ChatStore {

    /** Сколько диалогов хранить: самые старые удаляются. */
    public static final int MAX_CHATS = 100;
    private static final int MAX_TITLE = 60;
    /** Длина названия, данного пользователем. */
    private static final int MAX_CUSTOM_TITLE = 200;
    private static final String SUFFIX = ".json";
    private static final String CURRENT = "current.txt";
    private static final String TITLES = "titles.json";
    private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    /**
     * Диалог в списке.
     *
     * @param title   начало первого вопроса
     * @param updated когда диалог последний раз менялся
     */
    public record Entry(String id, String title, Instant updated) {
    }

    private final Path directory;

    public ChatStore(Path directory) {
        this.directory = directory;
    }

    /** Файл диалога. */
    public ChatHistoryFile file(String id) {
        return new ChatHistoryFile(directory.resolve(id + SUFFIX));
    }

    /** Новый идентификатор: время создания + случайный суффикс, чтобы два вызова подряд не дали один id. */
    public String newId() {
        String id = LocalDateTime.now().format(ID);
        while (Files.exists(directory.resolve(id + SUFFIX))) {
            id = LocalDateTime.now().format(ID) + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x10000));
        }
        return id;
    }

    /** Диалоги, последний изменённый — первым. Нечитаемые файлы пропускаются. */
    public List<Entry> list() throws IOException {
        return search("");
    }

    /**
     * Диалоги, в названии или сообщениях которых есть все слова запроса (без
     * учёта регистра, «ё» = «е»), последний изменённый — первым.
     *
     * @param query пустой — все диалоги
     */
    public List<Entry> search(String query) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<String> words = Arrays.stream(normalize(query).split("\\s+")).filter(word -> !word.isEmpty()).toList();
        Map<String, String> titles = titles();
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(ChatStore::isChat).toList()) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - SUFFIX.length());
                try {
                    List<ChatMessage> messages = new ChatHistoryFile(file).load();
                    if (!messages.isEmpty()) {
                        String title = titles.containsKey(id) ? titles.get(id) : title(messages);
                        if (matches(words, title, messages)) {
                            entries.add(new Entry(id, title, Files.getLastModifiedTime(file).toInstant()));
                        }
                    }
                } catch (IOException e) {
                    // Битый файл не мешает остальным диалогам.
                }
            }
        }
        entries.sort(Comparator.comparing(Entry::updated).thenComparing(Entry::id).reversed());
        return entries;
    }

    private static boolean isChat(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(SUFFIX) && !name.equals(TITLES);
    }

    private static boolean matches(List<String> words, String title, List<ChatMessage> messages) {
        if (words.isEmpty()) {
            return true;
        }
        StringBuilder text = new StringBuilder(normalize(title));
        for (ChatMessage message : messages) {
            text.append('\n').append(normalize(message.text()));
        }
        return words.stream().allMatch(word -> text.indexOf(word) >= 0);
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    /** Название, данное диалогу (пользователем или придуманное моделью); пусто — по первому вопросу. */
    public Optional<String> customTitle(String id) {
        return Optional.ofNullable(titles().get(id));
    }

    /** Название диалога: данное пользователем или начало первого вопроса. */
    public String title(String id, List<ChatMessage> messages) {
        String custom = titles().get(id);
        return custom != null ? custom : messages.isEmpty() ? "Без названия" : title(messages);
    }

    /**
     * Даёт диалогу название; пустое — снова начало первого вопроса. Время
     * изменения диалога не меняется.
     */
    public void rename(String id, String title) throws IOException {
        String clean = title.strip().replaceAll("\\s+", " ");
        Map<String, String> titles = titles();
        if (clean.isEmpty()) {
            titles.remove(id);
        } else {
            titles.put(id, clean.length() > MAX_CUSTOM_TITLE ? clean.substring(0, MAX_CUSTOM_TITLE) : clean);
        }
        writeTitles(titles);
    }

    /** Названия, данные пользователем, по идентификатору; файл повреждён — как нет названий. */
    private Map<String, String> titles() {
        Map<String, String> titles = new LinkedHashMap<>();
        Path file = directory.resolve(TITLES);
        if (Files.isRegularFile(file)) {
            try {
                Json.parseObject(Files.readString(file, StandardCharsets.UTF_8)).forEach((id, title) -> {
                    if (title instanceof String text) {
                        titles.put(id, text);
                    }
                });
            } catch (IOException | JsonException e) {
                // Остаются названия по первому вопросу.
            }
        }
        return titles;
    }

    private void writeTitles(Map<String, String> titles) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(TITLES), Json.write(titles), StandardCharsets.UTF_8);
    }

    /** Последний изменённый диалог. */
    public Optional<String> latest() throws IOException {
        return list().stream().findFirst().map(Entry::id);
    }

    /** Запоминает открытый диалог: его и откроем при следующем запуске. */
    public void setCurrent(String id) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(CURRENT), id, StandardCharsets.UTF_8);
    }

    /** Диалог, открытый в прошлый раз, если он ещё есть; иначе последний изменённый. */
    public Optional<String> current() throws IOException {
        Path current = directory.resolve(CURRENT);
        if (Files.isRegularFile(current)) {
            String id = Files.readString(current, StandardCharsets.UTF_8).strip();
            if (!id.isEmpty() && Files.isRegularFile(directory.resolve(id + SUFFIX))) {
                return Optional.of(id);
            }
        }
        return latest();
    }

    public void delete(String id) throws IOException {
        Files.deleteIfExists(directory.resolve(id + SUFFIX));
        Map<String, String> titles = titles();
        if (titles.remove(id) != null) {
            writeTitles(titles);
        }
    }

    /** Удаляет самые старые диалоги сверх {@link #MAX_CHATS}. */
    public void prune() throws IOException {
        List<Entry> entries = list();
        for (Entry entry : entries.subList(Math.min(MAX_CHATS, entries.size()), entries.size())) {
            delete(entry.id());
        }
    }

    /**
     * Переносит диалог из старого единственного файла {@code chat.json}, если
     * он есть: так история не теряется при обновлении плагина.
     */
    public void migrate(Path legacy) throws IOException {
        if (Files.isRegularFile(legacy)) {
            Files.createDirectories(directory);
            String id = LocalDateTime.ofInstant(Files.getLastModifiedTime(legacy).toInstant(), ZoneId.systemDefault()).format(ID);
            Files.move(legacy, directory.resolve(id + SUFFIX));
        }
    }

    /** Начало первого вопроса в одну строку. */
    static String title(List<ChatMessage> messages) {
        String text = messages.stream().filter(message -> message.role() == ChatRole.USER).map(ChatMessage::text).findFirst()
                .orElse(messages.get(0).text()).strip().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return "Без названия";
        }
        return text.length() > MAX_TITLE ? text.substring(0, MAX_TITLE) + "…" : text;
    }
}
