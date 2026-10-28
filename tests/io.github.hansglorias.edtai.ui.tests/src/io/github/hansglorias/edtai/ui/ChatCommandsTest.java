package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.settings.SettingsStore;

/** Команды через «/» и строка состояния: что делает модель, токены. */
class ChatCommandsTest extends AbstractChatTest {

    @Test
    void reviewCommandSendsPromptWithRemark() throws Exception {
        answering(List.of("Замечаний нет."));
        ChatView view = showView();

        ask(view, "/review  только модуль Общий");

        assertEquals(ChatView.REVIEW_PROMPT + "\n\nтолько модуль Общий", view.session().messages().get(0).text());
        assertEquals("", view.input().getText());
    }

    @Test
    void newAndAutoRunWithoutModel() throws Exception {
        answering(List.of("Привет."));
        ChatView view = showView();
        ask(view, "привет");

        view.input().setText("/new");
        view.send();
        assertEquals(List.of(), view.session().messages());
        assertEquals("", view.input().getText());
        assertEquals(1, server.requests().size(), "команда модели не отправляется");

        view.input().setText("/auto");
        view.send();
        assertTrue(autoApprove());
        assertTrue(view.status().getText().startsWith("Авторежим включён"), view.status().getText());
        view.input().setText("/AUTO");
        view.send();
        assertFalse(autoApprove());
    }

    @Test
    void statusShowsWhatModelDoesThenTokens() throws Exception {
        server = FakeModelServer.http().handler((request, response) -> response.sse(List.of(openAiPart("Раз, "), openAiPart("два."),
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1234,\"completion_tokens\":56}}\n\n", "data: [DONE]\n\n"), 300));
        configure(server);
        ChatView view = showView();
        assertTrue(view.status().getText().startsWith("test-model · "), view.status().getText());

        view.input().setText("Считай");
        view.send();
        waitUntil(() -> view.status().getText().matches("test-model · (думает|пишет ответ)… \\d+ с"), () -> view.status().getText());
        waitUntil(() -> !view.session().isBusy());
        settle();

        assertEquals("test-model · контекст 1,3k из 32k · ответ ↑1,2k ↓56", view.status().getText());
    }

    @Test
    void openedChatIsScrolledToEnd() throws Exception {
        answering(List.of("Ок."));
        ChatView view = showView();
        String id = view.chats().newId();
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            messages.add(ChatMessage.of(ChatRole.USER, "Вопрос " + i));
            messages.add(ChatMessage.of(ChatRole.ASSISTANT, "Ответ " + i + "\n\nстрока\nстрока\nстрока"));
        }
        view.chats().file(id).save(messages);

        view.openChatForTest(id);

        waitUntil(() -> view.history().getTopPixel() > 0
                && view.history().getLinePixel(view.history().getLineCount() - 1) < view.history().getClientArea().height,
                () -> "лента не внизу: " + view.history().getTopPixel());
    }

    private static boolean autoApprove() {
        return InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER).getBoolean(ChatView.AUTO_APPROVE, false);
    }
}
