package io.github.hansglorias.edtai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatMessage.Status;
import io.github.hansglorias.edtai.core.context.ContextItem;

class ChatSessionTest {

    private static final long TIMEOUT = 10;

    @Test
    void echoRepliesWithLastUserMessage() throws Exception {
        ChatSession session = new ChatSession(new EchoChatProvider(Duration.ZERO));

        ChatMessage reply = session.send("  привет  ").get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(ChatRole.ASSISTANT, reply.role());
        assertEquals(Status.DONE, reply.status());
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
        ChatSession session = new ChatSession(new EchoChatProvider(Duration.ofMillis(100)));

        assertThrows(IllegalArgumentException.class, () -> session.send(" \n "));
        CompletableFuture<ChatMessage> first = session.send("один");
        assertThrows(IllegalStateException.class, () -> session.send("два"));
        assertThrows(IllegalStateException.class, session::retry);
        first.get(TIMEOUT, TimeUnit.SECONDS);
        session.send("два").get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(4, session.messages().size());
    }

    @Test
    void answerGrowsWhileStreaming() throws Exception {
        ManualProvider provider = new ManualProvider();
        ChatSession session = new ChatSession(provider);
        List<String> events = record(session);

        CompletableFuture<ChatMessage> reply = session.send("вопрос");
        assertEquals(List.of(ChatRole.USER), roles(session));

        provider.part("При");
        assertEquals(new ChatMessage(ChatRole.ASSISTANT, "При", last(session).time(), Status.STREAMING), last(session));
        provider.part("");
        provider.part("вет");
        assertEquals("Привет", last(session).text());
        assertEquals(Status.STREAMING, last(session).status());
        provider.complete("Привет");

        assertEquals(new ChatMessage(ChatRole.ASSISTANT, "Привет", last(session).time(), Status.DONE), reply.get(TIMEOUT, TimeUnit.SECONDS));
        assertEquals(List.of("added:USER", "busy:true", "added:ASSISTANT/STREAMING:При", "updated:1/STREAMING:Привет",
                "updated:1/DONE:Привет", "busy:false"), events);
    }

    @Test
    void activityAndUsageFollowTheAnswer() throws Exception {
        ManualProvider provider = new ManualProvider();
        ChatSession session = new ChatSession(provider);
        assertNull(session.activity());

        CompletableFuture<ChatMessage> reply = session.send("вопрос");
        assertEquals(ReplyActivity.Phase.THINKING, session.activity().phase());
        provider.part("Смотрю.");
        assertEquals(ReplyActivity.Phase.WRITING, session.activity().phase());
        provider.onTool.accept(new ToolUse("1", "read_module", "Читает модуль Общий", "", ToolUse.Status.RUNNING));
        assertEquals(new ReplyActivity(ReplyActivity.Phase.TOOL, "Читает модуль Общий", session.activity().started()),
                session.activity());
        provider.onTool.accept(new ToolUse("1", "read_module", "Читает модуль Общий", "10 строк", ToolUse.Status.DONE));
        assertEquals(ReplyActivity.Phase.THINKING, session.activity().phase());
        provider.onUsage.accept(new TokenUsage(100, 5, 105, false));
        provider.complete("Смотрю.");
        reply.get(TIMEOUT, TimeUnit.SECONDS);

        assertNull(session.activity());
        assertEquals(new TokenUsage(100, 5, 105, false), session.usage());
        // Токены пришли после конца ответа — не его.
        provider.onUsage.accept(new TokenUsage(1, 1, 2, false));
        assertEquals(105, session.usage().context());
        session.clear();
        assertNull(session.usage());
    }

