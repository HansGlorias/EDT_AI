package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class ChatSessionTest {

    private static final long TIMEOUT = 10;

    @Test
    void echoRepliesWithLastUserMessage() throws Exception {
        ChatSession session = new ChatSession(new EchoChatProvider(Duration.ZERO));

        ChatMessage reply = session.send("  привет  ").get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(ChatRole.ASSISTANT, reply.role());
        assertEquals(EchoChatProvider.PREFIX + "привет", reply.text());
        assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT), roles(session));
        assertFalse(session.isBusy());
    }

    @Test
    void echoDoesNotBlockCaller() throws Exception {
        ChatSession session = new ChatSession(new EchoChatProvider(Duration.ofMillis(500)));

        CompletableFuture<ChatMessage> reply = session.send("долго");

        assertFalse(reply.isDone());
        assertTrue(session.isBusy());
        assertEquals(1, session.messages().size());
        reply.get(TIMEOUT, TimeUnit.SECONDS);
        assertFalse(session.isBusy());
    }

    @Test
    void rejectsBlankAndConcurrentMessages() throws Exception {
        ChatSession session = new ChatSession(new EchoChatProvider(Duration.ofMillis(300)));

        assertThrows(IllegalArgumentException.class, () -> session.send(" \n "));
        CompletableFuture<ChatMessage> first = session.send("один");
        assertThrows(IllegalStateException.class, () -> session.send("два"));
        first.get(TIMEOUT, TimeUnit.SECONDS);
        session.send("два").get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(4, session.messages().size());
    }

    @Test
    void providerFailureBecomesErrorMessage() throws Exception {
        ChatSession session = new ChatSession(failing(new IllegalStateException("сервер недоступен")));

        ChatMessage reply = session.send("вопрос").get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(ChatRole.ERROR, reply.role());
        assertEquals("Ошибка: сервер недоступен", reply.text());
        assertFalse(session.isBusy());
    }

    @Test
    void errorsAreNotSentToProvider() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        boolean[] fail = { true };
        ChatProvider provider = new ChatProvider() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public CompletableFuture<String> reply(List<ChatMessage> history) {
                requests.add(history);
                return fail[0] ? CompletableFuture.failedFuture(new RuntimeException()) : CompletableFuture.completedFuture("ok");
            }
        };
        ChatSession session = new ChatSession(provider);

        session.send("раз").get(TIMEOUT, TimeUnit.SECONDS);
        fail[0] = false;
        session.send("два").get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(List.of(ChatRole.USER, ChatRole.ERROR, ChatRole.USER, ChatRole.ASSISTANT), roles(session));
        assertEquals(List.of(ChatRole.USER, ChatRole.USER), requests.get(1).stream().map(ChatMessage::role).toList());
    }

    @Test
    void notifiesListeners() throws Exception {
        ChatSession session = new ChatSession(new EchoChatProvider(Duration.ZERO));
        List<String> events = new ArrayList<>();
        session.addListener(new ChatListener() {
            @Override
            public synchronized void messageAdded(ChatMessage message) {
                events.add("message:" + message.role());
            }

            @Override
            public synchronized void cleared() {
                events.add("cleared");
            }

            @Override
            public synchronized void busyChanged(boolean busy) {
                events.add("busy:" + busy);
            }
        });

        session.send("x").get(TIMEOUT, TimeUnit.SECONDS);
        session.clear();

        assertEquals(List.of("busy:true", "message:USER", "message:ASSISTANT", "busy:false", "cleared"), events);
        assertTrue(session.messages().isEmpty());
    }

    private static List<ChatRole> roles(ChatSession session) {
        return session.messages().stream().map(ChatMessage::role).toList();
    }

    private static ChatProvider failing(RuntimeException error) {
        return new ChatProvider() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public CompletableFuture<String> reply(List<ChatMessage> history) {
                return CompletableFuture.supplyAsync(() -> {
                    throw error;
                });
            }
        };
    }
}
