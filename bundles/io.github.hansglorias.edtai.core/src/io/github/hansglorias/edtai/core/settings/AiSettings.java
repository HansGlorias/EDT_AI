package io.github.hansglorias.edtai.core.settings;

import java.util.List;
import java.util.Objects;

/**
 * Настройки подключения к модели. Токена здесь нет: он хранится отдельно,
 * в Secure Storage (см. {@link SettingsStore}).
 *
 * @param protocol            протокол сервера модели
 * @param baseUrl             адрес сервера, например {@code https://llm.example.invalid}
 * @param model               имя модели
 * @param maxTokens           ограничение длины ответа в токенах, 0 — по умолчанию для протокола
 * @param useSystemTrustStore доверять корневым сертификатам ОС в дополнение к JDK
 * @param caCertificateFile   путь к дополнительному PEM-файлу с сертификатами CA, пусто — нет
 * @param mcpServers          MCP-серверы
 */
public record AiSettings(
        Protocol protocol,
        String baseUrl,
        String model,
        int maxTokens,
        boolean useSystemTrustStore,
        String caCertificateFile,
        List<McpServer> mcpServers) {

    public static final Protocol DEFAULT_PROTOCOL = Protocol.OPENAI_COMPATIBLE;
    public static final boolean DEFAULT_USE_SYSTEM_TRUST_STORE = true;
    public static final int MAX_TOKENS_LIMIT = 1_000_000;

    public AiSettings {
        protocol = Objects.requireNonNullElse(protocol, DEFAULT_PROTOCOL);
        baseUrl = Objects.requireNonNullElse(baseUrl, "").strip();
        model = Objects.requireNonNullElse(model, "").strip();
        maxTokens = Math.max(0, maxTokens);
        caCertificateFile = Objects.requireNonNullElse(caCertificateFile, "").strip();
        mcpServers = List.copyOf(Objects.requireNonNullElse(mcpServers, List.of()));
    }

    public static AiSettings defaults() {
        return new AiSettings(DEFAULT_PROTOCOL, "", "", 0, DEFAULT_USE_SYSTEM_TRUST_STORE, "", List.of());
    }

    /** Заданы ли адрес и модель — минимум, чтобы отправлять запросы. */
    public boolean isConfigured() {
        return !baseUrl.isEmpty() && !model.isEmpty();
    }
}
