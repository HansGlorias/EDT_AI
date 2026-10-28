package io.github.hansglorias.edtai.core.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                new McpServer("remote", McpTransport.HTTP, "http://localhost:8080/mcp", false),
                new McpServer("EDT", McpTransport.HTTP, "http://localhost:8765/mcp", true, "read_module_source, get_*"));

        assertEquals(servers, McpServersCodec.decode(McpServersCodec.encode(servers)));
    }

    @Test
    void recordWithoutToolsFromStage5IsRead() {
        assertEquals(List.of(new McpServer("EDT", McpTransport.HTTP, "http://localhost:8765/mcp", true, "")),
                McpServersCodec.decode("1\tHTTP\tEDT\thttp://localhost:8765/mcp"));
    }

    @Test
    void toolFilter() {
        McpServer all = new McpServer("EDT", McpTransport.HTTP, "", true, " ");
        McpServer some = new McpServer("EDT", McpTransport.HTTP, "", true, "read_module_source;  GET_* ,search_in_code");

        assertEquals("", all.tools());
        assertTrue(all.allowsTool("delete_project"));
        assertEquals("read_module_source, GET_*, search_in_code", some.tools());
        assertTrue(some.allowsTool("read_module_source"));
        assertTrue(some.allowsTool("get_edt_version"));
        assertTrue(some.allowsTool("Search_In_Code"));
        assertFalse(some.allowsTool("read_module_source_2"));
        assertFalse(some.allowsTool("delete_project"));
        for (String word : List.of("все", "Все", "all", "*", "read_*, все")) {
            McpServer everything = new McpServer("EDT", McpTransport.HTTP, "", true, word);
            assertEquals("", everything.tools(), word);
            assertTrue(everything.allowsTool("write_module_source"), word);
        }
    }

    @Test
    void roundTripEscapesSeparatorsAndBackslashes() {
        List<McpServer> servers = List.of(
                new McpServer("a\tb\\c", McpTransport.STDIO, "cmd \"x\\ty\"\\n", true));

        String encoded = McpServersCodec.encode(servers);

        assertEquals(1, encoded.split("\n").length);
        assertEquals(5, encoded.split("\t", -1).length);
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
