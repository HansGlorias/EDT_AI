package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.context.ContextItem;
import io.github.hansglorias.edtai.core.json.Json;

class ChatAnalysisExportTest {
    @TempDir Path directory;
    private static final String TOKEN = "sample-secret-12345";
    private static final String URL = "https://example.invalid/private?key=" + TOKEN;
    private static final String PRIVATE = "закрытый разговор";

    private static ChatAnalysisExport.Configuration configuration() {
        return new ChatAnalysisExport.Configuration("0.1.0.test", AgentMode.AGENT, "OPENAI_COMPATIBLE", "демо-модель",
                8192, 2048, 40000, true);
    }

    private static ExportRedactor redactor() { return new ExportRedactor(List.of(TOKEN), List.of(URL)); }

    private ChatAnalysisExport.Snapshot snapshot() {
        var context = ContextItem.of("Личный проект", "src/закрытый.bsl", 1, 1, 1, "секретный код");
        var use = new ToolUse("raw-call-id", "read_module", "Читает приватный модуль", "Ошибка " + TOKEN + " " + URL,
                ToolUse.Status.FAILED, 0, null,
                TokenUsage.step(10, 20, TokenUsage.Source.SERVER, TokenUsage.Source.ESTIMATE), ToolUse.Problem.SERVER);
        var ask = new ChatMessage(ChatRole.USER, PRIVATE + " " + TOKEN, Instant.EPOCH,
                ChatMessage.Status.DONE, List.of(context), List.of(), List.of(new Attachment("лог.txt", "text/plain", TOKEN)));
        var answer = new ChatMessage(ChatRole.ASSISTANT, "Ответ по-русски " + URL, Instant.EPOCH.plusSeconds(1),
                ChatMessage.Status.STOPPED, List.of(), List.of(use));
        return new ChatAnalysisExport.Snapshot("Приватное название", List.of(ask, answer), configuration(),
                List.of(new ChatAnalysisExport.Change(1, "src/закрытый.bsl", "до " + TOKEN, "после", false)),
                TokenUsage.step(100, 200, false), false);
    }

    private Map<String, String> write(ChatAnalysisExport.Snapshot snapshot, boolean include) throws Exception {
        Path zip = directory.resolve("разбор.zip");
        ChatAnalysisExport.write(zip, snapshot, include, new ChatAnalysisExport.Feedback("ожидал " + TOKEN, "получил", "ошибка " + URL), redactor());
        return unzip(zip);
    }

