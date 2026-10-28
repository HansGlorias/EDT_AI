package io.github.hansglorias.edtai.core.llm;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tls.TrustSettings;

/**
 * Всё, что нужно для запроса к модели.
 *
 * @param token     токен, пустая строка — без авторизации. Не попадает в {@link #toString()}
 * @param maxTokens    ограничение длины ответа, 0 — по умолчанию для протокола
 * @param systemPrompt системный промпт, пустая строка — без него
 * @param window       окно модели в токенах
 */
public record ModelConnection(
        Protocol protocol,
        String baseUrl,
        String model,
        String token,
        int maxTokens,
        String systemPrompt,
        TrustSettings trust,
        int window) {

    /** Anthropic требует max_tokens; столько, если в настройках не задано. */
    public static final int ANTHROPIC_DEFAULT_MAX_TOKENS = 8192;

    /** Последний сегмент пути — версия API: {@code v1}, {@code v1beta}, {@code v4}. */
    private static final Pattern VERSION_SEGMENT = Pattern.compile(".*/v\\d+[a-z0-9]*$");

    public ModelConnection {
        Objects.requireNonNull(protocol, "protocol");
        baseUrl = Objects.requireNonNullElse(baseUrl, "").strip();
        model = Objects.requireNonNullElse(model, "").strip();
        token = Objects.requireNonNullElse(token, "").strip();
        maxTokens = Math.max(0, maxTokens);
        systemPrompt = Objects.requireNonNullElse(systemPrompt, "").strip();
        Objects.requireNonNull(trust, "trust");
        window = window <= 0 ? AiSettings.DEFAULT_MODEL_WINDOW : window;
    }

    /** С окном модели по умолчанию. */
    public ModelConnection(Protocol protocol, String baseUrl, String model, String token, int maxTokens, String systemPrompt,
            TrustSettings trust) {
        this(protocol, baseUrl, model, token, maxTokens, systemPrompt, trust, AiSettings.DEFAULT_MODEL_WINDOW);
    }

    public static ModelConnection of(AiSettings settings, String token) {
        return new ModelConnection(settings.protocol(), settings.baseUrl(), settings.model(), token,
                settings.maxTokens(), settings.systemPrompt(), TrustSettings.from(settings), settings.modelWindow());
    }

    public ModelConnection withMaxTokens(int value) {
        return new ModelConnection(protocol, baseUrl, model, token, value, systemPrompt, trust, window);
    }

    /**
     * Адрес метода чата. Можно указать сервер ({@code https://host}), сервер с
     * версией API ({@code https://host/v1}) или полный адрес метода.
     *
     * @throws ModelException если адрес некорректен
     */
    public URI endpoint() throws ModelException {
        String method = protocol == Protocol.ANTHROPIC ? "/messages" : "/chat/completions";
        String base = baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(base);
        } catch (URISyntaxException e) {
            throw new ModelException("Некорректный адрес сервера: " + baseUrl);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            throw new ModelException("Некорректный адрес сервера: «" + baseUrl + "». Нужен адрес вида https://сервер");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String fullPath;
        if (path.endsWith(method)) {
            fullPath = path;
        } else if (VERSION_SEGMENT.matcher(path).matches()) {
            fullPath = path + method;
        } else {
            fullPath = path + "/v1" + method;
        }
        try {
            return new URI(scheme + "://" + uri.getRawAuthority() + fullPath
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        } catch (URISyntaxException e) {
            throw new ModelException("Некорректный адрес сервера: " + baseUrl);
        }
    }

    @Override
    public String toString() {
        return "ModelConnection[protocol=" + protocol + ", baseUrl=" + baseUrl + ", model=" + model
                + ", token=" + (token.isEmpty() ? "нет" : "***") + ", maxTokens=" + maxTokens + ", systemPrompt=" + systemPrompt.length() + " символов, trust=" + trust + ", window=" + window + "]";
    }
}
