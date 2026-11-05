package io.github.hansglorias.edtai.core.chat;

import java.util.Locale;
import java.util.Objects;

import io.github.hansglorias.edtai.core.settings.AiSettings;

/**
 * Выбранная модель и стабильный id API-провайдера чата. У старых записей id
 * пустой: до миграции привязка действует на том же сервере; после — в исходном
 * профиле default. Подключение и токен разрешает SettingsStore.
 */
public record ChatModel(String model, String baseUrl, String providerId) {

    public ChatModel(String model, String baseUrl) { this(model, baseUrl, ""); }

    public static final ChatModel NONE = new ChatModel("", "");

    public ChatModel {
        providerId = Objects.requireNonNullElse(providerId, "");
        model = Objects.requireNonNullElse(model, "").strip();
        baseUrl = Objects.requireNonNullElse(baseUrl, "").strip();
    }

    /** Модель из настроек: её получает чат при первой отправке. */
    public static ChatModel of(AiSettings settings) {
        return new ChatModel(settings.model(), settings.baseUrl(), settings.providerId());
    }

    public boolean isEmpty() {
        return model.isEmpty();
    }

    /** Тот же профиль; для старых записей — тот же адрес сервера. */
    public boolean appliesTo(AiSettings settings) {
        return !isEmpty() && (providerId.isEmpty() ? server(baseUrl).equals(server(settings.baseUrl()))
                : providerId.equals(settings.providerId()));
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
