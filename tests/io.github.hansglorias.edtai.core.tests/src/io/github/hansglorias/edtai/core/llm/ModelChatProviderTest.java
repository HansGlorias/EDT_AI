package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.ChatSession;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;

class ModelChatProviderTest {

    @Test
    void chatSessionTalksToModel() throws Exception {
        try (FakeModelServer server = FakeModelServer.http()) {
            server.handler((request, response) -> response.sse(List.of(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Да\"}}]}\n\n",
                    "data: {\"choices\":[{\"delta\":{\"content\":\", работает\"}}]}\n\n", "data: [DONE]\n\n"), 10));
            ModelConnection connection = new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "m", "", 0,
                    new TrustSettings(false, Optional.empty()));
            ChatSession session = new ChatSession(new ModelChatProvider(() -> connection, new ModelClient()));

            ChatMessage reply = session.send("Работает?").get(10, TimeUnit.SECONDS);

            assertEquals(ChatRole.ASSISTANT, reply.role());
            assertEquals("Да, работает", reply.text());
            assertEquals(ChatMessage.Status.DONE, reply.status());
        }
    }

    @Test
    void settingsErrorBecomesModelException() {
        ModelChatProvider failing = new ModelChatProvider(() -> {
            throw new IllegalStateException("Secure Storage недоступно");
        }, new ModelClient(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)));

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> failing.reply(List.of(), part -> { }).get(10, TimeUnit.SECONDS));

        ModelException cause = assertInstanceOf(ModelException.class, error.getCause());
        assertTrue(cause.getMessage().contains("Secure Storage недоступно"), cause.getMessage());
    }
}
