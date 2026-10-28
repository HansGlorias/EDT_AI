package io.github.hansglorias.edtai.core.llm;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatProvider;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.SettingsStore;
import io.github.hansglorias.edtai.core.tools.Tool;

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
    private final Supplier<List<Tool>> tools;
    /** Серверы (адрес и модель), которые отклонили запрос с инструментами: им их больше не передаём. */
    private final Set<String> withoutTools = ConcurrentHashMap.newKeySet();

    public ModelChatProvider(ConnectionSource source, ModelClient client) {
        this(source, client, List::of);
    }

    /** @param tools инструменты для очередного запроса; пустой список — без инструментов */
    public ModelChatProvider(ConnectionSource source, ModelClient client, Supplier<List<Tool>> tools) {
        this.source = source;
        this.client = client;
        this.tools = tools;
    }

    /** Подключение из настроек рабочей области и токен из Secure Storage. */
    public static ModelChatProvider forStore(SettingsStore store, ModelClient client) {
        return new ModelChatProvider(() -> connection(store), client);
    }

    /** То же с инструментами. */
    public static ModelChatProvider forStore(SettingsStore store, ModelClient client, Supplier<List<Tool>> tools) {
        return new ModelChatProvider(() -> connection(store), client, tools);
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
        String server = connection.baseUrl() + "|" + connection.model();
        List<Tool> available = withoutTools.contains(server) ? List.of() : tools.get();
        if (available.isEmpty()) {
            return client.stream(connection, history, onText);
        }
        AgentLoop loop = new AgentLoop(client, connection, history, available, onText, onTool);
        CompletableFuture<String> reply = loop.start();
        reply.whenComplete((answer, error) -> {
            if (loop.toolsRejected()) {
                withoutTools.add(server);
            }
        });
        return reply;
    }
}
