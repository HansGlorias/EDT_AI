package io.github.hansglorias.edtai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.McpServer;
import io.github.hansglorias.edtai.core.settings.McpTransport;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolException;

class McpStructuredResultTest {

    private final McpHub hub = new McpHub();
    private FakeMcpServer server;
    private int limit = 10_000;

    @BeforeEach
    void start() throws IOException {
        server = new FakeMcpServer();
        hub.buildRetryDelay = Duration.ofMillis(1);
    }

    @AfterEach
    void stop() throws IOException {
        hub.close();
        server.close();
    }

    @Test
    void placeholderDoesNotHideStructuredDataInJsonOrSse() throws Exception {
        Map<String, Object> structured = Json.object("objects", List.of("Catalog.Test"), "total", 1);
        server.tool("get_data", "Данные", arguments -> Json.object("content", List.of(text("OK - keys: objects, total")),
                "structuredContent", structured));
        Tool tool = tool("get_data");

        for (boolean sse : List.of(false, true)) {
            server.sse = sse;
            Tool.Result result = tool.call(Map.of());
            assertEquals(Json.write(structured) + "\n\nOK - keys: objects, total", result.text());
            assertEquals(Json.write(structured), result.outcome());
        }
    }

    @Test
    void structureWithoutContentOrWithEmptyContentIsReturned() throws Exception {
        server.tool("without_content", "Данные", arguments -> Json.object("structuredContent", Json.object("count", 2)));
        server.tool("empty_content", "Данные", arguments -> Json.object("content", List.of(),
                "structuredContent", Json.object("count", 2)));

        assertEquals("{\"count\":2}", tool("without_content").call(Map.of()).text());
        assertEquals("{\"count\":2}", tool("empty_content").call(Map.of()).text());
    }

    @Test
    void independentTextAndResourceAreKeptAlongsideStructure() throws Exception {
        server.tool("mixed", "Данные", arguments -> Json.object("content", List.of(text("Пояснение"), resource("# Отчёт")),
                "structuredContent", Json.object("count", 2)));

        assertEquals("{\"count\":2}\n\nПояснение\n\n# Отчёт", tool("mixed").call(Map.of()).text());
    }

    @Test
    void identicalReportInTextAndResourceIsNotRepeated() throws Exception {
        String report = "# Отчёт\n\nПроверено: 2";
        Map<String, Object> structured = Json.object("report", report, "count", 2);
        server.tool("report", "Отчёт", arguments -> Json.object("content",
                List.of(text(report), resource(report), text("Дополнение")), "structuredContent", structured));

        String result = tool("report").call(Map.of()).text();
        assertEquals(Json.write(Json.object("count", 2, "report", report)) + "\n\nДополнение", result);
        assertEquals(report, Json.stringAt(Json.parse(result.split("\n\n", 2)[0]), "report"));
    }

    @Test
    void jsonDuplicateIsComparedAsDataButDifferentReportsAreKept() throws Exception {
        server.tool("duplicate", "Данные", arguments -> Json.object("content",
                List.of(text("{ \"count\": 2, \"report\": \"Отчёт\" }"), resource("Другой отчёт")),
                "structuredContent", Json.object("report", "Отчёт", "count", 2)));

        assertEquals("{\"count\":2,\"report\":\"Отчёт\"}\n\nДругой отчёт", tool("duplicate").call(Map.of()).text());
    }

    @Test
    void nestedErrorsAreKeptAndOnlyOuterIsErrorFailsTheCall() throws Exception {
        Map<String, Object> nested = Json.object("success", false, "result", Json.object("isError", true,
                "error", Json.object("message", "Нет проекта", "details", List.of("Диагностика"))));
        server.tool("failed", "Ошибка", arguments -> Json.object("isError", true, "content", List.of(text("OK - keys: result")),
                "structuredContent", nested));
        server.tool("data", "Данные", arguments -> Json.object("isError", false, "structuredContent", nested));

        ToolException error = assertThrows(ToolException.class, () -> tool("failed").call(Map.of()));
        assertEquals(Json.write(nested) + "\n\nOK - keys: result", error.getMessage());
        assertEquals(Json.write(nested), tool("data").call(Map.of()).text(), "произвольный JSON не задаёт ошибку MCP");
    }

