package io.github.hansglorias.edtai.core.chat;

import java.util.Locale;
import java.util.Objects;

import io.github.hansglorias.edtai.core.settings.AiSettings;

/**
 * Модель, привязанная к чату, и сервер, на котором её выбрали. Чат отвечает
 * этой моделью, даже если в другом чате выбрали другую. Если в настройках
 * теперь другой сервер, привязка не действует: имя модели с прежнего сервера
 * там может не существовать.
 */
public record ChatModel(String model, String baseUrl) {

    public static final ChatModel NONE = new ChatModel("", "");

    public ChatModel {
        model = Objects.requireNonNullElse(model, "").strip();
        baseUrl = Objects.requireNonNullElse(baseUrl, "").strip();
    }

    /** Модель из настроек: её получает чат при первой отправке. */
    public static ChatModel of(AiSettings settings) {
        return new ChatModel(settings.model(), settings.baseUrl());
    }

    public boolean isEmpty() {
        return model.isEmpty();
    }

    /** Действует ли привязка при этих настройках: тот же сервер. */
    public boolean appliesTo(AiSettings settings) {
        return !isEmpty() && server(baseUrl).equals(server(settings.baseUrl()));
    }

    /** Настройки для запросов этого чата: с его моделью, если привязка действует. */
    public AiSettings apply(AiSettings settings) {
        return appliesTo(settings) && !model.equals(settings.model()) ? settings.withModel(model) : settings;
    }

    private static String server(String url) {
        String value = url.strip();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
