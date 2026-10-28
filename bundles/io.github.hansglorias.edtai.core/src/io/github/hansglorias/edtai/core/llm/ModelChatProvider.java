package io.github.hansglorias.edtai.core.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatProvider;
import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.SettingsStore;

/**
 * Ответы модели из настроек. Настройки и токен читаются при каждом запросе,
 * поэтому изменения применяются без перезапуска.
 */
public final class ModelChatProvider implements ChatProvider {

    /** Откуда брать подключение. */
    @FunctionalInterface
    public interface ConnectionSource {
        ModelConnection get() throws Exception;
    }

    private final ConnectionSource source;
    private final ModelClient client;

    public ModelChatProvider(ConnectionSource source, ModelClient client) {
        this.source = source;
        this.client = client;
    }

    /** Подключение из настроек рабочей области и токен из Secure Storage. */
    public static ModelChatProvider forStore(SettingsStore store, ModelClient client) {
        return new ModelChatProvider(() -> connection(store), client);
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
        ModelConnection connection;
        try {
            connection = source.get();
        } catch (ModelException e) {
            return CompletableFuture.failedFuture(e);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new ModelException("Не удалось прочитать настройки EDT AI: " + e.getMessage(), e));
        }
        return client.stream(connection, history, onText);
    }
}
