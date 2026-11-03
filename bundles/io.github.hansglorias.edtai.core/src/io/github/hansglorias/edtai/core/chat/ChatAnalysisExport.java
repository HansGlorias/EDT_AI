package io.github.hansglorias.edtai.core.chat;

import static io.github.hansglorias.edtai.core.json.Json.object;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.hansglorias.edtai.core.json.Json;

/** Выбранный чат для ручного разбора. Не читает репозиторий, Secure Storage или глобальную диагностику. */
public final class ChatAnalysisExport {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_MESSAGES = 1000;
    public static final int MAX_TOOLS = 5000;
    public static final int MAX_CHANGES = 1000;
    public static final int MAX_FIELD_CHARS = 20_000;
    public static final int MAX_CONTENT_CHARS = 2_000_000;
    // Структурированный формат progress ID ExploreProject (#90), не разбор summary/outcome.
    private static final Pattern CHILD = Pattern.compile("^(.*)/child/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:/.*)?$");

    /** Только белый список текущих настроек. Исторические настройки по запросам неизвестны. */
    public record Configuration(String pluginVersion, AgentMode mode, String protocol, String model,
            int configuredWindow, int maxOutputTokens, int contextLimit, boolean toolsEnabled) { }

    public record Feedback(String expected, String actual, String problem) {
        public static Feedback empty() { return new Feedback("", "", ""); }
    }

    /** Снимки, уже привязанные вызывающим кодом к сообщениям выбранного чата; индекс начинается с 0. */
    public record Change(int messageIndex, String path, String before, String after, boolean observedMcp) { }

    public record Snapshot(String title, List<ChatMessage> messages, Configuration configuration,
            List<Change> changes, TokenUsage lastReplyUsage, boolean active) {
        public Snapshot {
            messages = List.copyOf(messages);
            changes = List.copyOf(changes);
        }
    }

    private ChatAnalysisExport() { }

