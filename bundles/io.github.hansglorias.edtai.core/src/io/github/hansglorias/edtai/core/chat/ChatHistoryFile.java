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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.hansglorias.edtai.core.context.ContextItem;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.json.JsonException;

/**
 * Диалог в JSON-файле: чтобы он переживал перезапуск EDT.
 *
 * <p>Формат: {@code {"version":1,"messages":[{"role":"USER","status":"DONE","time":"…","text":"…","context":[…]}]}}.
 * Поля {@code context} (фрагменты кода сообщения), {@code attachments}
 * (файлы и скриншоты, картинки — в base64), {@code tools} (вызовы
 * инструментов в ответе) и {@code stats} (время и токены законченного ответа)
 * необязательные: файлы без них читаются как есть.
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
            if (message.isEmptyAnswer()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>(Json.object("role", message.role().name(), "status",
                    message.status().name(), "time", message.time().toString(), "text", message.text()));
            if (!message.context().isEmpty()) {
                List<Object> context = new ArrayList<>();
                for (ContextItem part : message.context()) {
                    context.add(Json.object("project", part.project(), "path", part.path(), "object", part.objectName(),
                            "firstLine", part.firstLine(), "lastLine", part.lastLine(), "totalLines", part.totalLines(),
                            "omittedLines", part.omittedLines(), "text", part.text()));
                }
                item.put("context", context);
            }
            if (!message.attachments().isEmpty()) {
                List<Object> files = new ArrayList<>();
                for (Attachment file : message.attachments()) {
                    files.add(Json.object("name", file.name(), "mediaType", file.mediaType(), "data", file.data()));
                }
                item.put("attachments", files);
            }
            if (!message.tools().isEmpty()) {
                List<Object> tools = new ArrayList<>();
                for (ToolUse use : message.tools()) {
                    Map<String, Object> saved = new LinkedHashMap<>(Json.object("id", use.id(), "name", use.name(),
                            "summary", use.summary(), "outcome", use.outcome(), "status", use.status().name()));
                    if (use.at() >= 0) {
                        saved.put("at", use.at());
                    }
                    if (use.problem() != null) {
                        saved.put("problem", use.problem().name());
                    }
                    if (use.usage() != null) {
                        saved.put("tokens", encode(use.usage()));
                    }
                    tools.add(saved);
                }
                item.put("tools", tools);
            }
            if (message.replyModel() != null) {
                item.put("replyModel", Json.object("protocol", message.replyModel().protocol(), "model", message.replyModel().model()));
            }
            ReplyStats stats = message.stats();
            if (stats != null && stats.finished() != null) {
                Map<String, Object> saved = new LinkedHashMap<>(Json.object("started", stats.started().toString(),
                        "finished", stats.finished().toString(), "waiting", stats.waiting(), "reasoning", stats.reasoning(),
                        "writing", stats.writing(), "tools", stats.tools()));
                if (stats.tokens() != null) {
                    saved.put("tokens", encode(stats.tokens()));
                }
                if (!stats.calls().isEmpty()) {
                    saved.put("calls", new LinkedHashMap<String, Object>(new java.util.TreeMap<>(stats.calls())));
                }
                item.put("stats", saved);
            }
            items.add(item);
        }
        return Json.write(Json.object("version", VERSION, "messages", items));
    }

    private static Map<String, Object> encode(TokenUsage usage) {
        return Json.object("input", usage.input(), "output", usage.output(), "estimated", usage.estimated(),
                "context", usage.context(), "inputSource", usage.inputSource().name(), "outputSource",
                usage.outputSource().name(), "contextSource", usage.contextSource().name());
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
                        status == null ? ChatMessage.Status.DONE : ChatMessage.Status.valueOf(status), context(item), tools(item),
                        attachments(item), stats(item), replyModel(item)));
            }
            return messages;
        } catch (JsonException | IllegalArgumentException | DateTimeParseException e) {
            throw new IOException("Файл диалога повреждён: " + e.getMessage(), e);
        }
    }

    private static List<ContextItem> context(Object message) throws IOException {
        List<ContextItem> context = new ArrayList<>();
        for (Object part : Json.asList(Json.at(message, "context"))) {
            String project = Json.stringAt(part, "project");
            String path = Json.stringAt(part, "path");
            String text = Json.stringAt(part, "text");
            if (project == null || path == null || text == null) {
                throw new IOException("В сохранённом фрагменте кода нет проекта, пути или текста");
            }
            context.add(new ContextItem(project, path, Json.stringAt(part, "object"), number(part, "firstLine"),
                    number(part, "lastLine"), number(part, "totalLines"), text, number(part, "omittedLines")));
        }
        return context;
    }

    private static List<ToolUse> tools(Object message) throws IOException {
        List<ToolUse> tools = new ArrayList<>();
        for (Object use : Json.asList(Json.at(message, "tools"))) {
            String name = Json.stringAt(use, "name");
            if (name == null) {
                throw new IOException("В сохранённом вызове инструмента нет имени");
            }
            String status = Json.stringAt(use, "status");
            tools.add(new ToolUse(Json.stringAt(use, "id"), name, Json.stringAt(use, "summary"), Json.stringAt(use, "outcome"),
                    status == null ? ToolUse.Status.DONE : ToolUse.Status.valueOf(status),
                    Json.at(use, "at") instanceof Number at ? at.intValue() : -1, null, tokens(use), problem(use)));
        }
        return tools;
    }

    /** Старые или неполные сведения о модели не восстанавливаются из текущих настроек. */
    private static ReplyModel replyModel(Object message) {
        Object saved = Json.asObject(message).get("replyModel");
        String protocol = Json.stringAt(saved, "protocol");
        String model = Json.stringAt(saved, "model");
        return protocol == null || model == null ? null : new ReplyModel(protocol, model);
    }

    /** Время и токены ответа; повреждённые или старые — без них, диалог всё равно загружается. */
    private static ReplyStats stats(Object message) {
        Map<String, Object> saved = Json.asObject(Json.at(message, "stats"));
        if (saved == null) return null;
        try {
            Map<String, Long> calls = new LinkedHashMap<>();
            Map<String, Object> savedCalls = Json.asObject(saved.get("calls"));
            if (savedCalls != null) {
                for (Map.Entry<String, Object> call : savedCalls.entrySet()) {
                    calls.put(call.getKey(), tokenNumber(call.getValue()));
                }
            }
            return new ReplyStats(Instant.parse((String) saved.get("started")), Instant.parse((String) saved.get("finished")),
                    tokenNumber(saved.get("waiting")), tokenNumber(saved.get("reasoning")), tokenNumber(saved.get("writing")),
                    tokenNumber(saved.get("tools")), tokens(saved), calls);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Вид ошибки вызова; старые диалоги и неизвестные значения — без него. */
    private static ToolUse.Problem problem(Object use) {
        String name = Json.stringAt(use, "problem");
        for (ToolUse.Problem problem : ToolUse.Problem.values()) {
            if (problem.name().equals(name)) {
                return problem;
            }
        }
        return null;
    }

    /** Повреждённые метрики не мешают загрузить сам диалог и результаты инструментов. */
    private static TokenUsage tokens(Object use) {
        java.util.Map<String, Object> saved = Json.asObject(Json.at(use, "tokens"));
        if (saved == null) return null;
        try {
            long input = tokenNumber(saved.get("input"));
            long output = tokenNumber(saved.get("output"));
            TokenUsage legacy = TokenUsage.step(input, output, Boolean.TRUE.equals(saved.get("estimated")));
            boolean sources = saved.containsKey("inputSource") || saved.containsKey("outputSource") || saved.containsKey("contextSource");
            return new TokenUsage(input, output,
                    saved.containsKey("context") ? tokenNumber(saved.get("context")) : legacy.context(),
                    source(saved, "inputSource", sources ? TokenUsage.Source.UNKNOWN : legacy.inputSource()),
                    source(saved, "outputSource", sources ? TokenUsage.Source.UNKNOWN : legacy.outputSource()),
                    source(saved, "contextSource", sources ? TokenUsage.Source.UNKNOWN : legacy.contextSource()));
        } catch (IllegalArgumentException | ArithmeticException e) {
            return null;
        }
    }

    private static long tokenNumber(Object value) {
        long number;
        if (value instanceof Long integer) number = integer;
        else if (value instanceof java.math.BigDecimal decimal) number = decimal.longValueExact();
        else throw new IllegalArgumentException("Не число токенов");
        if (number < 0) throw new IllegalArgumentException("Отрицательное число токенов");
        return number;
    }

    private static TokenUsage.Source source(java.util.Map<String, Object> saved, String field, TokenUsage.Source fallback) {
        if (!saved.containsKey(field)) return fallback;
        Object value = saved.get(field);
        for (TokenUsage.Source source : TokenUsage.Source.values()) {
            if (source.name().equals(value)) return source;
        }
        return TokenUsage.Source.UNKNOWN;
    }

    private static List<Attachment> attachments(Object message) throws IOException {
        List<Attachment> files = new ArrayList<>();
        for (Object file : Json.asList(Json.at(message, "attachments"))) {
            String name = Json.stringAt(file, "name");
            String type = Json.stringAt(file, "mediaType");
            String data = Json.stringAt(file, "data");
            if (name == null || type == null || data == null) {
                throw new IOException("В сохранённом вложении нет имени, типа или данных");
            }
            files.add(new Attachment(name, type, data));
        }
        return files;
    }

    private static int number(Object part, String key) {
        return Json.at(part, key) instanceof Number number ? number.intValue() : 0;
    }
}
