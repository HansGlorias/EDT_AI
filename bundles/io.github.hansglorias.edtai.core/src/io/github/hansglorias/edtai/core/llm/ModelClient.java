package io.github.hansglorias.edtai.core.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.tls.TlsContextFactory;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.Tool;

/**
 * Запросы к модели по HTTP со стримингом. Каждый запрос выполняется в своём
 * фоновом потоке; отмена future прерывает его.
 */
public final class ModelClient {

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(20);
    /** Сколько ждать заголовков ответа: сервер без стриминга отвечает только после генерации. */
    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofMinutes(5);
    /** Сколько ждать следующей порции потока. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(2);

    private static final int MAX_ERROR_BODY = 64 * 1024;
    private static final String USER_AGENT = "EDT-AI";

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "EDT AI: таймауты запросов");
        thread.setDaemon(true);
        return thread;
    });

    private final Duration connectTimeout;
    private final Duration responseTimeout;
    private final Duration idleTimeout;

    private CachedClient cached;

    public ModelClient() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_RESPONSE_TIMEOUT, DEFAULT_IDLE_TIMEOUT);
    }

    public ModelClient(Duration connectTimeout, Duration responseTimeout, Duration idleTimeout) {
        this.connectTimeout = connectTimeout;
        this.responseTimeout = responseTimeout;
        this.idleTimeout = idleTimeout;
    }

    /**
     * Запрашивает ответ на историю диалога.
     *
     * @param onText получает части ответа по мере генерации, из фонового потока
     * @return полный текст ответа; завершается {@link ModelException} при ошибке
     */
    public CompletableFuture<String> stream(ModelConnection connection, List<ChatMessage> history, Consumer<String> onText) {
        CompletableFuture<ModelReply> reply = request(connection, history, List.of(), List.of(), onText);
        CompletableFuture<String> text = reply.thenApply(ModelReply::text);
        text.whenComplete((answer, error) -> {
            if (text.isCancelled()) {
                reply.cancel(true);
            }
        });
        return text;
    }

    /**
     * Один запрос к модели с инструментами.
     *
     * @param extra   сообщения после истории в формате протокола: прошлые вызовы инструментов и их результаты
     * @param tools   инструменты, которые модель может вызвать
     * @param onText  получает части текста ответа по мере генерации, из фонового потока
     * @return текст и вызовы инструментов; завершается {@link ModelException} при ошибке
     */
    CompletableFuture<ModelReply> request(ModelConnection connection, List<ChatMessage> history, List<Object> extra,
            List<Tool> tools, Consumer<String> onText) {
        CompletableFuture<ModelReply> result = new CompletableFuture<>();
        Exchange exchange = new Exchange(connection, history, extra, tools, onText, result);
        Thread thread = new Thread(exchange::run, "EDT AI: запрос к модели");
        thread.setDaemon(true);
        exchange.thread = thread;
        result.whenComplete((answer, error) -> {
            if (result.isCancelled()) {
                exchange.abort();
            }
        });
        thread.start();
        return result;
    }

    /**
     * Проверка подключения: короткий запрос к модели.
     *
     * @return описание результата для пользователя
     */
    public CompletableFuture<String> check(ModelConnection connection) {
        long start = System.nanoTime();
        List<ChatMessage> ping = List.of(ChatMessage.of(ChatRole.USER, "Ответь одним словом: готов"));
        return stream(connection.withMaxTokens(16), ping, part -> { })
                .thenApply(answer -> {
                    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    String shown = answer.strip().replaceAll("\\s+", " ");
                    if (shown.length() > 60) {
                        shown = shown.substring(0, 60) + "…";
                    }
                    return "Подключение работает: модель " + connection.model() + " ответила за " + millis + " мс"
                            + (shown.isEmpty() ? "." : ": «" + shown + "».");
                });
    }

    private synchronized HttpClient client(TrustSettings trust) throws ModelException {
        long modified = trust.caCertificateFile().map(path -> {
            try {
                return Files.getLastModifiedTime(path).toMillis();
            } catch (IOException e) {
                return -1L;
            }
        }).orElse(0L);
        if (cached != null && cached.trust.equals(trust) && cached.modified == modified) {
            return cached.client;
        }
        SSLContext ssl;
        try {
            ssl = TlsContextFactory.create(trust);
        } catch (Exception e) {
            String file = trust.caCertificateFile().map(Object::toString).orElse("");
            throw new ModelException("Не удалось подготовить сертификаты" + (file.isEmpty() ? "" : " из файла " + file)
                    + ": " + e.getMessage(), e);
        }
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .sslContext(ssl);
        ProxySelector proxy = ProxySelector.getDefault();
        if (proxy != null) {
            builder.proxy(proxy);
        }
        cached = new CachedClient(trust, modified, builder.build());
        return cached.client;
    }