    private static Map<String, String> unzip(Path file) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertNull(entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8)), "duplicate entry");
            }
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) { return (List<Map<String, Object>>) value; }

    @Test
    void safeDefaultHasFixedZipSchemaAndNoContent() throws Exception {
        var entries = write(snapshot(), false);
        assertEquals(List.of("manifest.json", "conversation.md", "conversation.json", "summary.md", "feedback.md"),
                new ArrayList<>(entries.keySet()));
        String all = String.join("\n", entries.values());
        for (String value : List.of(TOKEN, URL, "https://", "example.invalid", PRIVATE, "секретный код",
                "Приватное название", "Читает приватный", "src/закрытый", "raw-call-id", "Личный проект", "ожидал ")) {
            assertFalse(all.contains(value), value);
        }
        var manifest = Json.parseObject(entries.get("manifest.json"));
        assertEquals(1L, manifest.get("schema_version"));
        assertEquals(false, manifest.get("content_included"));
        var completeness = map(manifest.get("completeness"));
        assertEquals("incomplete", completeness.get("overall"));
        assertEquals("excluded_no_chat_correlation", completeness.get("global_diagnostics"));
        var conversation = Json.parseObject(entries.get("conversation.json"));
        assertEquals(List.of("schema_version", "title", "messages", "changes", "measurements", "last_reply_usage"),
                new ArrayList<>(conversation.keySet()));
        assertNull(map(conversation.get("measurements")).get("physical_model_requests"));
        var ask = list(conversation.get("messages")).get(0);
        assertEquals(Map.of("status", "withheld"), withoutNulls(map(ask.get("text"))));
        var call = list(list(conversation.get("messages")).get(1).get("tools")).get(0);
        assertEquals("read_module", map(call.get("name")).get("value"));
        assertEquals("SERVER", call.get("problem"));
        assertEquals("unavailable", map(call.get("arguments")).get("status"));
        assertNull(call.get("duration_ms"));
        assertTrue(map(call.get("links")).values().stream().allMatch(v -> v == null));
        assertTrue(entries.get("summary.md").contains("не покрытие физических запросов"));
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> map) {
        Map<String, Object> result = new LinkedHashMap<>(map);
        result.values().removeIf(v -> v == null);
        return result;
    }

    @Test
    void explicitContentContainsConversationChangesAndFeedbackButNotSecretsOrUrls() throws Exception {
        var entries = write(snapshot(), true);
        String all = String.join("\n", entries.values());
        assertTrue(all.contains(PRIVATE));
        assertTrue(all.contains("секретный код"));
        assertTrue(all.contains("Ответ по-русски"));
        assertTrue(all.contains("src/закрытый.bsl"));
        assertTrue(entries.get("feedback.md").contains("ожидал [удалено]"));
        for (String value : List.of(TOKEN, URL, "https://", "example.invalid", "raw-call-id")) assertFalse(all.contains(value), value);
        var conversation = Json.parseObject(entries.get("conversation.json"));
        assertEquals("available", map(list(conversation.get("changes")).get(0).get("before")).get("status"));
    }

    @Test
    void unknownAndMixedStayExplicitAndParentIsNotAddedToChildTotals() throws Exception {
        var tool = new ToolUse("", "search_code", "", "", ToolUse.Status.DONE).withUsage(
                TokenUsage.step(900, 0, TokenUsage.Source.MIXED, TokenUsage.Source.UNKNOWN));
        var first = snapshot();
        var mixed = new ChatAnalysisExport.Snapshot(first.title(), List.of(first.messages().get(0),
                first.messages().get(1).withTools(List.of(first.messages().get(1).tools().get(0), tool))),
                first.configuration(), List.of(), first.lastReplyUsage(), true);
        var entries = write(mixed, false);
        String summary = entries.get("summary.md");
        assertTrue(summary.contains("| input | 10 (1/2 записей usage) | unknown (0/2 записей usage) |"), summary);
        assertTrue(summary.contains("| output | unknown (0/2 записей usage) | 20 (1/2 записей usage) |"), summary);
        var conversation = Json.parseObject(entries.get("conversation.json"));
        var uses = list(list(conversation.get("messages")).get(1).get("tools"));
        var usage = map(uses.get(1).get("usage"));
        assertEquals("MIXED", map(usage.get("input")).get("provenance"));
        assertEquals(900L, map(usage.get("input")).get("value"));
        assertEquals("UNKNOWN", map(usage.get("output")).get("provenance"));
        assertNull(map(usage.get("output")).get("value"));
        assertEquals(true, map(conversation.get("last_reply_usage")).get("includes_child_usage"));
        assertEquals(true, map(Json.parseObject(entries.get("manifest.json")).get("completeness")).get("active_snapshot"));
    }

    @Test
    void reloadedChatWithoutUsageOrChangesDoesNotInventZeros() throws Exception {
        var messages = List.of(ChatMessage.of(ChatRole.USER, "Тест"), ChatMessage.of(ChatRole.ERROR, "Сбой"));
        var entries = write(new ChatAnalysisExport.Snapshot("", messages, configuration(), List.of(), null, false), false);
        var conversation = Json.parseObject(entries.get("conversation.json"));
        assertEquals(List.of(), conversation.get("changes"));
        assertNull(map(map(conversation.get("last_reply_usage")).get("input")).get("value"));
        assertTrue(entries.get("summary.md").contains("unknown (0/0 записей usage)"));
        assertTrue(entries.get("summary.md").contains("ошибок-сообщений: 1"));
    }

    @Test
    void knownZeroIsServerAndBinaryAttachmentsAreNeverIncluded() throws Exception {
        var use = new ToolUse("", "read_module", "", "", ToolUse.Status.DONE).withUsage(TokenUsage.step(0, 0, false));
        var message = ChatMessage.of(ChatRole.ASSISTANT, "Ответ").withTools(List.of(use))
                .withAttachments(List.of(Attachment.binary("file.docx", "application/zip", "binary-secret".getBytes(StandardCharsets.UTF_8))));
        var entries = write(new ChatAnalysisExport.Snapshot("", List.of(message), configuration(), List.of(), null, false), true);
        assertTrue(entries.get("summary.md").contains("0 (1/1 записей usage)"));
        assertFalse(String.join("", entries.values()).contains("YmluYXJ5LXNlY3JldA=="));
        assertEquals("unavailable", map(list(list(Json.parseObject(entries.get("conversation.json")).get("messages")).get(0)
                .get("attachments")).get(0).get("data")).get("status"));
    }

    @Test
    void textAndRecordLimitsAreMarked() throws Exception {
        var many = new ArrayList<ChatMessage>();
        for (int i = 0; i < ChatAnalysisExport.MAX_MESSAGES + 1; i++) {
            many.add(ChatMessage.of(ChatRole.USER, "я".repeat(ChatAnalysisExport.MAX_FIELD_CHARS + 1)));
        }
        var entries = write(new ChatAnalysisExport.Snapshot("", many, configuration(), List.of(), null, false), true);
        var complete = map(Json.parseObject(entries.get("manifest.json")).get("completeness"));
        assertEquals((long) many.size(), complete.get("source_messages"));
        assertEquals((long) ChatAnalysisExport.MAX_MESSAGES, complete.get("exported_messages"));
        assertTrue(((Number) complete.get("truncated_content_fields")).intValue() > 0);
        var messages = list(Json.parseObject(entries.get("conversation.json")).get("messages"));
        assertEquals("truncated", map(messages.get(0).get("text")).get("status"));
        assertEquals("", map(messages.get(messages.size() - 1).get("text")).get("value"));
    }

    @Test
    void exportsOnlyGivenChatAndCanReplaceArchive() throws Exception {
        ChatStore store = new ChatStore(directory.resolve("chats"));
        store.file("selected").save(List.of(ChatMessage.of(ChatRole.USER, "Выбранный чат")));
        store.file("other").save(List.of(ChatMessage.of(ChatRole.USER, "ЧУЖОЙ ЧАТ")));
        Files.writeString(directory.resolve("metrics.jsonl"), "ЧУЖАЯ ГЛОБАЛЬНАЯ ДИАГНОСТИКА");
        var selected = new ChatAnalysisExport.Snapshot("", store.file("selected").load(), configuration(), List.of(), null, false);
        write(snapshot(), true);
        var entries = write(selected, true);
        String all = String.join("", entries.values());
        assertTrue(all.contains("Выбранный чат"));
        assertFalse(all.contains("ЧУЖОЙ"));
        assertFalse(all.contains("ГЛОБАЛЬНАЯ"));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void redactorRemovesKnownLiteralsUrlsAndCredentialHeadersFromEveryText() {
        var clean = new ExportRedactor(List.of(TOKEN, "secret\"quoted"), List.of(URL));
        String text = "Authorization: Bearer other-secret\nX-API-Key: another-secret\nCookie: a=b\n"
                + "token=third-secret\n" + TOKEN + " https://else.example.invalid/path\nexample.invalid\nsecret\\\"quoted";
        String result = clean.clean(text);
        for (String forbidden : List.of("other-secret", "another-secret", "a=b", "third-secret", TOKEN,
                "https://", "example.invalid", "secret\\\"quoted")) assertFalse(result.contains(forbidden), result);
        assertFalse(clean.toString().contains(TOKEN));
    }

    @Test
    void toolLimitAndSourceContextTruncationAreExplicit() throws Exception {
        List<ToolUse> uses = new ArrayList<>();
        for (int i = 0; i <= ChatAnalysisExport.MAX_TOOLS; i++) {
            uses.add(new ToolUse("ignored", "read_module", "", "", ToolUse.Status.DONE));
        }
        var item = new ContextItem("Проект", "x.bsl", "", 1, 2, 100, "Фрагмент", 98);
        var message = ChatMessage.of(ChatRole.ASSISTANT, "").withTools(uses).withContext(List.of(item));
        var entries = write(new ChatAnalysisExport.Snapshot("", List.of(message), configuration(),
                List.of(new ChatAnalysisExport.Change(0, "meta.mdo", null, null, true)), null, false), true);
        var complete = map(Json.parseObject(entries.get("manifest.json")).get("completeness"));
        assertEquals(5001L, complete.get("source_tool_records"));
        assertEquals(5000L, complete.get("exported_tool_records"));
        var conversation = Json.parseObject(entries.get("conversation.json"));
        var context = list(list(conversation.get("messages")).get(0).get("context")).get(0);
        assertEquals(true, context.get("source_truncated"));
        assertEquals(98L, context.get("omitted_lines"));
        assertEquals("unavailable", map(list(conversation.get("changes")).get(0).get("before")).get("status"));
    }

    @Test
    void cleaningPrecedesTruncationAndAlsoCoversMetadata() throws Exception {
        var config = new ChatAnalysisExport.Configuration(TOKEN, AgentMode.PLAN, URL, TOKEN, 0, 0, 40000, true);
        var message = ChatMessage.of(ChatRole.ERROR, "я".repeat(ChatAnalysisExport.MAX_FIELD_CHARS - 1) + TOKEN)
                .withTools(List.of(new ToolUse("", TOKEN, URL, TOKEN, ToolUse.Status.FAILED)));
        var entries = write(new ChatAnalysisExport.Snapshot(TOKEN, List.of(message), config, List.of(), null, false), true);
        String all = String.join("", entries.values());
        assertFalse(all.contains(TOKEN));
        assertFalse(all.contains("sample-secret"), "обрезка не должна оставить префикс ключа");
        assertFalse(all.contains("https://"));
    }

    @Test
    void explorationChildUsageIsDistinguishedAndCumulativeParentIsNotAdded() throws Exception {
        String childId = "call/child/12345678-1234-1234-1234-123456789abc/read";
        var parent = new ToolUse("call", "explore_project", "Разведка", "", ToolUse.Status.DONE)
                .withUsage(TokenUsage.step(10, 2, false));
        var child = new ToolUse(childId, "explore_project", "Разведка: читает", "", ToolUse.Status.DONE)
                .withUsage(TokenUsage.step(20, 5, true));
        var message = ChatMessage.of(ChatRole.ASSISTANT, "Итог").withTools(List.of(parent, child));
        var entries = write(new ChatAnalysisExport.Snapshot("", List.of(message), configuration(), List.of(),
                TokenUsage.step(30, 7, TokenUsage.Source.MIXED, TokenUsage.Source.MIXED), false), false);
        var uses = list(list(Json.parseObject(entries.get("conversation.json")).get("messages")).get(0).get("tools"));
        assertEquals("parent_or_unspecified", uses.get(0).get("execution_scope"));
        assertEquals("exploration_child", uses.get(1).get("execution_scope"));
        assertEquals("tool-1", uses.get(1).get("parent_tool_id"));
        assertEquals("child-1", uses.get(1).get("child_id"));
        assertEquals("exploration_child_model_step", map(uses.get(1).get("usage")).get("scope"));
        assertFalse(String.join("", entries.values()).contains("12345678-1234-1234-1234-123456789abc"));
        assertTrue(entries.get("summary.md").contains("| input | 10 (1/2 записей usage) | 20 (1/2 записей usage) |"));
    }
}
