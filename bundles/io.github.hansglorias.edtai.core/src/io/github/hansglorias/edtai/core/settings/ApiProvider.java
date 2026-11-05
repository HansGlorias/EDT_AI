package io.github.hansglorias.edtai.core.settings;

import java.util.Objects;

/** Именованное подключение API. Секрет хранится отдельно по стабильному id. */
public record ApiProvider(String id, String name, Protocol protocol, String baseUrl, String model,
        int maxTokens, int modelWindow, boolean useSystemTrustStore, String caCertificateFile) {

    public static final String DEFAULT_ID = "default";

    public ApiProvider {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("Некорректный идентификатор провайдера");
        }
        name = Objects.requireNonNullElse(name, "").strip();
        if (name.isEmpty()) throw new IllegalArgumentException("Укажите имя провайдера");
        protocol = Objects.requireNonNullElse(protocol, AiSettings.DEFAULT_PROTOCOL);
        baseUrl = Objects.requireNonNullElse(baseUrl, "").strip();
        model = Objects.requireNonNullElse(model, "").strip();
        caCertificateFile = Objects.requireNonNullElse(caCertificateFile, "").strip();
    }

    public static ApiProvider of(String id, String name, AiSettings settings) {
        return new ApiProvider(id, name, settings.protocol(), settings.baseUrl(), settings.model(), settings.maxTokens(),
                settings.modelWindow(), settings.useSystemTrustStore(), settings.caCertificateFile());
    }

    /** Общие настройки чата и MCP остаются общими; подключение берётся из профиля. */
    public AiSettings apply(AiSettings shared) {
        return new AiSettings(protocol, baseUrl, model, maxTokens, shared.systemPrompt(), shared.contextLimit(),
                shared.toolsEnabled(), useSystemTrustStore, caCertificateFile, shared.mcpServers(), modelWindow,
                shared.mcpCheckMinutes(), id);
    }
}