    private record CachedClient(TrustSettings trust, long modified, HttpClient client) {
    }

    /** Один запрос: поток, в котором он выполняется, и ответ, который можно закрыть. */
    private final class Exchange {

        private final ModelConnection connection;
        private final List<ChatMessage> history;
        private final List<Object> extra;
        private final List<Tool> tools;
        private final Consumer<String> onText;
        private final CompletableFuture<ModelReply> result;
        private final ProtocolAdapter adapter;

        volatile Thread thread;
        private volatile InputStream body;
        private volatile boolean aborted;
        private volatile boolean idleExpired;
        private volatile long lastActivity;

        Exchange(ModelConnection connection, List<ChatMessage> history, List<Object> extra, List<Tool> tools, Consumer<String> onText,
                CompletableFuture<ModelReply> result) {
            this.connection = connection;
            this.history = history;
            this.extra = extra;
            this.tools = tools;
            this.onText = onText;
            this.result = result;
            this.adapter = ProtocolAdapter.of(connection.protocol());
        }

        void run() {
            URI uri = null;
            ScheduledFuture<?> watchdog = null;
            try {
                uri = connection.endpoint();
                if (connection.model().isEmpty()) {
                    throw new ModelException("Не задана модель в настройках EDT AI.");
                }
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .timeout(responseTimeout)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream, application/json")
                        .header("User-Agent", USER_AGENT)
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(adapter.body(connection, history, extra, tools)), StandardCharsets.UTF_8));
                for (Map.Entry<String, String> header : adapter.headers(connection).entrySet()) {
                    request.header(header.getKey(), header.getValue());
                }
                HttpResponse<InputStream> response = client(connection.trust())
                        .send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                body = response.body();
                if (aborted) {
                    body.close();
                    return;
                }
                lastActivity = System.nanoTime();
                watchdog = WATCHDOG.scheduleWithFixedDelay(this::checkIdle, 1, 1, TimeUnit.SECONDS);

                int status = response.statusCode();
                if (status / 100 != 2) {
                    throw ModelErrors.http(status, readLimited(body), uri);
                }
                String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                ModelReply answer = type.contains("text/event-stream") ? readStream(body) : readFull(body);
                result.complete(answer);
            } catch (Throwable e) {
                if (!result.isDone()) {
                    ModelException error = uri == null && !(e instanceof ModelException)
                            ? new ModelException("Ошибка запроса к модели: " + e.getMessage(), e)
                            : ModelErrors.of(e, Objects.requireNonNullElse(uri, URI.create("http://localhost")),
                                    connectTimeout, responseTimeout, idleExpired ? idleTimeout : null);
                    result.completeExceptionally(new ModelException(ModelErrors.redact(error.getMessage(), connection.token()),
                            error.getCause(), error.rejectsTools()));
                }
            } finally {
                if (watchdog != null) {
                    watchdog.cancel(false);
                }
                closeQuietly();
            }
        }

        private ModelReply readStream(InputStream in) throws IOException, ModelException {
            StreamState state = new StreamState(part -> {
                if (!result.isDone()) {
                    onText.accept(part);
                }
            });
            SseParser parser = new SseParser();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                lastActivity = System.nanoTime();
                SseEvent event = parser.line(line);
                if (event != null && adapter.onEvent(event, state)) {
                    return state.reply();
                }
            }
            SseEvent last = parser.finish();
            if (last != null) {
                adapter.onEvent(last, state);
            }
            // Часть серверов закрывает поток без [DONE] или message_stop — считаем это концом ответа.
            return state.reply();
        }

        private ModelReply readFull(InputStream in) throws IOException, ModelException {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            ModelReply answer = adapter.fullResponse(Json.parse(text));
            if (!answer.text().isEmpty() && !result.isDone()) {
                onText.accept(answer.text());
            }
            return answer;
        }

        private String readLimited(InputStream in) throws IOException {
            return new String(in.readNBytes(MAX_ERROR_BODY), StandardCharsets.UTF_8);
        }

        private void checkIdle() {
            if (System.nanoTime() - lastActivity > idleTimeout.toNanos()) {
                idleExpired = true;
                closeQuietly();
                Thread current = thread;
                if (current != null) {
                    current.interrupt();
                }
            }
        }

        void abort() {
            aborted = true;
            closeQuietly();
            Thread current = thread;
            if (current != null) {
                current.interrupt();
            }
        }

        private void closeQuietly() {
            InputStream in = body;
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // Закрываем, потому что ответ больше не нужен; ошибка неинтересна.
                }
            }
        }
    }
}
