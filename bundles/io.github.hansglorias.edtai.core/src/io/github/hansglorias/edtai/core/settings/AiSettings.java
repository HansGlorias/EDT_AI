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
 * @param systemPrompt        системный промпт, пустая строка — без него
 * @param contextLimit        сколько символов кода из EDT прикреплять к одному сообщению, 0 — по умолчанию
 * @param toolsEnabled        давать модели инструменты чтения проекта
 * @param useSystemTrustStore доверять корневым сертификатам ОС в дополнение к JDK
 * @param caCertificateFile   путь к дополнительному PEM-файлу с сертификатами CA, пусто — нет
 * @param mcpServers          MCP-серверы
 * @param modelWindow         окно модели в токенах: сколько помещается в запрос вместе с ответом, 0 — по имени модели
 */
public record AiSettings(
        Protocol protocol,
        String baseUrl,
        String model,
        int maxTokens,
        String systemPrompt,
        int contextLimit,
        boolean toolsEnabled,
        boolean useSystemTrustStore,
        String caCertificateFile,
        List<McpServer> mcpServers,
        int modelWindow) {

    public static final Protocol DEFAULT_PROTOCOL = Protocol.OPENAI_COMPATIBLE;
    public static final boolean DEFAULT_USE_SYSTEM_TRUST_STORE = true;
    public static final boolean DEFAULT_TOOLS_ENABLED = true;
    public static final int MAX_TOKENS_LIMIT = 1_000_000;
    /**
     * Лимит контекста по умолчанию: около 15–25 тысяч токенов кириллицы —
     * с запасом для моделей с небольшим окном.
     */
    public static final int DEFAULT_CONTEXT_LIMIT = 40_000;
    public static final int MIN_CONTEXT_LIMIT = 1_000;
    public static final int MAX_CONTEXT_LIMIT = 2_000_000;
    /** Окно модели не задано: определяется по её имени. */
    public static final int AUTO_MODEL_WINDOW = 0;
    /** Окно незнакомой модели: 32 тыс. токенов — типично для локальных моделей. */
    public static final int DEFAULT_MODEL_WINDOW = 32_768;
    public static final int MIN_MODEL_WINDOW = 2_048;
    public static final int MAX_MODEL_WINDOW = 10_000_000;
    public static final String DEFAULT_SYSTEM_PROMPT = "Ты — помощник разработчика на платформе 1С:Предприятие 8 в среде"
            + " 1C:EDT. Отвечай по-русски, кратко и по делу. Код пиши на встроенном языке 1С в блоках ```bsl, с русскими"
            + " ключевыми словами, по стандартам разработки 1С. Если не уверен в ответе — так и скажи.";

    public AiSettings {
        protocol = Objects.requireNonNullElse(protocol, DEFAULT_PROTOCOL);
        baseUrl = Objects.requireNonNullElse(baseUrl, "").strip();
        model = Objects.requireNonNullElse(model, "").strip();
        maxTokens = Math.max(0, maxTokens);
        systemPrompt = Objects.requireNonNullElse(systemPrompt, "").strip();
        contextLimit = contextLimit <= 0 ? DEFAULT_CONTEXT_LIMIT : contextLimit;
        caCertificateFile = Objects.requireNonNullElse(caCertificateFile, "").strip();
        mcpServers = List.copyOf(Objects.requireNonNullElse(mcpServers, List.of()));
        modelWindow = Math.max(AUTO_MODEL_WINDOW, modelWindow);
    }

    /** С окном модели по умолчанию. */
    public AiSettings(Protocol protocol, String baseUrl, String model, int maxTokens, String systemPrompt, int contextLimit,
            boolean toolsEnabled, boolean useSystemTrustStore, String caCertificateFile, List<McpServer> mcpServers) {
        this(protocol, baseUrl, model, maxTokens, systemPrompt, contextLimit, toolsEnabled, useSystemTrustStore, caCertificateFile,
                mcpServers, AUTO_MODEL_WINDOW);
    }

    public static AiSettings defaults() {
        return new AiSettings(DEFAULT_PROTOCOL, "", "", 0, DEFAULT_SYSTEM_PROMPT, DEFAULT_CONTEXT_LIMIT, DEFAULT_TOOLS_ENABLED,
                DEFAULT_USE_SYSTEM_TRUST_STORE, "", List.of());
    }

    /** Заданы ли адрес и модель — минимум, чтобы отправлять запросы. */
    public boolean isConfigured() {
        return !baseUrl.isEmpty() && !model.isEmpty();
    }
}
