package io.github.hansglorias.edtai.core.settings;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpServerSetupTest {
    @Test void localPortModeOnlyAcceptsLosslessSimpleUrls() {
        for (String host : List.of("localhost", "127.0.0.1", "[::1]")) {
            assertEquals(1337, McpServer.localPort("http://" + host + ":1337/mcp").orElseThrow());
        }
        for (String url : List.of("https://localhost:1337/mcp", "http://localhost:1337/custom", "http://localhost:1337/mcp?q=1",
                "http://user@localhost:1337/mcp", "http://mcp.example.invalid:1337/mcp")) {
            assertTrue(McpServer.localPort(url).isEmpty(), url);
        }
        assertEquals("http://localhost:1337/mcp", McpServer.localTarget("1337"));
        for (String port : List.of("0", "65536", "", "abc", "-1")) assertThrows(IllegalArgumentException.class, () -> McpServer.localTarget(port));
    }

    @Test void namesUseServerInfoOrEndpointAndAvoidDuplicates() {
        assertEquals("EDT MCP", McpServer.suggestedName(" EDT MCP ", "", List.of()));
        assertEquals("EDT MCP (3)", McpServer.suggestedName("EDT MCP", "", List.of("edt mcp", "EDT MCP (2)")));
        assertEquals("MCP localhost:1337", McpServer.suggestedName("", "http://localhost:1337/mcp", List.of()));
    }
}
