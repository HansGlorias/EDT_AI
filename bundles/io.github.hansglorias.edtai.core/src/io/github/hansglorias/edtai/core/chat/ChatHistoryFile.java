package io.github.hansglorias.edtai.core.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.json.JsonException;

/**
 * Диалог в JSON-файле: чтобы он переживал перезапуск EDT.
 *
 * <p>Формат: {@code {"version":1,"messages":[{"role":"USER","status":"DONE","time":"…","text":"…"}]}}.
 */
public final class ChatHistoryFile {

    static final int VERSION = 1;

    private final Path file;

    public ChatHistoryFile(Path file) {
        this.file = file;
    }

    public Path path() {
        return file;
    }

    /** @return сохранённый диалог; пустой, если файла нет */
    public List<ChatMessage> load() throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        return decode(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Записывает через временный файл, чтобы при сбое не остался обрезанный JSON. */
    public void save(List<ChatMessage> messages) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, encode(messages), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String encode(List<ChatMessage> messages) {
        List<Object> items = new ArrayList<>();
        for (ChatMessage message : messages) {
            items.add(Json.object("role", message.role().name(), "status", message.status().name(),
                    "time", message.time().toString(), "text", message.text()));
        }
        return Json.write(Json.object("version", VERSION, "messages", items));
    }

    /**
     * @throws IOException если файл повреждён
     */
    static List<ChatMessage> decode(String text) throws IOException {
        try {
            Map<String, Object> root = Json.parseObject(text);
            List<ChatMessage> messages = new ArrayList<>();
            for (Object item : Json.asList(root.get("messages"))) {
                String role = Json.stringAt(item, "role");
                String body = Json.stringAt(item, "text");
                if (role == null || body == null) {
                    throw new IOException("В сохранённом сообщении нет роли или текста");
                }
                String status = Json.stringAt(item, "status");
                String time = Json.stringAt(item, "time");
                messages.add(new ChatMessage(ChatRole.valueOf(role), body, time == null ? Instant.EPOCH : Instant.parse(time),
                        status == null ? ChatMessage.Status.DONE : ChatMessage.Status.valueOf(status)));
            }
            return messages;
        } catch (JsonException | IllegalArgumentException | DateTimeParseException e) {
            throw new IOException("Файл диалога повреждён: " + e.getMessage(), e);
        }
    }
}
