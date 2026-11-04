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

    @Test void replacementIsAtomicBoundedAndIsolated() throws Exception {
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

    @Test void validatesArgumentsAndLargeSchemas() {
        McpToolCatalog catalog = new McpToolCatalog(List.of(tool("huge", "z".repeat(13_000))));
        catalog.fits(selected -> false);
        for (Map<String, Object> input : List.of(Map.<String,Object>of("action", "list", "offset", 0.5),
                Map.<String,Object>of("action", "list", "extra", true),
                Map.<String,Object>of("action", "select", "names", List.of("huge")),
                Map.<String,Object>of("action", "select", "names", List.of("huge", "huge")),
                Map.<String,Object>of("action", "select", "names", List.of("a","b","c","d","e","f","g","h","i")))) {
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
