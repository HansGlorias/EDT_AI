package io.github.hansglorias.edtai.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class McpServersCodecTest {

    @Test
    void emptyListIsEmptyString() {
        assertEquals("", McpServersCodec.encode(List.of()));
        assertTrue(McpServersCodec.decode("").isEmpty());
        assertTrue(McpServersCodec.decode(null).isEmpty());
    }

    @Test
    void roundTripKeepsAllFields() {
        List<McpServer> servers = List.of(
                new McpServer("файлы", McpTransport.STDIO, "npx -y @modelcontextprotocol/server-filesystem C:\\work", true),
                new McpServer("remote", McpTransport.HTTP, "http://localhost:8080/mcp", false));

        assertEquals(servers, McpServersCodec.decode(McpServersCodec.encode(servers)));
    }

    @Test
    void roundTripEscapesSeparatorsAndBackslashes() {
        List<McpServer> servers = List.of(
                new McpServer("a\tb\\c", McpTransport.STDIO, "cmd \"x\\ty\"\\n", true));

        String encoded = McpServersCodec.encode(servers);

        assertEquals(1, encoded.split("\n").length);
        assertEquals(4, encoded.split("\t").length);
        assertEquals(servers, McpServersCodec.decode(encoded));
    }

    @Test
    void namesAndTargetsAreTrimmed() {
        McpServer server = new McpServer("  name ", McpTransport.HTTP, " http://localhost/ ", true);

        assertEquals("name", server.name());
        assertEquals("http://localhost/", server.target());
    }

    @Test
    void corruptedRecordIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> McpServersCodec.decode("1\tSTDIO\tname"));
        assertThrows(IllegalArgumentException.class, () -> McpServersCodec.decode("x\tSTDIO\tname\tcmd"));
        assertThrows(IllegalArgumentException.class, () -> McpServersCodec.decode("1\tSMTP\tname\tcmd"));
        assertThrows(IllegalArgumentException.class, () -> McpServersCodec.decode("1\tSTDIO\tname\tcmd\\"));
        assertThrows(IllegalArgumentException.class, () -> McpServersCodec.decode("1\tSTDIO\tname\tcmd\\q"));
    }
}
