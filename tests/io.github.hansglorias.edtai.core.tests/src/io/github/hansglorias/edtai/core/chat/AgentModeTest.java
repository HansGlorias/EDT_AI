package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.mcp.McpHub;
import io.github.hansglorias.edtai.core.settings.McpServer;
import io.github.hansglorias.edtai.core.settings.McpTransport;

class AgentModeTest {

    @Test
    void modesAndLegacyFlags() {
        assertTrue(AgentMode.AUTO.autoApprove());
        assertFalse(AgentMode.AGENT.autoApprove());
        assertTrue(AgentMode.PLAN.readOnly());
        assertTrue(AgentMode.ASK.readOnly());
        assertFalse(AgentMode.AUTO.readOnly());

        assertEquals(AgentMode.PLAN, AgentMode.parse("PLAN", true, false), "сохранённый режим важнее прежних флажков");
        assertEquals(AgentMode.ASK, AgentMode.parse("", true, true), "было «Только вопросы» — «Вопросы»");
        assertEquals(AgentMode.AUTO, AgentMode.parse("", true, false), "был авторежим — «Авто»");
        assertEquals(AgentMode.AGENT, AgentMode.parse("непонятно", false, false));
    }

    @Test
    void mcpCounter() {
        McpServer edt = new McpServer("EDT", McpTransport.HTTP, "http://localhost:8765/mcp", true);
        McpServer docs = new McpServer("Docs", McpTransport.HTTP, "http://localhost:8766/mcp", true);
        assertEquals("MCP", McpHub.counter(List.of()));
        assertEquals("MCP 1/2", McpHub.counter(List.of(
                new McpHub.ServerStatus(edt, McpHub.State.CONNECTED, "", "", List.of()),
                new McpHub.ServerStatus(docs, McpHub.State.FAILED, "нет связи", "", List.of()))));
    }
}