    @Test
    void unfinishedJobRemainsDataWithoutPollingOrACompletionClaim() throws Exception {
        Map<String, Object> job = Json.object("report", "Запрос принят", "jobId", "job-1", "status", "running", "completed", false);
        server.tool("start_job", "Запуск", arguments -> Json.object("content", List.of(text("OK - keys: jobId, status")),
                "structuredContent", job));
        server.tool("job_id", "Идентификатор", arguments -> Json.object("structuredContent", Json.object("jobId", "job-2")));

        Tool.Result result = tool("start_job").call(Map.of());
        assertTrue(result.text().contains("\"jobId\":\"job-1\""), result.text());
        assertTrue(result.text().contains("\"status\":\"running\""), result.text());
        assertTrue(result.text().contains("\"completed\":false"), result.text());
        assertTrue(result.outcome().startsWith("{\"jobId\":\"job-1\",\"status\":\"running\",\"completed\":false"), result.outcome());
        assertEquals("{\"jobId\":\"job-2\"}", tool("job_id").call(Map.of()).outcome());
        assertEquals(2, calls(), "jobId не приводит к повтору или опросу");
    }

    @Test
    void longReportIsCutAfterJobMetadataEvenWhenReportWasFirst() throws Exception {
        Map<String, Object> structured = Json.object("report", "я".repeat(1000), "jobId", "job-1", "status", "running",
                "completed", false);
        server.tool("large_job", "Отчёт", arguments -> Json.object("content", List.of(text("OK - keys: report, jobId")),
                "structuredContent", structured));
        limit = 120;

        Tool.Result result = tool("large_job").call(Map.of());
        assertTrue(result.text().startsWith("{\"jobId\":\"job-1\",\"status\":\"running\",\"completed\":false,\"report\":\""), result.text());
        assertEquals(limit, result.text().indexOf('\n'), "содержательная часть ограничена лимитом");
        assertTrue(result.text().contains("результат обрезан по лимиту контекста: 120 из "), result.text());
        assertFalse(result.text().contains("OK - keys"), "структура важнее заглушки");
        assertTrue(result.text().length() < 220, "полный отчёт не дописывается за лимитом");
    }

    @Test
    void longErrorRetainsNestedDiagnosticsAheadOfReport() throws Exception {
        server.tool("large_error", "Ошибка", arguments -> Json.object("isError", true, "structuredContent",
                Json.object("report", "я".repeat(1000), "result", Json.object("error", Json.object("message", "Нет проекта")))));
        limit = 100;

        ToolException error = assertThrows(ToolException.class, () -> tool("large_error").call(Map.of()));
        assertTrue(error.getMessage().startsWith("{\"result\":{\"error\":{\"message\":\"Нет проекта\"}}"), error.getMessage());
        assertEquals(limit, error.getMessage().indexOf('\n'));
        assertTrue(error.getMessage().contains("результат обрезан по лимиту контекста"), error.getMessage());
    }

    @Test
    void addedJsonDoesNotIntroduceBuildingRetries() throws Exception {
        server.tool("error_with_data", "Ошибка", arguments -> Json.object("isError", true, "content", List.of(text("Нет проекта")),
                "structuredContent", Json.object("example", "Project is building. Please retry.")));

        ToolException error = assertThrows(ToolException.class, () -> tool("error_with_data").call(Map.of()));
        assertTrue(error.getMessage().contains("Project is building. Please retry."));
        assertTrue(error.getMessage().contains("Нет проекта"));
        assertEquals(1, calls(), "условия повтора проверяются по прежнему тексту");
    }

    @Test
    void originalContentAndStructuredOnlyBuildingRetriesStillWork() throws Exception {
        for (boolean structuredOnly : List.of(false, true)) {
            String name = structuredOnly ? "structured_build" : "text_build";
            AtomicInteger attempts = new AtomicInteger();
            server.tool(name, "Сборка", arguments -> attempts.incrementAndGet() == 1
                    ? Json.object("isError", true, "content", structuredOnly ? List.of() : List.of(text("Project is building. Please retry.")),
                            "structuredContent", Json.object("error", "Project is building. Please retry."))
                    : Json.object("structuredContent", Json.object("report", "Готово")));

            assertEquals("{\"report\":\"Готово\"}", tool(name).call(Map.of()).text());
            assertEquals(2, attempts.get());
            // Реестр уже запомнил инструменты; следующий вариант добавляет новый.
            hub.reset();
        }
    }

    @Test
    void legacyConstructorKeepsRetryText() {
        McpCallResult result = new McpCallResult("текст", false);
        assertEquals("текст", result.text());
        assertEquals("текст", result.retryText());
        assertFalse(result.error());
    }

    private long calls() {
        return server.methods.stream().filter("tools/call"::equals).count();
    }

    private Tool tool(String name) {
        McpServer config = new McpServer("MCP", McpTransport.HTTP, server.url(), true);
        return hub.tools(List.of(config), new TrustSettings(false, Optional.empty()), Set.of(), () -> limit,
                problem -> { throw new AssertionError(problem); }).stream()
                .filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    private static Object text(String value) {
        return Json.object("type", "text", "text", value);
    }

    private static Object resource(String value) {
        return Json.object("type", "resource", "resource",
                Json.object("uri", "https://example.invalid/report", "mimeType", "text/markdown", "text", value));
    }
}