    @Test
    void cancelKeepsReceivedPart() throws Exception {
        ManualProvider provider = new ManualProvider();
        ChatSession session = new ChatSession(provider);

        CompletableFuture<ChatMessage> reply = session.send("вопрос");
        provider.part("Начало ");
        session.cancel();

        ChatMessage stopped = reply.get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(Status.STOPPED, stopped.status());
        assertEquals("Начало ", stopped.text());
        assertTrue(provider.reply.isCancelled());
        assertFalse(session.isBusy());

        // Часть, пришедшая после отмены, не попадает в историю.
        provider.part("хвост");
        assertEquals("Начало ", last(session).text());
        assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT), roles(session));
    }

    @Test
    void cancelBeforeFirstPartLeavesEmptyStoppedAnswer() throws Exception {
        ManualProvider provider = new ManualProvider();
        ChatSession session = new ChatSession(provider);

        session.send("раз");
        session.cancel();
        assertEquals(new ChatMessage(ChatRole.ASSISTANT, "", last(session).time(), Status.STOPPED), last(session));

        session.send("два");
        // Пустой ответ в модель не уходит.
        assertEquals(List.of("USER:раз", "USER:два"), texts(provider.history));
    }

    @Test
    void failureMidStreamKeepsPartAndAddsError() throws Exception {
        ManualProvider provider = new ManualProvider();
        ChatSession session = new ChatSession(provider);

        CompletableFuture<ChatMessage> reply = session.send("вопрос");
        provider.part("Половина");
        provider.fail(new IllegalStateException("соединение прервано"));

        ChatMessage error = reply.get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(ChatRole.ERROR, error.role());
        assertEquals("соединение прервано", error.text());
        assertEquals(List.of("USER:вопрос", "ASSISTANT:Половина", "ERROR:соединение прервано"), texts(session.messages()));
        assertEquals(Status.STOPPED, session.messages().get(1).status());
    }

    @Test
    void providerFailureBecomesErrorMessage() throws Exception {
        ChatSession session = new ChatSession(failing(new IllegalStateException("сервер недоступен")));

        ChatMessage reply = session.send("вопрос").get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(ChatRole.ERROR, reply.role());
        assertEquals("сервер недоступен", reply.text());
        assertFalse(session.isBusy());
    }

    @Test
    void providerThrowingSynchronouslyBecomesErrorMessage() throws Exception {
        ChatSession session = new ChatSession(new ChatProvider() {
            @Override
            public String name() {
                return "throwing";
            }

            @Override
            public CompletableFuture<String> reply(List<ChatMessage> history, Consumer<String> onText) {
                throw new IllegalStateException("сразу");
            }
        });

        assertEquals("сразу", session.send("x").get(TIMEOUT, TimeUnit.SECONDS).text());
        assertFalse(session.isBusy());
    }

    @Test
    void errorsAreNotSentToProvider() throws Exception {
        ManualProvider provider = new ManualProvider();
        ChatSession session = new ChatSession(provider);

        CompletableFuture<ChatMessage> first = session.send("раз");
        provider.fail(new RuntimeException());
        first.get(TIMEOUT, TimeUnit.SECONDS);
        session.send("два");

        assertEquals(List.of(ChatRole.USER, ChatRole.ERROR, ChatRole.USER), roles(session));
        assertEquals(List.of(ChatRole.USER, ChatRole.USER), provider.history.stream().map(ChatMessage::role).toList());
    }

    @Test
    void retryReplacesErrorWithNewAnswer() throws Exception {
        ManualProvider provider = new ManualProvider();
        ChatSession session = new ChatSession(provider);
        assertFalse(session.canRetry());

        CompletableFuture<ChatMessage> first = session.send("вопрос");
        provider.part("обры");
        provider.fail(new RuntimeException("сбой"));
        first.get(TIMEOUT, TimeUnit.SECONDS);
        assertTrue(session.canRetry());
        List<String> events = record(session);

        CompletableFuture<ChatMessage> second = session.retry();
        assertEquals(List.of("USER:вопрос"), texts(provider.history));
        provider.complete("ответ");

        assertEquals("ответ", second.get(TIMEOUT, TimeUnit.SECONDS).text());
        assertEquals(List.of("USER:вопрос", "ASSISTANT:ответ"), texts(session.messages()));
        assertEquals("history", events.get(0));
    }

    @Test
    void retryRegeneratesFinishedAnswer() throws Exception {
        ManualProvider provider = new ManualProvider();
        ChatSession session = new ChatSession(provider);
        CompletableFuture<ChatMessage> first = session.send("вопрос");
        provider.complete("первый ответ");
        first.get(TIMEOUT, TimeUnit.SECONDS);

        CompletableFuture<ChatMessage> second = session.retry();
        provider.complete("второй ответ");

        assertEquals("второй ответ", second.get(TIMEOUT, TimeUnit.SECONDS).text());
        assertEquals(List.of("USER:вопрос", "ASSISTANT:второй ответ"), texts(session.messages()));
    }

    @Test
    void editRemovesMessageAndEverythingAfter() throws Exception {
        ManualProvider provider = new ManualProvider();
        ChatSession session = new ChatSession(provider);
        CompletableFuture<ChatMessage> first = session.send("раз");
        provider.complete("ответ 1");
        first.get(TIMEOUT, TimeUnit.SECONDS);
        CompletableFuture<ChatMessage> second = session.send("два");
        provider.complete("ответ 2");
        second.get(TIMEOUT, TimeUnit.SECONDS);
        List<String> events = record(session);

        assertEquals("два", session.editFrom(2).text());

        assertEquals(List.of("USER:раз", "ASSISTANT:ответ 1"), texts(session.messages()));
        assertEquals(List.of("history"), events);
        assertThrows(IllegalArgumentException.class, () -> session.editFrom(1));
        assertThrows(IllegalArgumentException.class, () -> session.editFrom(5));
        session.send("три");
        assertThrows(IllegalStateException.class, () -> session.editFrom(0));
    }

    @Test
    void contextGoesToProviderAndSurvivesRetryAndEdit() throws Exception {
        ManualProvider provider = new ManualProvider();
        ChatSession session = new ChatSession(provider);
        List<ContextItem> context = List.of(ContextItem.of("Демо", "src/CommonModules/Цены/Module.bsl", 3, 4, 10, "А = 1;\nБ = 2;"));

        CompletableFuture<ChatMessage> reply = session.send("  что тут?  ", context);
        assertEquals("что тут?", provider.history.get(0).text());
        assertEquals(context, provider.history.get(0).context());
        assertTrue(provider.history.get(0).modelText().startsWith("ОбщийМодуль.Цены.Модуль (проект Демо, "));
        assertTrue(provider.history.get(0).modelText().endsWith("```bsl\nА = 1;\nБ = 2;\n```\n\nчто тут?"));
        provider.fail(new IllegalStateException("сбой"));
        reply.get(TIMEOUT, TimeUnit.SECONDS);

        CompletableFuture<ChatMessage> again = session.retry();
        assertEquals(context, provider.history.get(0).context());
        provider.complete("ответ");
        again.get(TIMEOUT, TimeUnit.SECONDS);

        ChatMessage edited = session.editFrom(0);
        assertEquals("что тут?", edited.text());
        assertEquals(context, edited.context());
        assertTrue(session.messages().isEmpty());
    }

    @Test
    void toolUsesGoIntoAnswerAndUnfinishedAreInterrupted() throws Exception {
        ToolProvider provider = new ToolProvider();
        ChatSession session = new ChatSession(provider);
        CompletableFuture<ChatMessage> reply = session.send("что в проекте?");

        provider.tool.accept(new ToolUse("1", "project_info", "Смотрит сведения о проекте", "", ToolUse.Status.RUNNING));
        assertEquals(List.of(new ToolUse("1", "project_info", "Смотрит сведения о проекте", "", ToolUse.Status.RUNNING, 0)),
                last(session).tools(), "вызов до текста — в начале ответа");
        assertEquals(Status.STREAMING, last(session).status());
        provider.tool.accept(new ToolUse("1", "project_info", "Смотрит сведения о проекте", "Демо 1.0", ToolUse.Status.DONE));
        provider.tool.accept(new ToolUse("2", "read_module", "Читает модуль Х", "", ToolUse.Status.RUNNING));
        provider.text.accept("Ответ");
        session.cancel();
        reply.get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals(List.of("Смотрит сведения о проекте — Демо 1.0", "Читает модуль Х — ошибка: прервано"),
                last(session).tools().stream().map(ToolUse::label).toList());
        assertEquals("Ответ", last(session).text());
    }

    @Test
    void toolPositionFollowsTextAndSurvivesStrippedAnswer() throws Exception {
        ToolProvider provider = new ToolProvider();
        ChatSession session = new ChatSession(provider);
        CompletableFuture<ChatMessage> reply = session.send("правь");

        provider.text.accept("\n\nСмотрю.");
        provider.tool.accept(new ToolUse("1", "read_module", "Читает модуль Х", "", ToolUse.Status.RUNNING));
        provider.tool.accept(new ToolUse("1", "read_module", "Читает модуль Х", "10 строк", ToolUse.Status.DONE));
        provider.text.accept("\n\nГотово.");
        provider.reply.complete("Смотрю.\n\nГотово.");
        reply.get(TIMEOUT, TimeUnit.SECONDS);

        ChatMessage answer = last(session);
        assertEquals(7, answer.tools().get(0).at(), "сдвинут на убранные в начале переводы строк");
        assertEquals(List.of("Смотрю.", "Читает модуль Х — 10 строк", "Готово."), AnswerParts.split(answer.text(), answer.tools()).stream()
                .map(part -> part.isText() ? part.text() : part.tools().get(0).label()).toList());
    }

    @Test
    void answerPartsKeepLinesWhole() {
        ToolUse early = new ToolUse("1", "a", "Первый", "", ToolUse.Status.DONE, -1);
        ToolUse middle = new ToolUse("2", "b", "Второй", "", ToolUse.Status.DONE, 3);
        ToolUse end = new ToolUse("3", "c", "Третий", "", ToolUse.Status.DONE, 99);
        List<AnswerParts.Part> parts = AnswerParts.split("Строка раз\nСтрока два", List.of(early, middle, end));

        assertEquals(List.of("[Первый]", "Строка раз", "[Второй]", "Строка два", "[Третий]"), parts.stream()
                .map(part -> part.isText() ? part.text() : part.tools().stream().map(ToolUse::summary).toList().toString()).toList());
        assertEquals(List.of(), AnswerParts.split("", List.of()));
    }

    @Test
    void restoreReplacesHistory() {
        ChatSession session = new ChatSession(new ManualProvider());
        List<String> events = record(session);
        ChatMessage streaming = new ChatMessage(ChatRole.ASSISTANT, "обрыв", java.time.Instant.EPOCH, Status.STREAMING);

        session.restore(List.of(ChatMessage.of(ChatRole.USER, "вопрос"), streaming));

        assertEquals(List.of("USER:вопрос", "ASSISTANT:обрыв"), texts(session.messages()));
        assertEquals(Status.STOPPED, session.messages().get(1).status());
        assertEquals(java.time.Instant.EPOCH, session.messages().get(1).time());
        assertEquals(List.of("history"), events);
        assertTrue(session.canRetry());
    }

    @Test
    void retryWithoutUserMessageFails() {
        ChatSession session = new ChatSession(new ManualProvider());

        assertThrows(IllegalStateException.class, session::retry);
    }

    @Test
    void clearStopsRequest() throws Exception {
        ManualProvider provider = new ManualProvider();
        ChatSession session = new ChatSession(provider);

        CompletableFuture<ChatMessage> reply = session.send("вопрос");
        provider.part("часть");
        session.clear();

        assertTrue(provider.reply.isCancelled());
        reply.get(TIMEOUT, TimeUnit.SECONDS);
        assertTrue(session.messages().isEmpty());
        assertFalse(session.isBusy());
    }

    @Test
    void notifiesListeners() throws Exception {
        ChatSession session = new ChatSession(new EchoChatProvider(Duration.ZERO));
        List<String> events = record(session);

        session.send("x").get(TIMEOUT, TimeUnit.SECONDS);
        session.clear();

        assertEquals(List.of("added:USER", "busy:true", "added:ASSISTANT/STREAMING:Эхо: ", "updated:1/STREAMING:Эхо: x",
                "updated:1/DONE:Эхо: x", "busy:false", "history"), events);
        assertTrue(session.messages().isEmpty());
    }

    private static List<String> record(ChatSession session) {
        List<String> events = new ArrayList<>();
        session.addListener(new ChatListener() {
            @Override
            public synchronized void messageAdded(ChatMessage message) {
                events.add("added:" + message.role() + (message.role() == ChatRole.ASSISTANT ? "/" + message.status() + ":" + message.text() : ""));
            }

            @Override
            public synchronized void messageUpdated(int index, ChatMessage message) {
                events.add("updated:" + index + "/" + message.status() + ":" + message.text());
            }

            @Override
            public synchronized void historyChanged() {
                events.add("history");
            }

            @Override
            public synchronized void busyChanged(boolean busy) {
                events.add("busy:" + busy);
            }
        });
        return events;
    }

    private static ChatMessage last(ChatSession session) {
        List<ChatMessage> messages = session.messages();
        return messages.get(messages.size() - 1);
    }

    private static List<ChatRole> roles(ChatSession session) {
        return session.messages().stream().map(ChatMessage::role).toList();
    }

    private static List<String> texts(List<ChatMessage> messages) {
        return messages.stream().map(message -> message.role() + ":" + message.text()).toList();
    }

    private static ChatProvider failing(RuntimeException error) {
        return new ChatProvider() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public CompletableFuture<String> reply(List<ChatMessage> history, Consumer<String> onText) {
                return CompletableFuture.supplyAsync(() -> {
                    throw error;
                });
            }
        };
    }

    /** Провайдер, которым управляет тест: части ответа, завершение, ошибка. */
    /** Провайдер, который сообщает о вызовах инструментов. */
    private static final class ToolProvider implements ChatProvider {

        Consumer<String> text;
        Consumer<ToolUse> tool;
        CompletableFuture<String> reply;

        @Override
        public String name() {
            return "tools";
        }

        @Override
        public CompletableFuture<String> reply(List<ChatMessage> request, Consumer<String> consumer) {
            throw new AssertionError("сессия должна вызывать вариант с инструментами");
        }

        @Override
        public CompletableFuture<String> reply(List<ChatMessage> request, Consumer<String> onText, Consumer<ToolUse> onTool) {
            text = onText;
            tool = onTool;
            reply = new CompletableFuture<>();
            return reply;
        }
    }

    private static final class ManualProvider implements ChatProvider {

        List<ChatMessage> history;
        Consumer<String> onText;
        Consumer<ToolUse> onTool = use -> { };
        Consumer<TokenUsage> onUsage = usage -> { };
        CompletableFuture<String> reply;

        @Override
        public String name() {
            return "manual";
        }

        @Override
        public CompletableFuture<String> reply(List<ChatMessage> request, Consumer<String> consumer) {
            history = request;
            onText = consumer;
            reply = new CompletableFuture<>();
            return reply;
        }

        @Override
        public CompletableFuture<String> reply(List<ChatMessage> request, Consumer<String> text, Consumer<ToolUse> tool,
                Consumer<TokenUsage> usage) {
            onTool = tool;
            onUsage = usage;
            return reply(request, text);
        }

        void part(String text) {
            onText.accept(text);
        }

        void complete(String text) {
            reply.complete(text);
        }

        void fail(Throwable error) {
            reply.completeExceptionally(error);
        }
    }

    @Test
    void longHistoryIsCompactedIntoSummary() throws Exception {
        List<List<ChatMessage>> requests = new ArrayList<>();
        List<List<ChatMessage>> summarized = new ArrayList<>();
        ChatProvider provider = new ChatProvider() {
            @Override
            public String name() {
                return "сжатие";
            }

            @Override
            public CompletableFuture<String> reply(List<ChatMessage> history, Consumer<String> onText) {
                requests.add(history);
                return CompletableFuture.completedFuture("ответ " + requests.size());
            }

            @Override
            public int compactCount(List<ChatMessage> history) {
                // Сжимаем всё, кроме последнего вопроса, когда без краткого содержания больше трёх сообщений.
                return history.size() > 3 && history.get(0).role() != ChatRole.SUMMARY ? history.size() - 1 : 0;
            }

            @Override
            public CompletableFuture<String> summarize(List<ChatMessage> messages) {
                summarized.add(messages);
                return CompletableFuture.completedFuture("кратко: " + messages.size());
            }
        };
        ChatSession session = new ChatSession(provider);
        session.send("один").get(TIMEOUT, TimeUnit.SECONDS);
        session.send("два").get(TIMEOUT, TimeUnit.SECONDS);
        ChatMessage third = session.send("три").get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals("ответ 3", third.text());
        assertEquals(List.of(4), summarized.stream().map(List::size).toList());
        List<ChatMessage> messages = session.messages();
        assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.SUMMARY, ChatRole.USER,
                ChatRole.ASSISTANT), messages.stream().map(ChatMessage::role).toList());
        assertEquals("кратко: 4", messages.get(4).text());
        assertEquals("История сжата", messages.get(6).tools().get(0).summary());
        assertEquals(List.of(ChatRole.SUMMARY, ChatRole.USER), requests.get(2).stream().map(ChatMessage::role).toList(),
                "модель получает краткое содержание и последний вопрос");
        assertTrue(messages.get(4).modelText().startsWith(ChatMessage.SUMMARY_PREFIX));

        // Следующий вопрос: история — от краткого содержания, сжимать снова не нужно.
        session.send("четыре").get(TIMEOUT, TimeUnit.SECONDS);
        assertEquals(List.of(ChatRole.SUMMARY, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER),
                requests.get(3).stream().map(ChatMessage::role).toList());
    }

    @Test
    void failedSummaryDoesNotBreakAnswer() throws Exception {
        ChatProvider provider = new ChatProvider() {
            @Override
            public String name() {
                return "без сжатия";
            }

            @Override
            public CompletableFuture<String> reply(List<ChatMessage> history, Consumer<String> onText) {
                return CompletableFuture.completedFuture("ответ");
            }

            @Override
            public int compactCount(List<ChatMessage> history) {
                return history.size() > 1 ? history.size() - 1 : 0;
            }
        };
        ChatSession session = new ChatSession(provider);
        session.send("один").get(TIMEOUT, TimeUnit.SECONDS);
        ChatMessage answer = session.send("два").get(TIMEOUT, TimeUnit.SECONDS);

        assertEquals("ответ", answer.text());
        assertEquals(ToolUse.Status.FAILED, answer.tools().get(0).status());
        assertFalse(session.messages().stream().anyMatch(message -> message.role() == ChatRole.SUMMARY));
    }
}
