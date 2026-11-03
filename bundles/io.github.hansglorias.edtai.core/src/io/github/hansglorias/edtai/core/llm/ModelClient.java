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
import java.util.ArrayList;
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
import java.util.function.BooleanSupplier;

import javax.net.ssl.SSLContext;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Id;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.RequestType;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Status;
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

    /** Пометка в конце ответа, который оборвался по max_tokens. */
    static final String TRUNCATED = "_Ответ оборван: модель дошла до лимита длины ответа. Нажмите «Повторить» или увеличьте"
            + " «Макс. токенов ответа» в настройках._";

    /** Лимит служебных запросов (проверка, название): хватает и на рассуждения модели. */
    static final int SERVICE_TOKENS = 1024;

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

    private final Consumer<DiagnosticEvent> diagnostics;
    private final BooleanSupplier diagnosticsEnabled;

    private CachedClient cached;

    public ModelClient() {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_RESPONSE_TIMEOUT, DEFAULT_IDLE_TIMEOUT, null);
    }

    /** Callback может выполняться в caller или worker; должен быть неблокирующим. Ошибки изолированы. */
    public ModelClient(Consumer<DiagnosticEvent> diagnostics) {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_RESPONSE_TIMEOUT, DEFAULT_IDLE_TIMEOUT, diagnostics);
    }

    /** Включение фиксируется при создании run/standalone-запроса; predicate не должен блокировать. */
    public ModelClient(Consumer<DiagnosticEvent> diagnostics, BooleanSupplier diagnosticsEnabled) {
        this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_RESPONSE_TIMEOUT, DEFAULT_IDLE_TIMEOUT, diagnostics, diagnosticsEnabled);
    }

    public ModelClient(Duration connectTimeout, Duration responseTimeout, Duration idleTimeout) {
        this(connectTimeout, responseTimeout, idleTimeout, null);
    }

    public ModelClient(Duration connectTimeout, Duration responseTimeout, Duration idleTimeout, Consumer<DiagnosticEvent> diagnostics) {
        this(connectTimeout, responseTimeout, idleTimeout, diagnostics, () -> true);
    }

    private ModelClient(Duration connectTimeout, Duration responseTimeout, Duration idleTimeout,
            Consumer<DiagnosticEvent> diagnostics, BooleanSupplier diagnosticsEnabled) {
        this.connectTimeout = connectTimeout;
        this.responseTimeout = responseTimeout;
        this.idleTimeout = idleTimeout;
        this.diagnostics = diagnostics;
        this.diagnosticsEnabled = Objects.requireNonNull(diagnosticsEnabled);
    }

    private boolean diagnosticsEnabled() {
        if (diagnostics == null) return false;
        try { return diagnosticsEnabled.getAsBoolean(); } catch (Throwable ignored) { return false; }
    }
    Id newRunId() { return diagnosticsEnabled() ? Id.random() : null; }
    RequestContext context(RequestType type) {
        return diagnosticsEnabled() ? new RequestContext(Id.random(), type, 0) : null;
    }
    RequestDiagnostics begin(RequestContext context) {
        return context == null ? null : new RequestDiagnostics(diagnostics, context);
    }
    void localFailure(RequestDiagnostics attempt, boolean rejected) {
        if (attempt != null) attempt.finish(rejected ? Status.REJECTED : Status.FAILED, null, !rejected);
    }

    /**
     * Запрашивает ответ на историю диалога.
     *
     * @param onText получает части ответа по мере генерации, из фонового потока
     * @return полный текст ответа; завершается {@link ModelException} при ошибке
     */
    public CompletableFuture<String> stream(ModelConnection connection, List<ChatMessage> history, Consumer<String> onText) {
        return stream(connection, history, onText, RequestType.ANSWER);
    }

    CompletableFuture<String> stream(ModelConnection connection, List<ChatMessage> history, Consumer<String> onText, RequestType type) {
        CompletableFuture<ModelReply> reply = request(connection, history, List.of(), List.of(), onText, context(type));
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
        return request(connection, history, extra, tools, onText, context(RequestType.ANSWER));
    }

    private CompletableFuture<ModelReply> request(ModelConnection connection, List<ChatMessage> history, List<Object> extra,
            List<Tool> tools, Consumer<String> onText, RequestContext context) {
        RequestDiagnostics attempt = begin(context);
        try {
            RequestBudget.requireInputSpace(connection);
            return request(connection, PreparedRequest.prepare(connection, history, extra, tools), onText, attempt, null);
        } catch (ModelException e) {
            localFailure(attempt, true);
            return CompletableFuture.failedFuture(e);
        } catch (RuntimeException e) {
            localFailure(attempt, false);
            return CompletableFuture.failedFuture(preparationError(connection, e));
        }
    }

    static ModelException preparationError(ModelConnection connection, Throwable error) {
        return new ModelException(ModelErrors.redact("Ошибка подготовки запроса к модели: " + error.getMessage(), connection.token()));
    }

    CompletableFuture<ModelReply> request(ModelConnection connection, PreparedRequest prepared, Consumer<String> onText) {
        return request(connection, prepared, onText, () -> { });
    }

    CompletableFuture<ModelReply> request(ModelConnection connection, PreparedRequest prepared, Consumer<String> onText,
            Runnable onReasoning) {
        return request(connection, prepared, onText, onReasoning, begin(context(RequestType.ANSWER)), null);
    }

    CompletableFuture<ModelReply> request(ModelConnection connection, PreparedRequest prepared, Consumer<String> onText,
            RequestDiagnostics attempt, Integer reducedBudget) {
        return request(connection, prepared, onText, () -> { }, attempt, reducedBudget);
    }

    CompletableFuture<ModelReply> request(ModelConnection connection, PreparedRequest prepared, Consumer<String> onText,
            Runnable onReasoning, RequestDiagnostics attempt, Integer reducedBudget) {
        if (attempt != null) attempt.prepared(prepared);
        try {
            if (reducedBudget == null) RequestBudget.requireFits(connection, prepared);
            else RequestBudget.requireFits(connection, prepared, reducedBudget);
        } catch (ModelException e) {
            localFailure(attempt, true);
            return CompletableFuture.failedFuture(e);
        }
        RequestFuture result = new RequestFuture(attempt);
        Exchange exchange = new Exchange(connection, prepared, onText, onReasoning, result, attempt);
        Thread thread = new Thread(exchange::run, "EDT AI: запрос к модели");
        thread.setDaemon(true);
        exchange.thread = thread;
        result.whenComplete((answer, error) -> {
            if (result.isCancelled()) exchange.abort();
        });
        thread.start();
        return result;
    }

    /** Terminal ownership сериализует cancel/success/failure, событие публикуется перед future. */
    private static final class RequestFuture extends CompletableFuture<ModelReply> {
        private final RequestDiagnostics attempt;
        private boolean terminal;
        RequestFuture(RequestDiagnostics attempt) { this.attempt = attempt; }
        private synchronized boolean claim() {
            if (terminal) return false;
            terminal = true;
            return true;
        }
        @Override public boolean complete(ModelReply reply) {
            if (!claim()) return false;
            if (attempt != null) attempt.finish(Status.SUCCESS, reply, false);
            return super.complete(reply);
        }
        boolean fail(Throwable error, Status status) {
            if (!claim()) return false;
            if (attempt != null) attempt.finish(status, null, false);
            return super.completeExceptionally(error);
        }
        @Override public boolean completeExceptionally(Throwable error) { return fail(error, Status.FAILED); }
        @Override public boolean cancel(boolean interrupt) {
            if (!claim()) return false;
            if (attempt != null) attempt.finish(Status.CANCELLED, null, false);
            return super.cancel(interrupt);
        }
    }

    /**
     * Проверка подключения: короткий запрос к модели.
     *
     * @return описание результата для пользователя
     */
    public CompletableFuture<String> check(ModelConnection connection) {
        long start = System.nanoTime();
        List<ChatMessage> ping = List.of(ChatMessage.of(ChatRole.USER, "Ответь одним словом: готов"));
        // Запас на рассуждения: у reasoning-моделей (DeepSeek, o-серия) они тоже идут в max_tokens.
        return stream(connection.withMaxTokens(SERVICE_TOKENS), ping, part -> { }, RequestType.CHECK)
                .thenApply(answer -> {
                    long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    String shown = withoutNotice(answer).strip().replaceAll("\\s+", " ");
                    if (shown.length() > 60) {
                        shown = shown.substring(0, 60) + "…";
                    }
                    return "Подключение работает: модель " + connection.model() + " ответила за " + millis + " мс"
                            + (shown.isEmpty() ? "." : ": «" + shown + "».");
                });
    }

    /** Ответ без пометки об обрыве: служебным запросам она не нужна. */
    static String withoutNotice(String answer) {
        return answer == null ? "" : answer.replace(TRUNCATED, "");
    }

    /**
     * Модели сервера: {@code GET …/v1/models} (OpenAI-совместимые серверы,
     * Ollama, LM Studio, vLLM, Anthropic).
     *
     * @return идентификаторы моделей по алфавиту
     */
    public CompletableFuture<List<String>> models(ModelConnection connection) {
        CompletableFuture<List<String>> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                result.complete(listModels(connection));
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        }, "EDT AI: список моделей");
        thread.setDaemon(true);
        thread.start();
        return result;
    }

    private List<String> listModels(ModelConnection connection) throws ModelException {
        URI chat = connection.endpoint();
        String path = chat.getRawPath();
        String base = path.substring(0, path.length() - (path.endsWith("/chat/completions") ? "/chat/completions" : "/messages").length());
        URI uri = URI.create(chat.getScheme() + "://" + chat.getRawAuthority() + base + "/models");
        try {
            // Заголовки — внутри try: некорректный токен в тексте исключения редактируется ниже.
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(connectTimeout.plusSeconds(30))
                    .header("User-Agent", USER_AGENT).header("Accept", "application/json").GET();
            ProtocolAdapter.of(connection.protocol()).headers(connection).forEach(request::header);
            HttpResponse<String> response = client(connection.trust()).send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw ModelErrors.http(response.statusCode(), response.body(), uri);
            }
            List<String> ids = new ArrayList<>();
            for (Object model : Json.asList(Json.at(Json.parse(response.body()), "data"))) {
                String id = Json.stringAt(model, "id");
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
            ids.sort(String.CASE_INSENSITIVE_ORDER);
            return ids;
        } catch (ModelException e) {
            throw new ModelException(ModelErrors.redact(e.getMessage(), connection.token()), e.getCause(), false);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            ModelException error = ModelErrors.of(e, uri, connectTimeout, responseTimeout, null);
            throw new ModelException(ModelErrors.redact(error.getMessage(), connection.token()), error.getCause(), false);
        }
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

    private static boolean timeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException) return true;
        }
        return false;
    }

    /** Один запрос: поток, в котором он выполняется, и ответ, который можно закрыть. */
    private final class Exchange {

        private final ModelConnection connection;
        private final PreparedRequest prepared;
        private final Consumer<String> onText;
        private final Runnable onReasoning;
        private final RequestFuture result;
        private final RequestDiagnostics attempt;
        private final ProtocolAdapter adapter;

        volatile Thread thread;
        private volatile InputStream body;
        private volatile boolean aborted;
        private volatile boolean idleExpired;
        private volatile long lastActivity;

        Exchange(ModelConnection connection, PreparedRequest prepared, Consumer<String> onText, Runnable onReasoning,
                RequestFuture result, RequestDiagnostics attempt) {
            this.connection = connection;
            this.prepared = prepared;
            this.onText = onText;
            this.onReasoning = onReasoning;
            this.result = result;
            this.attempt = attempt;
            this.adapter = ProtocolAdapter.of(connection.protocol());
        }

        void run() {
            URI uri = null;
            ScheduledFuture<?> watchdog = null;
            try {
                if (result.isCancelled()) return;
                uri = connection.endpoint();
                if (connection.model().isEmpty()) {
                    throw new ModelException("Не задана модель в настройках EDT AI.");
                }
                HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                        .timeout(responseTimeout)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream, application/json")
                        .header("User-Agent", USER_AGENT)
                        .POST(HttpRequest.BodyPublishers.ofString(prepared.body(), StandardCharsets.UTF_8));
                for (Map.Entry<String, String> header : adapter.headers(connection).entrySet()) {
                    request.header(header.getKey(), header.getValue());
                }
                HttpResponse<InputStream> response = client(connection.trust())
                        .send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                body = attempt == null ? response.body() : attempt.response(response.body());
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
                if (answer.truncated() && answer.calls().isEmpty()) {
                    String notice = (answer.text().isEmpty() ? "" : "\n\n") + TRUNCATED;
                    if (!result.isDone()) {
                        onText.accept(notice);
                    }
                    answer = new ModelReply(answer.text() + notice, answer.calls(), true, answer.usage(), answer.reasoning());
                }
                result.complete(answer);
            } catch (Throwable e) {
                if (!result.isDone()) {
                    ModelException error = uri == null && !(e instanceof ModelException)
                            ? new ModelException("Ошибка запроса к модели: " + e.getMessage(), e)
                            : ModelErrors.of(e, Objects.requireNonNullElse(uri, URI.create("http://localhost")),
                                    connectTimeout, responseTimeout, idleExpired ? idleTimeout : null);
                    String message = ModelErrors.redact(error.getMessage(), connection.token());
                    Status status = idleExpired || timeout(e) ? Status.TIMEOUT : Status.FAILED;
                    result.fail(error.contextOverflow() ? ModelException.overflow(message)
                            : new ModelException(message, error.getCause(), error.rejectsTools()), status);
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
            }, () -> {
                if (!result.isDone()) {
                    onReasoning.run();
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
