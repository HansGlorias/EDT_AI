package io.github.hansglorias.edtai.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.context.ContextItem;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;

class ChatTitleTest {

    @Test
    void firstQuestionSurvivesLargeContextAndSkill() {
        ChatMessage question = new ChatMessage(ChatRole.USER, "Проверь расчёт скидки", Instant.now(), ChatMessage.Status.DONE,
                List.of(ContextItem.of("Тест", "src/CommonModules/Скидки/Module.bsl", 1, 1, 1, "КОД_МОДУЛЯ".repeat(4000))),
                List.of(), List.of(Attachment.skill("review", "ИНСТРУКЦИЯ_СКИЛЛА".repeat(4000))));
        String task = ModelChatProvider.titleTask(List.of(question, ChatMessage.of(ChatRole.ASSISTANT, "Сейчас посмотрю")));

        assertTrue(task.contains("Проверь расчёт скидки"), task);
        assertTrue(task.contains("ОбщийМодуль.Скидки.Модуль"), task);
        assertTrue(task.contains("скилл review"), task);
        assertFalse(task.contains("КОД_МОДУЛЯ"));
        assertFalse(task.contains("ИНСТРУКЦИЯ_СКИЛЛА"));
        assertFalse(task.contains("Сейчас посмотрю"));
        assertTrue(task.length() <= 3000);
    }

    @Test
    void longQuestionKeepsBeginningAndFinalRequest() {
        String task = ModelChatProvider.titleTask(List.of(ChatMessage.of(ChatRole.USER,
                "Ошибка проведения реализации\n" + "длинное описание ".repeat(1000) + "\nИсправь расчёт НДС")));
        assertTrue(task.contains("Ошибка проведения реализации"), task);
        assertTrue(task.endsWith("Исправь расчёт НДС"), task);
        assertTrue(task.length() <= 3000);
    }

    @Test
    void titleUsesOwnSystemPromptAndOnlyFirstQuestionInBothProtocols() throws Exception {
        for (Protocol protocol : Protocol.values()) {
            try (FakeModelServer server = FakeModelServer.http()) {
                server.handler((request, response) -> response.send(200, "application/json", protocol == Protocol.ANTHROPIC
                        ? "{\"content\":[{\"type\":\"text\",\"text\":\"Ревью модуля обмена\"}]}"
                        : "{\"choices\":[{\"message\":{\"content\":\"Ревью модуля обмена\"}}]}"));
                // Даже большой пользовательский промпт не нужен для служебной задачи.
                ModelConnection connection = new ModelConnection(protocol, server.url(), "test-model", "", 4096,
                        "СИСТЕМНАЯ_ИНСТРУКЦИЯ".repeat(5000), new TrustSettings(false, Optional.empty()), 8192);
                ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient());
                String title = provider.title(List.of(ChatMessage.of(ChatRole.USER, "Проведи ревью модуля обмена"),
                        ChatMessage.of(ChatRole.ASSISTANT, "СЛУЖЕБНОЕ_ВСТУПЛЕНИЕ"),
                        ChatMessage.of(ChatRole.USER, "СЛЕДУЮЩИЙ_ВОПРОС"))).get(10, TimeUnit.SECONDS);

                assertEquals("Ревью модуля обмена", title);
                String body = server.requests().get(0).body();
                assertTrue(body.contains("Проведи ревью модуля обмена"), body);
                assertTrue(body.contains("конкретное действие или проблему"), body);
                assertFalse(body.contains("СИСТЕМНАЯ_ИНСТРУКЦИЯ"));
                assertFalse(body.contains("СЛУЖЕБНОЕ_ВСТУПЛЕНИЕ"));
                assertFalse(body.contains("СЛЕДУЮЩИЙ_ВОПРОС"));
                assertFalse(Json.parseObject(body).containsKey("tools"));
                assertEquals((long) ModelClient.SERVICE_TOKENS, Json.at(Json.parse(body), "max_tokens"));
            }
        }
    }

    @Test
    void noQuestionDoesNotSendRequest() throws Exception {
        ModelChatProvider provider = new ModelChatProvider(() -> new ModelConnection(Protocol.OPENAI_COMPATIBLE,
                "http://localhost:1", "test-model", "", 0, "", new TrustSettings(false, Optional.empty())), new ModelClient());
        assertEquals("", provider.title(List.of(ChatMessage.of(ChatRole.ASSISTANT, "Ответ"))).get(1, TimeUnit.SECONDS));
    }

    @Test
    void cancellationReachesServiceRequest() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        try (FakeModelServer server = FakeModelServer.http()) {
            server.handler((request, response) -> {
                received.countDown();
                release.await(10, TimeUnit.SECONDS);
                response.send(200, "application/json", "{}");
            });
            ModelConnection connection = new ModelConnection(Protocol.OPENAI_COMPATIBLE, server.url(), "test-model", "", 0,
                    "", new TrustSettings(false, Optional.empty()));
            ModelChatProvider provider = new ModelChatProvider(() -> connection, new ModelClient(event -> cancelled.countDown()));
            var title = provider.title(List.of(ChatMessage.of(ChatRole.USER, "Ревью обмена")));
            assertTrue(received.await(5, TimeUnit.SECONDS));
            title.cancel(true);
            assertTrue(cancelled.await(5, TimeUnit.SECONDS), "отмена названия завершает HTTP-запрос и диагностику");
        } finally {
            release.countDown();
        }
    }

    @Test
    void codeFenceAndFormattedPrefixAreCleaned() {
        assertEquals("Ревью модуля обмена", ModelChatProvider.cleanTitle("```text\n**Название чата:** «Ревью модуля обмена».\n```"));
        assertEquals("Расчёт НДС", ModelChatProvider.cleanTitle("Заголовок: Расчёт НДС"));
    }
}
