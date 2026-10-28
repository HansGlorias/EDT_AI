package io.github.hansglorias.edtai.core.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatProvider;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.SettingsStore;
import io.github.hansglorias.edtai.core.tools.ToolApprover;
import io.github.hansglorias.edtai.core.tools.ToolSource;

/**
 * Ответы модели из настроек. Настройки и токен читаются при каждом запросе,
 * поэтому изменения применяются без перезапуска. Если есть инструменты —
 * модель может их вызывать (цикл агента, {@link AgentLoop}).
 */
public final class ModelChatProvider implements ChatProvider {

    /** Откуда брать подключение. */
    @FunctionalInterface
    public interface ConnectionSource {
        ModelConnection get() throws Exception;
    }

    private final ConnectionSource source;
    private final ModelClient client;
    private final ToolSource tools;
    private final ToolApprover approver;
    /** Серверы (адрес и модель), которые отклонили запрос с инструментами: им их больше не передаём. */
    private final Set<String> withoutTools = ConcurrentHashMap.newKeySet();

    public ModelChatProvider(ConnectionSource source, ModelClient client) {
        this(source, client, ToolSource.NONE, ToolApprover.ALLOW_ALL);
    }

    /**
     * @param tools    инструменты для очередного ответа; {@link ToolSource#NONE} — без инструментов
     * @param approver спрашивает пользователя перед вызовом инструментов, которым нужно подтверждение
     */
    public ModelChatProvider(ConnectionSource source, ModelClient client, ToolSource tools, ToolApprover approver) {
        this.source = source;
        this.client = client;
        this.tools = tools;
        this.approver = approver;
    }

    /** Подключение из настроек рабочей области и токен из Secure Storage. */
    public static ModelChatProvider forStore(SettingsStore store, ModelClient client) {
        return new ModelChatProvider(() -> connection(store), client);
    }

    /** То же с инструментами. */
    public static ModelChatProvider forStore(SettingsStore store, ModelClient client, ToolSource tools, ToolApprover approver) {
        return new ModelChatProvider(() -> connection(store), client, tools, approver);
    }

    /**
     * @throws ModelException если адрес или модель не заданы
     */
    public static ModelConnection connection(SettingsStore store) throws Exception {
        AiSettings settings = store.load();
        if (!settings.isConfigured()) {
            throw new ModelException("Модель не настроена: укажите адрес сервера и модель в настройках EDT AI.");
        }
        // Без сохранённого токена Secure Storage не открываем: иначе он может спросить мастер-пароль.
        String token = store.hasToken() ? store.loadToken() : "";
        return ModelConnection.of(settings, token);
    }

    /** Настройки изменились: снова пробовать инструменты с серверами, которые их отклоняли. */
    public void forgetRejections() {
        withoutTools.clear();
    }

    @Override
    public String name() {
        return "модель";
    }

    @Override
    public CompletableFuture<String> reply(List<ChatMessage> history, Consumer<String> onText) {
        return reply(history, onText, use -> { });
    }

    @Override
    public CompletableFuture<String> reply(List<ChatMessage> history, Consumer<String> onText, Consumer<ToolUse> onTool) {
        ModelConnection connection;
        try {
            connection = source.get();
        } catch (ModelException e) {
            return CompletableFuture.failedFuture(e);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new ModelException("Не удалось прочитать настройки EDT AI: " + e.getMessage(), e));
        }
        connection = withBrief(connection);
        String server = connection.baseUrl() + "|" + connection.model();
        if (tools == ToolSource.NONE || withoutTools.contains(server)) {
            ModelConnection plain = connection;
            RequestBudget.Fitted fitted = RequestBudget.fit(history,
                    part -> RequestBudget.size(plain, part, List.of(), List.of()), RequestBudget.chars(plain));
            if (fitted.trimmed()) {
                onTool.accept(fitted.notice());
            }
            return client.stream(plain, fitted.history(), onText);
        }
        AgentLoop loop = new AgentLoop(client, connection, history, tools, approver, onText, onTool);
        CompletableFuture<String> reply = loop.start();
        reply.whenComplete((answer, error) -> {
            if (loop.toolsRejected()) {
                withoutTools.add(server);
            }
        });
        return reply;
    }

    /**
     * Сжимать, когда история занимает больше половины бюджета запроса
     * (остальное — системный промпт, инструменты и их результаты). Сжимаются
     * целые обмены от начала; остаются последние, занимающие до четверти
     * бюджета, и всегда последний вопрос.
     */
    @Override
    public int compactCount(List<ChatMessage> history) {
        ModelConnection connection;
        try {
            connection = source.get();
        } catch (Exception e) {
            return 0;
        }
        int budget = RequestBudget.chars(connection);
        if (history.size() < 3 || RequestBudget.size(connection, history, List.of(), List.of()) <= budget / 2) {
            return 0;
        }
        int keep = history.size() - 1;
        while (keep > 0 && RequestBudget.size(connection, history.subList(keep - 1, history.size()), List.of(), List.of()) <= budget / 4) {
            keep--;
        }
        while (keep < history.size() - 1 && history.get(keep).role() != ChatRole.USER) {
            keep++;
        }
        return keep >= 2 ? keep : 0;
    }

    @Override
    public CompletableFuture<String> summarize(List<ChatMessage> messages) {
        ModelConnection connection;
        try {
            connection = source.get();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        String transcript = transcript(messages, RequestBudget.chars(connection) * 6 / 10);
        ChatMessage prompt = ChatMessage.of(ChatRole.USER, SUMMARY_PROMPT + "\n\n" + transcript);
        return client.stream(connection.withMaxTokens(SUMMARY_TOKENS), List.of(prompt), part -> { });
    }

    static final String SUMMARY_PROMPT = "Сожми переписку ниже в краткое содержание, по которому можно продолжить работу"
            + " (до 400 слов): что просил пользователь и зачем; принятые решения и договорённости; какие модули, объекты"
            + " метаданных и формы обсуждали и меняли (с путями); результаты и выводы; что осталось сделать. По делу, без"
            + " вступлений и без кода, кроме коротких имён.";
    private static final int SUMMARY_TOKENS = 2048;
    private static final int MAX_MESSAGE = 4000;

    /** Переписка текстом; не влезает в {@code limit} — без самого начала. */
    static String transcript(List<ChatMessage> messages, int limit) {
        List<String> parts = new ArrayList<>();
        for (ChatMessage message : messages) {
            String who = switch (message.role()) {
                case USER -> "Пользователь";
                case ASSISTANT -> "Ассистент";
                default -> "Ранее";
            };
            String text = message.modelText();
            if (text.length() > MAX_MESSAGE) {
                text = text.substring(0, MAX_MESSAGE) + " …(обрезано)";
            }
            parts.add(who + ": " + text);
        }
        StringBuilder result = new StringBuilder();
        for (int i = parts.size() - 1; i >= 0 && result.length() + parts.get(i).length() < limit; i--) {
            result.insert(0, parts.get(i) + "\n\n");
        }
        return result.toString().strip();
    }

    /** Сведения о проекте в конце системного промпта. */
    private ModelConnection withBrief(ModelConnection connection) {
        try {
            return connection.withSystemPromptPart(tools.brief());
        } catch (RuntimeException e) {
            // Сведения о проекте — подсказка модели; без них ответ всё равно возможен.
            return connection;
        }
    }
}
