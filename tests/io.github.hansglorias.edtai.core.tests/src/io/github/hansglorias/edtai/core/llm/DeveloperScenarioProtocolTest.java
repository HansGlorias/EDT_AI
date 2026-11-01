package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.EditTools;
import io.github.hansglorias.edtai.core.tools.ProjectTools;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolApprover;
import io.github.hansglorias.edtai.core.tools.WorkspaceFiles;

/** Тот же S05 с фиксированными решениями fake model, а не оценка качества Qwen. */
class DeveloperScenarioProtocolTest {

    private static final String MODULE = "src/CommonModules/РасчетыЗаказов/Module.bsl";
    @TempDir
    Path directory;

    @Test
    void smallEditIsReadBackThroughBothProtocolsAtAllThreeWindows() throws Exception {
        String prompt = Json.stringAt(Json.parseObject(resource("scenarios.json")), "scenarios", 4, "prompts", 0);
        WorkspaceFiles workspace = new WorkspaceFiles() {
            @Override
            public List<String> projects() {
                return List.of("Сценарии1С");
            }

            @Override
            public Optional<Path> root(String project) {
                return Optional.of(directory);
            }
        };
        List<Tool> tools = new ArrayList<>(ProjectTools.create(workspace, () -> 40_000));
        tools.addAll(EditTools.create(workspace, change -> { }));
        Path file = directory.resolve(MODULE);
        Files.createDirectories(file.getParent());
        for (Protocol protocol : Protocol.values()) {
            for (int window : List.of(8192, 16384, 32768)) {
                Files.writeString(file, resource("project/" + MODULE));
                List<ModelReply> replies = List.of(
                        call("read-before", "read_module", Map.of("module", MODULE)),
                        call("edit", "edit_module", Map.of("module", MODULE, "old_text", "/ 100, 2)", "new_text", "/ 100, 0)")),
                        call("read-after", "read_module", Map.of("module", MODULE)),
                        ModelReply.text("Округление изменено и прочитано."));
                AtomicInteger index = new AtomicInteger();
                try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                    ModelReply reply = replies.get(index.getAndIncrement());
                    Object message = ProtocolAdapter.of(protocol).assistantMessage(reply);
                    Object body = protocol == Protocol.OPENAI_COMPATIBLE
                            ? Json.object("choices", List.of(Json.object("message", message))) : message;
                    response.send(200, "application/json", Json.write(body));
                })) {
                    ModelConnection connection = connection(protocol, server, window);
                    ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient(),
                            problems -> tools, ToolApprover.ALLOW_ALL);
                    String answer = provider.reply(List.of(ChatMessage.of(ChatRole.USER, prompt)), text -> { })
                            .get(10, TimeUnit.SECONDS);
                    assertEquals("Округление изменено и прочитано.", answer);
                    assertEquals(resource("expected/S05.bsl"), Files.readString(file));
                    assertEquals(4, server.requests().size());
                    String last = server.requests().get(3).body();
                    assertTrue(last.contains("/ 100, 0)"), protocol + " " + window);
                    assertTrue(last.contains("read-after"), "результат связан с проверочным вызовом");
                }
            }
        }
    }

    @Test
    void summaryAndTitleRemainSeparatePhysicalRequestsInBothProtocols() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            try (FakeModelServer server = FakeModelServer.http().handler((request, response) -> {
                Object message = ProtocolAdapter.of(protocol).assistantMessage(ModelReply.text("Короткий ответ"));
                response.send(200, "application/json", Json.write(protocol == Protocol.OPENAI_COMPATIBLE
                        ? Json.object("choices", List.of(Json.object("message", message))) : message));
            })) {
                ModelChatProvider provider = new ModelChatProvider(() -> connection(protocol, server, 32768), new ModelClient());
                List<ChatMessage> history = List.of(ChatMessage.of(ChatRole.USER, "Вопрос"));
                provider.reply(history, text -> { }).get(10, TimeUnit.SECONDS);
                provider.summarize(history).get(10, TimeUnit.SECONDS);
                provider.title(history).get(10, TimeUnit.SECONDS);
                assertEquals(3, server.requests().size());
                assertTrue(server.requests().get(1).body().contains("Сожми переписку"));
                assertTrue(server.requests().get(2).body().contains("короткое название"));
            }
        }
    }

    private static ModelConnection connection(Protocol protocol, FakeModelServer server, int window) {
        return new ModelConnection(protocol, server.url(), "test-model", "", 2048, "",
                new TrustSettings(false, Optional.empty()), window);
    }

    private static ModelReply call(String id, String tool, Map<String, Object> input) {
        return new ModelReply("", List.of(new ToolCall(id, tool, Json.write(input))));
    }

    private static String resource(String name) throws Exception {
        try (var stream = DeveloperScenarioProtocolTest.class.getResourceAsStream("/fixtures/developer-scenarios/" + name)) {
            assertNotNull(stream, name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
