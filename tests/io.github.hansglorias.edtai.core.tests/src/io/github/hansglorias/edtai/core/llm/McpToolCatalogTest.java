package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolException;

class McpToolCatalogTest {
    @Test void defaultValidatorSchemasStayOrdinaryReplaceableSelections() throws Exception {
        Tool first = tool("validate_query", "read");
        Tool second = new Tool() {
            public boolean mcp() { return true; }
            public String mcpName() { return "validate_query"; }
            public String name() { return "other__validate_query"; }
            public String description() { return "second provider"; }
            public Map<String,Object> inputSchema() { return Map.of("type", "object"); }
            public String summary(Map<String,Object> input) { return name(); }
            public Result call(Map<String,Object> input) { throw new AssertionError("No automatic execution"); }
        };
        Tool unrelated = tool("prefix_validate_query", "read");
        McpToolCatalog catalog = new McpToolCatalog(List.of(first, second, unrelated));
        catalog.selectDefaults();
        assertEquals(List.of(first, second), catalog.selected(), "все доступные провайдеры без выбора за модель");
        assertEquals(List.of("validate_query", "other__validate_query"), Json.asList(Json.parseObject(
                catalog.call(Map.of("action", "list")).text()).get("active")));
        catalog.call(Map.of("action", "select", "names", List.of()));
        assertTrue(catalog.selected().isEmpty(), "select освобождает и начальные схемы");
        catalog.call(Map.of("action", "select", "names", List.of(second.name())));
        assertEquals(List.of(second), catalog.selected());
        catalog.disable();
        catalog.selectDefaults();
        assertTrue(catalog.selected().isEmpty(), "отказ модели не включает схемы повторно");

        McpToolCatalog tight = new McpToolCatalog(List.of(first, second));
        tight.fits(selected -> selected.size() < 2);
        tight.selectDefaults();
        assertTrue(tight.selected().isEmpty(), "набор целиком не помещается: не выбираем первый сервер");
    }

    @Test void pagesAreBoundedAndHonest() throws Exception {
        McpToolCatalog catalog = new McpToolCatalog(IntStream.range(0, 23).mapToObj(i -> tool("mcp_" + i, "x".repeat(50_000))).toList());
        String first = catalog.call(Map.of("action", "list")).text();
        assertTrue(first.length() < 4000);
        Map<String, Object> page = Json.asObject(Json.parse(first));
        assertEquals(10, Json.asList(page.get("tools")).size());
        assertEquals(true, page.get("more"));
        assertEquals(10, ((Number) page.get("next_offset")).intValue());
        Map<String, Object> last = Json.asObject(Json.parse(catalog.call(Map.of("action", "list", "offset", 20)).text()));
        assertEquals(false, last.get("more"));
        assertNull(last.get("next_offset"));
    }

    @Test void searchFindsLaterToolsWithoutScanningPages() throws Exception {
        McpToolCatalog catalog = new McpToolCatalog(IntStream.range(0, 45)
                .mapToObj(i -> tool("mcp_" + i, i == 44 ? "Metadata replacement" : "Read module")).toList());
        Map<String, Object> result = Json.parseObject(catalog.call(Map.of("action", "list", "query", "METADATA")).text());
        assertEquals(1, Json.asList(result.get("tools")).size());
        assertEquals("mcp_44", Json.asObject(Json.asList(result.get("tools")).get(0)).get("name"));
        assertEquals(false, result.get("more"));
        catalog.call(Map.of("action", "select", "names", List.of("mcp_44")));
        assertEquals("mcp_44", catalog.selected().get(0).name());
        assertTrue(Json.asList(Json.parseObject(catalog.call(Map.of("action", "list", "query", "absent")).text()).get("tools")).isEmpty());
    }

    @Test void replacementIsAtomicAndIsolated() throws Exception {
        List<Tool> tools = List.of(tool("one", "read"), tool("two", "write"));
        McpToolCatalog catalog = new McpToolCatalog(tools);
        McpToolCatalog other = new McpToolCatalog(tools);
        catalog.call(Map.of("action", "select", "names", List.of("one")));
        assertThrows(ToolException.class, () -> catalog.call(Map.of("action", "select", "names", List.of("two", "forbidden"))));
        assertEquals(List.of("one"), catalog.selected().stream().map(Tool::name).toList());
        assertTrue(other.selected().isEmpty());
        catalog.fits(selected -> false);
        assertThrows(ToolException.class, () -> catalog.call(Map.of("action", "select", "names", List.of("two"))));
        assertEquals(List.of("one"), catalog.selected().stream().map(Tool::name).toList());
        catalog.disable();
        assertTrue(catalog.selected().isEmpty());
        assertThrows(ToolException.class, () -> catalog.call(Map.of("action", "select", "names", List.of("one"))));
    }

    @Test void selectionCountIsLimitedOnlyByRequestBudget() throws Exception {
        List<Tool> tools = IntStream.range(0, 12).mapToObj(i -> tool("mcp_" + i, "read")).toList();
        McpToolCatalog catalog = new McpToolCatalog(tools);
        List<String> names = tools.stream().map(Tool::name).toList();
        catalog.fits(selected -> selected.stream().mapToInt(tool -> tool.description().length()).sum() <= 40);
        Map<String, Object> properties = Json.asObject(catalog.inputSchema().get("properties"));
        assertFalse(Json.asObject(properties.get("names")).containsKey("maxItems"));

        catalog.call(Map.of("action", "select", "names", names.subList(0, 10)));
        assertEquals(names.subList(0, 10), catalog.selected().stream().map(Tool::name).toList());
        ToolException rejected = assertThrows(ToolException.class,
                () -> catalog.call(Map.of("action", "select", "names", names)));
        assertTrue(rejected.getMessage().contains("бюджет"));
        assertEquals(names.subList(0, 10), catalog.selected().stream().map(Tool::name).toList());

        catalog.call(Map.of("action", "select", "names", List.of()));
        assertTrue(catalog.selected().isEmpty());
    }

    @Test void validatesArgumentsAndLargeSchemas() {
        McpToolCatalog catalog = new McpToolCatalog(List.of(tool("huge", "z".repeat(13_000))));
        catalog.fits(selected -> false);
        for (Map<String, Object> input : List.of(Map.<String,Object>of("action", "list", "offset", 0.5),
                Map.<String,Object>of("action", "list", "extra", true),
                Map.<String,Object>of("action", "select", "names", List.of("huge")),
                Map.<String,Object>of("action", "select", "names", List.of("huge", "huge")),
                Map.<String,Object>of("action", "select", "names", List.of("unknown")),
                Map.<String,Object>of("action", "select", "names", "huge"),
                Map.<String,Object>of("action", "select", "names", List.of(1)),
                Map.<String,Object>of("action", "select"))) {
            assertThrows(ToolException.class, () -> catalog.call(input));
            assertTrue(catalog.selected().isEmpty());
        }
    }

    private static Tool tool(String name, String description) {
        return new Tool() {
            public boolean mcp() { return true; }
            public String name() { return name; }
            public String description() { return description; }
            public Map<String,Object> inputSchema() { return Map.of("type", "object"); }
            public String summary(Map<String,Object> input) { return name; }
            public Result call(Map<String,Object> input) { throw new AssertionError("Catalog must never execute MCP"); }
        };
    }
}
