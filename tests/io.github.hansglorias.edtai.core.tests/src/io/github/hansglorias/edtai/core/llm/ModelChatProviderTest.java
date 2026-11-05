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
            ModelConnection connection = new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "m", "", 0, "",
                    new TrustSettings(false, Optional.empty()));
            ChatSession session = new ChatSession(new ModelChatProvider(() -> connection, new ModelClient()));

            ChatMessage reply = session.send("Работает?").get(10, TimeUnit.SECONDS);

            assertEquals(ChatRole.ASSISTANT, reply.role());
            assertEquals("Да, работает", reply.text());
            assertEquals(ChatMessage.Status.DONE, reply.status());
            assertEquals("m", reply.replyModel().model());
            assertEquals("OPENAI_COMPATIBLE", reply.replyModel().protocol());
        }
    }

    @Test
    void changingConnectionDoesNotRelabelEarlierAnswer() throws Exception {
        try (FakeModelServer server = FakeModelServer.http()) {
            server.handler((request, response) -> response.sse(List.of(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n", "data: [DONE]\n\n"), 0));
            var current = new java.util.concurrent.atomic.AtomicReference<>(new ModelConnection(Protocol.OPENAI_COMPATIBLE,
                    server.url(), "first-model", "", 0, "", new TrustSettings(false, Optional.empty())));
            ChatSession session = new ChatSession(new ModelChatProvider(current::get, new ModelClient()));
            ChatMessage first = session.send("one").get(10, TimeUnit.SECONDS);
            current.set(new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "second-model", "", 0, "",
                    new TrustSettings(false, Optional.empty())));
            ChatMessage second = session.send("two").get(10, TimeUnit.SECONDS);
            assertEquals("first-model", first.replyModel().model());
            assertEquals("second-model", second.replyModel().model());
            assertEquals("first-model", session.messages().get(1).replyModel().model());
        }
    }

    @Test
    void failedResponseKeepsAttemptedModel() throws Exception {
        try (FakeModelServer server = FakeModelServer.http()) {
            server.handler((request, response) -> response.send(400, "application/json", "{}"));
            ModelConnection connection = new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "failed-model", "", 0,
                    "", new TrustSettings(false, Optional.empty()));
            ChatSession session = new ChatSession(new ModelChatProvider(() -> connection, new ModelClient()));
            session.send("one").get(10, TimeUnit.SECONDS);
            ChatMessage failure = session.messages().get(session.messages().size() - 1);
            assertEquals(ChatRole.ERROR, failure.role());
            assertEquals("failed-model", failure.replyModel().model());
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
