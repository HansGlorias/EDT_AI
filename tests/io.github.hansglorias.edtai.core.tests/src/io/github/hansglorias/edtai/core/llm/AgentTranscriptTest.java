package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.Protocol;

class AgentTranscriptTest {

    @Test
    void projectionPreservesFailureAndCallPairsWithoutDestroyingOriginals() {
        ToolCall call = new ToolCall("failed-call", "write", "{\"x\":1}");
        AgentTranscript transcript = new AgentTranscript();
        transcript.add(new ModelReply("attempt", List.of(call)),
                List.of(new ProtocolAdapter.ToolResult(call, "original failure", true)));

        for (Protocol protocol : Protocol.values()) {
            ProtocolAdapter adapter = ProtocolAdapter.of(protocol);
            List<Object> omitted = transcript.project(adapter, 1);
            assertEquals(2, omitted.size());
            String serialized = Json.write(omitted);
            assertFalse(serialized.contains("original failure"));
            assertTrue(serialized.contains("НЕ завершился успешно"));
            if (protocol == Protocol.OPENAI_COMPATIBLE) {
                assertEquals("failed-call", Json.stringAt(omitted.get(0), "tool_calls", 0, "id"));
                assertEquals("failed-call", Json.stringAt(omitted.get(1), "tool_call_id"));
                assertTrue(Json.stringAt(omitted.get(1), "content").startsWith("Ошибка:"));
            } else {
                assertEquals("failed-call", Json.stringAt(omitted.get(0), "content", 1, "id"));
                assertEquals("failed-call", Json.stringAt(omitted.get(1), "content", 0, "tool_use_id"));
                assertEquals(true, Json.at(omitted.get(1), "content", 0, "is_error"));
            }
            assertTrue(Json.write(transcript.project(adapter, 0)).contains("original failure"));
        }
    }
}
