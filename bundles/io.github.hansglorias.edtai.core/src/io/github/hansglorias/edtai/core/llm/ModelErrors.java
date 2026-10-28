package io.github.hansglorias.edtai.core.llm;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.json.JsonException;

/**
 * Превращает сетевые, TLS- и HTTP-ошибки в сообщения для пользователя.
 */
final class ModelErrors {

    private static final int MAX_SERVER_TEXT = 300;
    /** Тексты переполнения контекста у OpenAI, vLLM, Anthropic, llama.cpp, шлюзов. */
    static final Pattern CONTEXT_OVERFLOW = Pattern.compile("(?i)context[_ ]length|maximum context|context window"
            + "|max_model_len|too many tokens|prompt is too long|input is too long|exceeds? the (?:model'?s? )?(?:context|maximum)"
            + "|exceed_context_size");
    private static final Pattern HTML_TITLE = Pattern.compile("(?is)<title>(.*?)</title>");

    private ModelErrors() {
    }

    /** Ответ сервера с кодом не 2xx. */
    static ModelException http(int status, String body, URI uri) {
        String where = shown(uri);
        String text = switch (status) {
            case 400 -> "Сервер отклонил запрос (HTTP 400). Проверьте имя модели и протокол.";
            case 401 -> "Сервер не принял токен (HTTP 401). Проверьте токен в настройках EDT AI.";
            case 403 -> "Доступ запрещён (HTTP 403). Проверьте токен и права на модель.";
            case 404 -> "Адрес не найден (HTTP 404): " + where + ". Проверьте адрес сервера, протокол и имя модели.";
            case 405 -> "Сервер не поддерживает этот метод (HTTP 405): " + where + ". Возможно, выбран не тот протокол.";
            case 408, 504 -> "Сервер не дождался ответа модели (HTTP " + status + ").";
            case 413 -> "Запрос слишком большой для сервера (HTTP 413).";
            case 429 -> "Превышен лимит запросов (HTTP 429). Подождите и повторите.";
            default -> status >= 500
                    ? "Ошибка на стороне сервера (HTTP " + status + ")."
                    : "Сервер ответил HTTP " + status + ".";
        };
        String server = serverText(body);
        if (status / 100 == 4 && body != null && CONTEXT_OVERFLOW.matcher(body).find()) {
            return ModelException.overflow("Диалог не помещается в окно модели (HTTP " + status + "). Начните новый чат или проверьте"
                    + " «Окно модели» в настройках EDT AI: оно должно быть не больше, чем у модели на сервере."
                    + (server == null ? "" : " Ответ сервера: «" + server + "»"));
        }
        // vLLM без --enable-auto-tool-choice, старые шлюзы: 400/422 с упоминанием tools в тексте ошибки.
        boolean rejectsTools = (status == 400 || status == 422) && body != null && body.toLowerCase(Locale.ROOT).contains("tool");
        return new ModelException(server == null ? text : text + " Ответ сервера: «" + server + "»", null, rejectsTools);
    }