    /** Атомарная замена выбранного файла по возможности; сбой подготовки не портит старый архив. */
    public static void write(Path target, Snapshot snapshot, boolean includeContent, Feedback feedback,
            ExportRedactor redactor) throws IOException {
        Map<String, String> entries = entries(snapshot, includeContent, feedback, redactor);
        Path absolute = target.toAbsolutePath();
        Path temp = Files.createTempFile(absolute.getParent(), ".edtai-analysis-", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp), StandardCharsets.UTF_8)) {
                for (var entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Map<String, Object> links() {
        return object("task_id", null, "run_id", null, "parent_run_id", null, "request_id", null,
                "span_id", null, "parent_span_id", null);
    }

    static Map<String, String> entries(Snapshot snapshot, boolean include, Feedback feedback, ExportRedactor redactor) {
        Content content = new Content(include, redactor);
        List<Object> messages = new ArrayList<>();
        List<Object> edits = new ArrayList<>();
        StringBuilder markdown = new StringBuilder("# Выгрузка выбранного чата\n\n");
        Map<String, Object> title = content.field(snapshot.title());
        if (include) markdown.append(text(title)).append("\n\n");
        else markdown.append("Содержимое разговора, кода, ошибок и путей не включено.\n\n");
        int sourceTools = snapshot.messages().stream().mapToInt(m -> m.tools().size()).sum();
        int tools = 0, calls = 0, failed = 0, errors = 0, usageRecords = 0;
        int childRecords = 0;
        Map<String, String> childIds = new LinkedHashMap<>();
        // Отдельные известные подытоги, без parent aggregate и без суммы context.
        long[][] totals = new long[2][2];
        int[][] coverage = new int[2][2];
        int size = Math.min(MAX_MESSAGES, snapshot.messages().size());
        for (int i = 0; i < size; i++) {
            ChatMessage message = snapshot.messages().get(i);
            String mid = "message-" + (i + 1);
            if (message.role() == ChatRole.ERROR) errors++;
            Map<String, Object> body = content.field(message.text());
            markdown.append("## ").append(mid).append(" · ").append(message.role()).append(" · ")
                    .append(message.status()).append("\n\n").append(text(body)).append("\n\n");
            List<Object> uses = new ArrayList<>();
            Map<String, String> callIds = new LinkedHashMap<>();
            for (ToolUse use : message.tools()) {
                if (tools >= MAX_TOOLS) break;
                tools++;
                if (use.isCall()) calls++;
                if (use.status() == ToolUse.Status.FAILED) failed++;
                Map<String, Object> name = content.metadata(use.name());
                Map<String, Object> summary = content.field(use.summary());
                Map<String, Object> outcome = content.field(use.outcome());
                String localId = "tool-" + tools;
                var childMatch = CHILD.matcher(use.id());
                boolean child = use.name().equals("explore_project") && childMatch.matches();
                String parentId = child ? callIds.get(childMatch.group(1)) : null;
                String childId = null;
                if (child) {
                    childRecords++;
                    String key = mid + "/" + childMatch.group(1) + "/" + childMatch.group(2);
                    childId = childIds.computeIfAbsent(key, ignored -> "child-" + (childIds.size() + 1));
                }
                callIds.put(use.id(), localId);
                uses.add(object("id", localId, "message_id", mid, "links", links(),
                        "execution_scope", child ? "exploration_child" : "parent_or_unspecified",
                        "scope_source", child ? "structured_progress_id_convention" : "history_without_execution_ids",
                        "parent_tool_id", parentId, "child_id", childId,
                        "kind", use.isCall() ? "tool_call" : "notice", "name", name,
                        "status", use.status().name(), "problem", use.problem() == null ? null : use.problem().name(),
                        "text_offset", use.at(), "summary", summary, "outcome", outcome,
                        "arguments", unavailable(), "result", unavailable(), "duration_ms", null,
                        "usage", usage(use.usage(), child ? "exploration_child_model_step" : "parent_or_unspecified_model_step", false)));
                markdown.append("- tool-").append(tools).append(" · ").append(use.status());
                if (use.problem() != null) markdown.append(" · ").append(use.problem());
                markdown.append(" · ").append(text(name));
                if (include) markdown.append("\n\n")
                        .append(text(summary)).append("\n\n").append(text(outcome));
                markdown.append("\n\n");
                if (use.usage() != null) {
                    usageRecords++;
                    accumulate(use.usage().input(), use.usage().inputSource(), totals[0], coverage[0]);
                    accumulate(use.usage().output(), use.usage().outputSource(), totals[1], coverage[1]);
                }
            }
            List<Object> contexts = new ArrayList<>();
            for (var item : message.context().stream().limit(100).toList()) {
                Map<String, Object> path = content.field(item.path());
                Map<String, Object> code = content.field(item.text());
                contexts.add(object("path", path, "text", code, "first_line", item.firstLine(), "last_line", item.lastLine(),
                        "total_lines", item.totalLines(), "source_truncated", item.truncated(), "omitted_lines", item.omittedLines()));
                if (include) markdown.append("Контекст: ").append(text(path)).append("\n\n").append(text(code)).append("\n\n");
            }
            List<Object> attachments = new ArrayList<>();
            for (Attachment attachment : message.attachments().stream().limit(100).toList()) {
                Map<String, Object> name = content.field(attachment.name());
                Map<String, Object> data = attachment.isText() ? content.field(attachment.data()) : unavailable();
                attachments.add(object("name", name, "data", data));
                if (include) markdown.append("Вложение: ").append(text(name)).append("\n\n").append(text(data)).append("\n\n");
            }
            messages.add(object("id", mid, "links", links(), "role", message.role().name(),
                    "status", message.status().name(), "time", message.time().toString(), "text", body,
                    "tools", uses, "tool_count_in_history", message.tools().size(),
                    "context", contexts, "context_count_in_history", message.context().size(),
                    "attachments", attachments, "attachment_count_in_history", message.attachments().size()));
        }
        for (Change change : snapshot.changes().stream().limit(MAX_CHANGES).toList()) {
            if (change.messageIndex() < 0 || change.messageIndex() >= size) continue;
            edits.add(object("message_id", "message-" + (change.messageIndex() + 1),
                    "source", change.observedMcp() ? "observed_resource_change" : "volatile_edit_snapshot",
                    "path", content.field(change.path()),
                    "before", change.observedMcp() ? unavailable() : content.field(change.before()),
                    "after", change.observedMcp() ? unavailable() : content.field(change.after())));
        }
        String feedbackText = "# Отзыв пользователя\n\n## Ожидал\n\n" + text(content.field(feedback.expected()))
                + "\n\n## Получил\n\n" + text(content.field(feedback.actual()))
                + "\n\n## Что не так\n\n" + text(content.field(feedback.problem())) + "\n";
        Configuration config = snapshot.configuration();
        // Настройки безопасного белого списка включены и без содержимого; это текущий профиль, не история.
        Map<String, Object> profile = object("scope", "export_time_configuration_not_historical",
                "mode", config.mode().name(), "protocol", content.metadata(config.protocol()),
                "model", content.metadata(config.model()), "configured_window_tokens", config.configuredWindow(),
                "max_output_tokens", config.maxOutputTokens(), "context_limit_characters", config.contextLimit(),
                "tools_enabled", config.toolsEnabled());
        Map<String, Object> pluginVersion = content.metadata(config.pluginVersion());
        Map<String, Object> completeness = object("overall", "incomplete", "active_snapshot", snapshot.active(),
                "physical_requests", "unavailable", "tool_arguments_results", "unavailable",
                "changes", "volatile_partial_not_persisted", "global_diagnostics", "excluded_no_chat_correlation",
                "repository", "excluded", "binary_attachments", "excluded",
                "source_messages", snapshot.messages().size(), "exported_messages", size,
                "source_tool_records", sourceTools, "exported_tool_records", tools,
                "source_change_records", snapshot.changes().size(), "exported_change_records", edits.size(),
                "truncated_content_fields", content.truncated);
        String summary = "# Сводка\n\n"
                + "Данные неполные: это история выбранного чата, не полный trace задачи.\n\n"
                + "Сообщений: " + size + "/" + snapshot.messages().size() + "; ошибок-сообщений: " + errors
                + ". Записей ToolUse: " + tools + "/" + sourceTools + ", из них вызовов: " + calls
                + ", со статусом FAILED (включая отказ/дубль): " + failed + ".\n\n"
                + "Дочерних progress-записей explore_project: " + childRecords + ", групп: " + childIds.size()
                + ". Scope взят из соглашения структурированных ID, не из текста отчёта. Это не полный child transcript.\n\n"
                + "Доступных записей правок: " + edits.size() + ". Это не число всех изменений; снимки не сохраняются в истории.\n\n"
                + "| Известный подытог usage шагов | SERVER | ESTIMATE |\n|---|---|---|\n"
                + "| input | " + subtotal(totals[0], coverage[0], 0, usageRecords) + " | " + subtotal(totals[0], coverage[0], 1, usageRecords) + " |\n"
                + "| output | " + subtotal(totals[1], coverage[1], 0, usageRecords) + " | " + subtotal(totals[1], coverage[1], 1, usageRecords) + " |\n\n"
                + "Подытоги включают известные шаги родителя и дочерней разведки, а не родительский cumulative aggregate. "
                + "Usage есть у " + usageRecords + "/" + tools + " записей ToolUse; один шаг может содержать несколько вызовов. "
                + "Это не покрытие физических запросов. UNKNOWN и MIXED не включены в эти суммы; MIXED сохранён отдельно в JSON. "
                + "Usage последнего ответа — родительский агрегат, он не прибавляется к шагам.\n\n"
                + "Запросы ответа без инструментов, названия, сжатия, сетевые повторы, transport attempts, "
                + "полная стоимость и длительности: unavailable. Контекст не суммируется. "
                + "Аргументы/полные результаты инструментов не сохраняются; outcome — краткая строка, не полный результат.\n\n"
                + "Содержимое: " + (include ? "включено с очисткой известных секретов и ограничениями" : "не включено")
                + "; обрезанных полей: " + content.truncated + ". Глобальный DiagnosticLog не включён: связи с этим чатом нет.\n\n"
                + "Очистка не гарантирует анонимность произвольного текста. Перед передачей проверьте архив вручную. Автоматической отправки нет.\n";
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("manifest.json", Json.write(object("schema_version", SCHEMA_VERSION, "archive_id", UUID.randomUUID().toString(),
                "plugin_version", pluginVersion, "created_at", Instant.now().toString(),
                "id_scope", "export_local_not_execution_trace", "content_included", include,
                "configuration", profile, "completeness", completeness)));
        entries.put("conversation.md", markdown.toString());
        entries.put("conversation.json", Json.write(object("schema_version", SCHEMA_VERSION, "title", title,
                "messages", messages, "changes", edits, "measurements", object("coverage", "unavailable",
                        "physical_model_requests", null, "transport_attempts", null, "duration_ms", null, "first_visible_ms", null),
                "last_reply_usage", usage(snapshot.lastReplyUsage(), "last_reply_aggregate", true))));
        entries.put("summary.md", summary);
        entries.put("feedback.md", feedbackText);
        return entries;
    }

    private static Map<String, Object> unavailable() { return object("status", "unavailable", "value", null); }

    private static String text(Map<String, Object> field) {
        return field.get("value") instanceof String value ? value : "[" + field.get("status") + "]";
    }

    private static Map<String, Object> usage(TokenUsage usage, String scope, boolean parent) {
        return object("scope", scope, "includes_child_usage", parent, "coverage", "incomplete",
                "input", tokens(usage == null ? 0 : usage.input(), usage == null ? TokenUsage.Source.UNKNOWN : usage.inputSource()),
                "output", tokens(usage == null ? 0 : usage.output(), usage == null ? TokenUsage.Source.UNKNOWN : usage.outputSource()),
                "context", tokens(usage == null ? 0 : usage.context(), usage == null ? TokenUsage.Source.UNKNOWN : usage.contextSource()));
    }

    private static Map<String, Object> tokens(long value, TokenUsage.Source source) {
        return object("value", source == TokenUsage.Source.UNKNOWN ? null : value, "provenance", source.name());
    }

    private static void accumulate(long value, TokenUsage.Source source, long[] totals, int[] counts) {
        int column = source == TokenUsage.Source.SERVER ? 0 : source == TokenUsage.Source.ESTIMATE ? 1 : -1;
        if (column >= 0) {
            totals[column] = Math.addExact(totals[column], value);
            counts[column]++;
        }
    }

    private static String subtotal(long[] totals, int[] counts, int column, int records) {
        return (counts[column] == 0 ? "unknown" : Long.toString(totals[column])) + " (" + counts[column] + "/" + records + " записей usage)";
    }

    private static final class Content {
        private final boolean include;
        private final ExportRedactor redactor;
        private int remaining = MAX_CONTENT_CHARS;
        private int truncated;

        Content(boolean include, ExportRedactor redactor) { this.include = include; this.redactor = redactor; }

        Map<String, Object> field(String value) {
            if (!include) return object("status", "withheld", "value", null);
            return metadata(value);
        }

        Map<String, Object> metadata(String value) {
            if (value == null) return unavailable();
            String clean = redactor.clean(value);
            int limit = Math.min(MAX_FIELD_CHARS, remaining);
            boolean cut = clean.length() > limit;
            String kept = cut ? clean.substring(0, limit) : clean;
            if (!kept.isEmpty() && Character.isHighSurrogate(kept.charAt(kept.length() - 1))) kept = kept.substring(0, kept.length() - 1);
            remaining -= kept.length();
            if (cut) truncated++;
            return object("status", cut ? "truncated" : "available", "value", kept);
        }
    }
}
