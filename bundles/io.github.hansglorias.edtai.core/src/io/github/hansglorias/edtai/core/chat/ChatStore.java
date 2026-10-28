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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Все диалоги: по файлу {@link ChatHistoryFile} на диалог в одном каталоге.
 * Название диалога — начало первого вопроса, порядок — по времени
 * последнего изменения.
 */
public final class ChatStore {

    /** Сколько диалогов хранить: самые старые удаляются. */
    public static final int MAX_CHATS = 100;
    private static final int MAX_TITLE = 60;
    private static final String SUFFIX = ".json";
    private static final String CURRENT = "current.txt";
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

    /** Новый идентификатор: время создания, по нему же сортируются диалоги с одинаковым временем изменения. */
    public String newId() {
        String id = LocalDateTime.now().format(ID);
        while (Files.exists(directory.resolve(id + SUFFIX))) {
            id = id + "-1";
        }
        return id;
    }

    /** Диалоги, последний изменённый — первым. Нечитаемые файлы пропускаются. */
    public List<Entry> list() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(SUFFIX)).toList()) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - SUFFIX.length());
                try {
                    List<ChatMessage> messages = new ChatHistoryFile(file).load();
                    if (!messages.isEmpty()) {
                        entries.add(new Entry(id, title(messages), Files.getLastModifiedTime(file).toInstant()));
                    }
                } catch (IOException e) {
                    // Битый файл не мешает остальным диалогам.
                }
            }
        }
        entries.sort(Comparator.comparing(Entry::updated).thenComparing(Entry::id).reversed());
        return entries;
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