    /**
     * Исключение при подключении или чтении ответа.
     *
     * @param idle сколько ждали данных, если поток закрыт из-за простоя; иначе {@code null}
     */
    static ModelException of(Throwable error, URI uri, Duration connectTimeout, Duration responseTimeout, Duration idle) {
        if (error instanceof ModelException model) {
            return model;
        }
        String host = uri.getHost();
        String hostPort = host + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        if (idle != null) {
            return new ModelException("Сервер перестал присылать ответ: нет данных " + seconds(idle) + ".", error);
        }
        if (error instanceof JsonException) {
            return new ModelException("Сервер прислал ответ, который не удалось разобрать: " + error.getMessage(), error);
        }
        if (error instanceof HttpConnectTimeoutException) {
            return new ModelException("Не удалось подключиться к " + hostPort + ": нет ответа за " + seconds(connectTimeout)
                    + ". Проверьте адрес и доступность сервера (сеть, прокси).", error);
        }
        if (error instanceof HttpTimeoutException) {
            return new ModelException("Сервер " + hostPort + " не начал отвечать за " + seconds(responseTimeout) + ".", error);
        }
        if (has(error, UnresolvedAddressException.class) || has(error, UnknownHostException.class)) {
            return new ModelException("Не найден сервер " + host + ". Проверьте адрес в настройках EDT AI.", error);
        }
        SSLException ssl = find(error, SSLException.class);
        if (ssl != null) {
            return tls(error, ssl, uri, hostPort);
        }
        if (error instanceof ConnectException || has(error, ConnectException.class)) {
            return new ModelException("Не удалось подключиться к " + hostPort
                    + ": соединение отклонено. Проверьте адрес и порт и что сервер запущен.", error);
        }
        String message = String.valueOf(error.getMessage());
        if (message.contains("header parser received no bytes") || message.contains("Invalid status line")) {
            String hint = "http".equalsIgnoreCase(uri.getScheme()) ? " Возможно, нужен адрес с https://." : "";
            return new ModelException("Сервер " + hostPort + " закрыл соединение, не ответив." + hint, error);
        }
        if (error instanceof IOException) {
            return new ModelException("Соединение с сервером " + hostPort + " прервано: " + describe(error), error);
        }
        return new ModelException("Ошибка запроса к модели: " + describe(error), error);
    }

    private static ModelException tls(Throwable error, SSLException ssl, URI uri, String hostPort) {
        String host = uri.getHost();
        String all = messages(error).toLowerCase(Locale.ROOT);
        if (has(error, CertificateExpiredException.class) || has(error, CertificateNotYetValidException.class)
                || all.contains("validity check failed") || all.contains("certificate expired")) {
            return new ModelException("Сертификат сервера " + host + " просрочен или ещё не действует. Проверьте дату и время"
                    + " на компьютере; если они верные — сертификат нужно обновить на сервере.", error);
        }
        if (all.contains("no subject alternative") || all.contains("no name matching")) {
            return new ModelException("Сертификат сервера выдан не на имя " + host + ". Укажите в адресе то имя сервера,"
                    + " на которое выдан сертификат.", error);
        }
        if (has(error, CertPathBuilderException.class) || has(error, CertPathValidatorException.class)
                || all.contains("pkix path") || all.contains("unable to find valid certification path")) {
            return new ModelException("Сертификату сервера " + host + " нет доверия. Укажите в настройках EDT AI файл"
                    + " с корневым сертификатом организации (PEM) или включите доверие сертификатам ОС.", error);
        }
        if (all.contains("unrecognized ssl message") || all.contains("plaintext connection")) {
            return new ModelException("Сервер " + hostPort + " не отвечает по HTTPS. Возможно, нужен адрес с http://.", error);
        }
        return new ModelException("Ошибка TLS при подключении к " + hostPort + ": " + describe(ssl), error);
    }

    /** Убирает токен из текста, если он туда как-то попал. */
    static String redact(String text, String token) {
        if (text == null || token == null || token.length() < 4) {
            return text;
        }
        return text.replace(token, "***");
    }

    /** Адрес без запроса и учётных данных — для сообщений. */
    static String shown(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + uri.getRawPath();
    }

    /** Короткий текст ошибки из тела ответа: из JSON, из заголовка HTML-страницы или сам текст. */
    static String serverText(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String text = null;
        try {
            text = ProtocolAdapter.errorText(Json.parse(body));
        } catch (JsonException e) {
            Matcher title = HTML_TITLE.matcher(body);
            if (title.find()) {
                text = title.group(1);
            } else if (!body.stripLeading().startsWith("<")) {
                text = body;
            }
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        text = text.strip().replaceAll("\\s+", " ");
        return text.length() > MAX_SERVER_TEXT ? text.substring(0, MAX_SERVER_TEXT) + "…" : text;
    }

    private static String seconds(Duration duration) {
        return duration.toSeconds() + " с";
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static String messages(Throwable error) {
        StringBuilder out = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            out.append(cause.getClass().getName()).append(": ").append(cause.getMessage()).append('\n');
        }
        return out.toString();
    }

    private static boolean has(Throwable error, Class<? extends Throwable> type) {
        return find(error, type) != null;
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }
}
