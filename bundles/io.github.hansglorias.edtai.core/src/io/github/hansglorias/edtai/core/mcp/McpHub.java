package io.github.hansglorias.edtai.core.mcp;

import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import javax.net.ssl.SSLContext;

import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.settings.McpServer;
import io.github.hansglorias.edtai.core.settings.McpTransport;
import io.github.hansglorias.edtai.core.tls.TlsContextFactory;
import io.github.hansglorias.edtai.core.tls.TrustSettings;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolException;

/**
 * Подключения к MCP-серверам из настроек и их инструменты для модели.
 * Подключение и список инструментов запоминаются, пока не изменились
 * настройки сервера; {@link #reset()} забывает всё.
 */
public final class McpHub implements AutoCloseable {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Вызов инструмента EDT-MCP может ждать фоновую задачу EDT. */
    static final Duration CALL_TIMEOUT = Duration.ofMinutes(3);
    /** Больше инструментов — описания заметно раздувают каждый запрос к модели. */
    static final int MANY_TOOLS = 30;
    private static final int MAX_NAME = 64;

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    /** Чем кончилось последнее подключение к серверу: для индикатора у поля ввода. */
    private final Map<McpServer, ServerStatus> lastStatus = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final Object statusLock = new Object();
    private final Runnable statusChanged;
    private final Map<String, ReentrantLock> usage = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Connection> retired = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PROBE_BUDGET = Duration.ofSeconds(10);
    private final Duration callTimeout;
    /** Пауза перед повтором вызова, пока EDT собирает проект; в тестах — короче. */
    volatile Duration buildRetryDelay = Duration.ofSeconds(3);

    public McpHub() {
        this(CALL_TIMEOUT, () -> { });
    }

    public McpHub(Runnable statusChanged) {
        this(CALL_TIMEOUT, statusChanged);
    }

    McpHub(Duration callTimeout) {
        this(callTimeout, () -> { });
    }

    private McpHub(Duration callTimeout, Runnable statusChanged) {
        this.callTimeout = callTimeout;
        this.statusChanged = Objects.requireNonNull(statusChanged);
    }

    private record Connection(McpServer server, TrustSettings trust, McpClient client, List<McpToolInfo> tools) {
    }

    /**
     * Инструменты включённых серверов. Сервер, который не подключился, —
     * строка в {@code problems}, остальные работают.
     *
     * @param reserved имена, уже занятые встроенными инструментами
     * @param limit    прежний лимит результата (совместимость API); страницы формирует AgentLoop
     */
    public List<Tool> tools(List<McpServer> servers, TrustSettings trust, Set<String> reserved, IntSupplier limit,
            Consumer<ToolUse> problems) {
        List<Tool> tools = new ArrayList<>();
        long version = generation.get();
        Set<String> taken = new HashSet<>(reserved);
        // Этот встроенный инструмент появится лениво, после первого большого результата.
        taken.add("read_tool_result");
        for (McpServer server : servers) {
            if (!server.enabled()) {
                continue;
            }
            if (server.transport() == McpTransport.STDIO) {
                remember(new ServerStatus(server, State.UNSUPPORTED, "", "", List.of()), version);
                problems.accept(problem(server, "stdio пока не поддерживается, нужен адрес http://…/mcp"));
                continue;
            }
            Connection connection;
            try {
                connection = connect(server, trust, version);
            } catch (McpException e) {
                remember(new ServerStatus(server, State.FAILED, e.getMessage(), "", List.of(), Instant.now()), version);
                problems.accept(problem(server, e.getMessage()));
                continue;
            }
            int allowed = 0;
            for (McpToolInfo info : connection.tools()) {
                if (server.allowsTool(info.name())) {
                    String name = exposedName(server, info.name(), taken);
                    taken.add(name);
                    tools.add(new McpTool(this, server, info, name, limit));
                    allowed++;
                }
            }
            if (allowed == 0) {
                problems.accept(problem(server, "фильтр «" + server.tools() + "» не пропустил ни одного из "
                        + connection.tools().size() + " инструментов сервера"));
            }
        }
        return tools;
    }

    /**
     * Проверка сервера для страницы настроек: подключение и список
     * инструментов заново.
     *
     * @return описание для пользователя
     */
    public String check(McpServer server, TrustSettings trust) throws McpException {
        return checkDetails(server, trust).text();
    }

    /** Проверка и имя сервера из serverInfo, без версии. */
    public record CheckResult(String text, String name, boolean deferred) {
        public CheckResult(String text, String name) { this(text, name, false); }
    }

    public CheckResult checkDetails(McpServer server, TrustSettings trust) throws McpException {
        return checkDetails(server, trust, revision());
    }

    public long revision() { return generation.get(); }

    public CheckResult checkDetails(McpServer server, TrustSettings trust, long version) throws McpException {
        if (closed || version != generation.get()) throw new McpException("Настройки MCP изменились до начала проверки");
        if (server.transport() == McpTransport.STDIO) {
            remember(new ServerStatus(server, State.UNSUPPORTED, "", "", List.of()), version);
            throw new McpException("Транспорт stdio пока не поддерживается: укажите адрес http://…/mcp");
        }
        ReentrantLock gate = gate(server);
        if (!gate.tryLock()) return new CheckResult("Сервер занят: проверка отложена", "", true);
        ServerStatus previous = lastStatus(List.of(server)).get(0);
        remember(new ServerStatus(server, State.CHECKING, "", previous.serverName(), previous.tools(), previous.checkedAt()), version);
        // Отдельная короткая сессия: не верим кэшу и не прерываем вызов инструмента в живом чате.
        try (McpClient client = probeClient(server, trust)) {
            client.initialize();
            List<McpToolInfo> tools = List.copyOf(client.listTools());
            remember(new ServerStatus(server, State.CONNECTED, "", client.serverName(), tools, Instant.now()), version);
            return new CheckResult(checkText(server, client.serverName(), tools), client.serverTitle());
        } catch (McpException e) {
            remember(new ServerStatus(server, State.FAILED, e.getMessage(), "", List.of(), Instant.now()), version);
            throw e;
        } finally {
            gate.unlock();
            closeRetired();
        }
    }

    /** Доступность транспорта, без вызова бизнес-инструментов. false — занятый сервер отложен. */
    public boolean refreshAvailability(List<McpServer> servers, TrustSettings trust) {
        long version = generation.get();
        boolean complete = true;
        for (McpServer server : servers) {
            if (closed || generation.get() != version) return true;
            if (!server.enabled()) continue;
            if (server.transport() != McpTransport.HTTP) {
                remember(new ServerStatus(server, State.UNSUPPORTED, "", "", List.of()), version);
                continue;
            }
            ReentrantLock gate = gate(server);
            if (!gate.tryLock()) { complete = false; continue; }
            ServerStatus previous = lastStatus(List.of(server)).get(0);
            remember(new ServerStatus(server, State.CHECKING, "", previous.serverName(), previous.tools(), previous.checkedAt()), version);
            try (McpClient client = probeClient(server, trust)) {
                client.initialize();
                client.ping();
                remember(new ServerStatus(server, State.CONNECTED, "", client.serverName(), previous.tools(), Instant.now()), version);
            } catch (McpException e) {
                remember(new ServerStatus(server, State.FAILED, e.getMessage(), "", List.of(), Instant.now()), version);
            } finally {
                gate.unlock();
                closeRetired();
            }
        }
        closeRetired();
        return complete;
    }

    private McpClient probeClient(McpServer server, TrustSettings trust) throws McpException {
        return new McpClient(uri(server), httpClient(uri(server), trust), PROBE_TIMEOUT, PROBE_BUDGET);
    }

    private static String checkText(McpServer server, String serverName, List<McpToolInfo> tools) {
        int all = tools.size();
        long allowed = tools.stream().filter(info -> server.allowsTool(info.name())).count();
        StringBuilder text = new StringBuilder("Подключено");
        if (!serverName.isEmpty()) {
            text.append(": ").append(serverName);
        }
        text.append(". Инструментов: ").append(all);
        if (allowed != all) {
            text.append(", модели доступно по фильтру: ").append(allowed);
        }
        text.append('.');
        if (allowed > MANY_TOOLS) {
            int size = tools.stream().filter(info -> server.allowsTool(info.name()))
                    .mapToInt(info -> info.description().length() + Json.write(info.inputSchema()).length()).sum();
            text.append(" Описания займут около ").append(size / 1000).append(" тыс. символов в каждом запросе к модели —"
                    + " лучше перечислить нужные инструменты в поле «Инструменты».");
        }
        return text.toString();
    }

    /** Состояние сервера для окна {@code /mcp}. */
    public enum State {
        CONNECTED, FAILED, DISABLED, UNSUPPORTED, CHECKING,
        /** Ещё не подключались: после запуска или изменения настроек. */
        UNKNOWN
    }

    /**
     * Сервер в окне {@code /mcp}.
     *
     * @param message    у неподключённого — почему
     * @param serverName как сервер назвал себя при подключении
     * @param tools      все инструменты сервера; модели уходят те, что пропускает фильтр сервера
     */
    public record ServerStatus(McpServer server, State state, String message, String serverName, List<McpToolInfo> tools, Instant checkedAt) {

        public ServerStatus(McpServer server, State state, String message, String serverName, List<McpToolInfo> tools) {
            this(server, state, message, serverName, tools, null);
        }

        public ServerStatus {
            message = Objects.requireNonNullElse(message, "");
            serverName = Objects.requireNonNullElse(serverName, "");
            tools = List.copyOf(Objects.requireNonNullElse(tools, List.of()));
        }

        /** Инструменты, которые получает модель. */
        public List<McpToolInfo> allowed() {
            return tools.stream().filter(info -> server.allowsTool(info.name())).toList();
        }

        /** Строка состояния: «Подключено: EDT-MCP. Инструментов: 40, модели доступно: 12». */
        public String summary() {
            return switch (state) {
                case DISABLED -> "Выключен";
                case UNSUPPORTED -> "stdio пока не поддерживается, нужен адрес http://…/mcp";
                case FAILED -> "Не подключён: " + message;
                case UNKNOWN -> "Ещё не проверялся" + (message.isEmpty() ? "" : ". " + message);
                case CHECKING -> "Проверяется связь" + (message.isEmpty() ? "" : ". " + message);
                case CONNECTED -> "Подключено" + (serverName.isEmpty() ? "" : ": " + serverName)
                        + (tools.isEmpty() ? ". Доступен транспорт MCP; инструменты не запрашивались"
                                : ". Инструментов: " + tools.size() + ", модели доступно: " + allowed().size())
                        + (message.isEmpty() ? "" : ". " + message);
            };
        }

        public String checkedTime() {
            return checkedAt == null ? "Ещё не проверялся" : "Последняя проверка: "
                    + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(checkedAt);
        }
    }

    /**
     * Состояние серверов для команды {@code /mcp}: подключение и какие
     * инструменты модель получает. Подключается к включённым серверам, как
     * перед ответом.
     */
    public List<ServerStatus> status(List<McpServer> servers, TrustSettings trust) {
        return status(servers, trust, revision());
    }

    public List<ServerStatus> status(List<McpServer> servers, TrustSettings trust, long version) {
        List<ServerStatus> result = new ArrayList<>();
        for (McpServer server : servers) {
            if (!server.enabled()) {
                result.add(new ServerStatus(server, State.DISABLED, "", "", List.of()));
            } else if (server.transport() == McpTransport.STDIO) {
                result.add(new ServerStatus(server, State.UNSUPPORTED, "", "", List.of()));
            } else {
                try {
                    CheckResult check = checkDetails(server, trust, version);
                    ServerStatus last = lastStatus(List.of(server)).get(0);
                    result.add(check.deferred() ? new ServerStatus(server, last.state(),
                            (last.message().isEmpty() ? "" : last.message() + ". ") + check.text(), last.serverName(), last.tools(), last.checkedAt()) : last);
                } catch (McpException e) {
                    result.add(new ServerStatus(server, State.FAILED, e.getMessage(), "", List.of(), Instant.now()));
                }
            }
        }
        result.forEach(status -> remember(status, version));
        return result;
    }

    private void remember(ServerStatus status) {
        remember(status, generation.get());
    }

    private void remember(ServerStatus status, long version) {
        boolean changed = false;
        synchronized (statusLock) {
            if (!closed && generation.get() == version) {
                ServerStatus previous = lastStatus.get(status.server());
                if (previous != null && previous.checkedAt() != null && status.checkedAt() != null
                        && status.checkedAt().isBefore(previous.checkedAt())) return;
                changed = !status.equals(lastStatus.put(status.server(), status));
            }
        }
        if (changed) statusChanged.run();
    }

    /** Итог независимой проверки в настройках; вызывающий проверяет, что настройки ещё актуальны. */
    public void recordCheck(ServerStatus status) {
        remember(status);
    }

    /**
     * Последнее известное состояние серверов, без подключения: что было при
     * последнем ответе или {@code /mcp}. Сервер с изменёнными настройками —
     * {@link State#UNKNOWN}.
     */
    public List<ServerStatus> lastStatus(List<McpServer> servers) {
        List<ServerStatus> result = new ArrayList<>();
        for (McpServer server : servers) {
            ServerStatus last = lastStatus.get(server);
            if (!server.enabled()) {
                result.add(new ServerStatus(server, State.DISABLED, "", "", List.of()));
            } else if (last != null && last.server().equals(server)) {
                result.add(last);
            } else {
                result.add(new ServerStatus(server, State.UNKNOWN, "", "", List.of()));
            }
        }
        return result;
    }

    /**
     * Итог для индикатора: {@code state} — общий цвет, {@code text} —
     * подсказка по серверам.
     */
    public record Health(State state, String text) {
    }

    /** Для кнопки у поля ввода: «MCP 1/2» — подключено из настроенных; не настроены — «MCP». */
    public static String counter(List<ServerStatus> servers) {
        if (servers.isEmpty()) {
            return "MCP";
        }
        if (servers.stream().anyMatch(server -> server.state() == State.CHECKING)) return "MCP …/" + servers.size();
        return "MCP " + servers.stream().filter(server -> server.state() == State.CONNECTED).count() + "/" + servers.size();
    }

    /**
     * Общее состояние: хоть один не подключился — ошибка; все включённые
     * подключены — норма; включённых нет — выключено; иначе не проверялись.
     */
    public static Health health(List<ServerStatus> servers) {
        if (servers.isEmpty()) {
            return new Health(State.DISABLED, "MCP-серверы не настроены");
        }
        List<ServerStatus> enabled = servers.stream().filter(server -> server.state() != State.DISABLED).toList();
        State state;
        if (enabled.isEmpty()) {
            state = State.DISABLED;
        } else if (enabled.stream().anyMatch(server -> server.state() == State.FAILED || server.state() == State.UNSUPPORTED)) {
            state = State.FAILED;
        } else if (enabled.stream().allMatch(server -> server.state() == State.CONNECTED)) {
            state = State.CONNECTED;
        } else if (enabled.stream().anyMatch(server -> server.state() == State.CHECKING)) {
            state = State.CHECKING;
        } else {
            state = State.UNKNOWN;
        }
        StringBuilder text = new StringBuilder();
        for (ServerStatus server : servers) {
            text.append(text.isEmpty() ? "" : "\n").append(server.server().name()).append(": ").append(server.summary())
                    .append(". ").append(server.checkedTime());
        }
        text.append("\nПроверяется транспорт MCP; успешность отдельных инструментов зависит от сервера и проекта.");
        return new Health(state, text.toString());
    }

    /** Нет серверов — что показать вместо списка. */
    public static final String NO_SERVERS = "MCP-серверы не настроены. Добавить: Настройки → EDT AI → MCP-серверы.";

    /** {@link #status} текстом: инструменты отдельно читающие и меняющие проект. */
    public String describe(List<McpServer> servers, TrustSettings trust) {
        if (servers.isEmpty()) {
            return NO_SERVERS;
        }
        StringBuilder text = new StringBuilder();
        for (ServerStatus status : status(servers, trust)) {
            String summary = status.summary();
            text.append(status.server().name()).append(" — ").append(status.server().target()).append('\n').append(summary)
                    .append(summary.endsWith(".") ? "\n" : ".\n");
            List<McpToolInfo> allowed = status.allowed();
            names(text, "Читают", allowed.stream().filter(info -> !info.modifies()).toList());
            names(text, "Меняют проект (в режиме «только вопросы» не передаются)",
                    allowed.stream().filter(McpToolInfo::modifies).toList());
            text.append('\n');
        }
        return text.toString().strip();
    }

    private static void names(StringBuilder text, String title, List<McpToolInfo> tools) {
        if (!tools.isEmpty()) {
            text.append(title).append(": ").append(String.join(", ", tools.stream().map(McpToolInfo::name).toList())).append(".\n");
        }
    }

    /** Имена инструментов сервера: для подсказки в настройках. */
    public List<String> toolNames(McpServer server, TrustSettings trust) throws McpException {
        return connect(server, trust, revision()).tools().stream().map(McpToolInfo::name).toList();
    }

    McpCallResult call(McpServer server, String tool, Map<String, Object> arguments) throws ToolException {
        long version = generation.get();
        Connection connection = connections.get(server.name());
        if (connection == null || !connection.server().target().equals(server.target())
                || connection.server().transport() != server.transport()) {
            throw new ToolException(ToolUse.Problem.SERVER, "MCP-сервер «" + server.name() + "» отключён");
        }
        ReentrantLock gate = gate(server);
        gate.lock();
        try {
            if (connections.get(server.name()) != connection) throw new ToolException(ToolUse.Problem.SERVER, "Подключение MCP изменилось");
            McpCallResult result = connection.client().callTool(tool, arguments);
            if (connections.get(server.name()) == connection) {
                remember(new ServerStatus(server, State.CONNECTED, "", connection.client().serverName(), connection.tools(), Instant.now()), version);
            }
            return result;
        } catch (McpException e) {
            if (connections.get(server.name()) == connection
                    && !e.remoteError() && !Thread.currentThread().isInterrupted()) {
                remember(new ServerStatus(server, State.FAILED, e.getMessage(), "", List.of(), Instant.now()), version);
            }
            throw new ToolException(problem(e), e.getMessage());
        } finally {
            gate.unlock();
            closeRetired();
        }
    }

    /** Нет ответа в срок или нет связи с сервером — не ошибка самого инструмента. */
    static ToolUse.Problem problem(McpException e) {
        Throwable cause = e.getCause();
        if (cause instanceof java.net.http.HttpConnectTimeoutException || cause instanceof java.net.ConnectException) {
            return ToolUse.Problem.SERVER;
        }
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return ToolUse.Problem.TIMEOUT;
        }
        return cause instanceof java.io.IOException ? ToolUse.Problem.SERVER : ToolUse.Problem.FAILED;
    }

    /** Закрывает подключения: например, после изменения настроек. */
    public void reset() {
        invalidateConfiguration();
        closeRetired();
    }

    /** Без сети и без прерывания начатых инструментов; старые сессии закрываются после освобождения. */
    public void invalidateConfiguration() {
        synchronized (statusLock) {
            generation.incrementAndGet();
            lastStatus.clear();
            retired.addAll(connections.values());
            connections.clear();
        }
        statusChanged.run();
    }

    /** Без сети: устаревшие проверки после смены настроек больше не публикуются. */
    public void invalidateStatus() {
        synchronized (statusLock) {
            generation.incrementAndGet();
            lastStatus.clear();
        }
        statusChanged.run();
    }

    @Override
    public void close() {
        closed = true;
        reset();
    }

    private synchronized Connection connect(McpServer server, TrustSettings trust, long version) throws McpException {
        if (closed || version != generation.get()) throw new McpException("Настройки MCP изменились до подключения");
        Connection current = connections.get(server.name());
        ServerStatus previous = lastStatus.get(server);
        if (current != null && current.server().target().equals(server.target()) && current.trust().equals(trust)
                && current.server().transport() == server.transport() && (previous == null || previous.state() != State.FAILED)) {
            return current;
        }
        forget(server.name());
        ReentrantLock gate = gate(server);
        McpClient client = new McpClient(uri(server), httpClient(uri(server), trust), callTimeout);
        gate.lock();
        try {
            client.initialize();
            List<McpToolInfo> tools = List.copyOf(client.listTools());
            requireTools(tools);
            Connection connection = new Connection(server, trust, client, tools);
            synchronized (statusLock) {
                if (closed || generation.get() != version) throw new McpException("Настройки MCP изменились во время подключения");
                connections.put(server.name(), connection);
            }
            remember(new ServerStatus(server, State.CONNECTED, "", client.serverName(), tools, Instant.now()), version);
            return connection;
        } catch (McpException e) {
            client.close();
            throw e;
        } finally {
            gate.unlock();
            closeRetired();
        }
    }

    private static void requireTools(List<McpToolInfo> tools) throws McpException {
        if (tools.isEmpty()) {
            throw new McpException("сервер не вернул ни одного инструмента. Если EDT только что запущена, повторите вопрос чуть позже");
        }
    }

    private void forget(String name) {
        Connection connection = connections.remove(name);
        if (connection != null) {
            retired.add(connection);
            closeRetired();
        }
    }

    private ReentrantLock gate(McpServer server) {
        return usage.computeIfAbsent(server.transport() + ":" + server.target(), key -> new ReentrantLock());
    }

    private void closeRetired() {
        for (Connection connection : retired) {
            ReentrantLock gate = gate(connection.server());
            if (!gate.tryLock()) continue;
            try {
                if (retired.remove(connection)) connection.client().close();
            } finally {
                gate.unlock();
            }
        }
    }

    private static URI uri(McpServer server) throws McpException {
        try {
            URI uri = new URI(server.target());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new McpException("Адрес MCP-сервера «" + server.target() + "»: нужен http://… или https://…");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new McpException("Некорректный адрес MCP-сервера: " + server.target());
        }
    }

    /** Локальный сервер — без прокси; https — с сертификатами из настроек. */
    private static HttpClient httpClient(URI uri, TrustSettings trust) throws McpException {
        HttpClient.Builder builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(CONNECT_TIMEOUT);
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            try {
                SSLContext ssl = TlsContextFactory.create(trust);
                builder.sslContext(ssl);
            } catch (Exception e) {
                throw new McpException("Не удалось подготовить сертификаты для MCP-сервера: " + e.getMessage(), e);
            }
        }
        if (isLoopback(uri.getHost())) {
            builder.proxy(HttpClient.Builder.NO_PROXY);
        } else if (ProxySelector.getDefault() != null) {
            builder.proxy(ProxySelector.getDefault());
        }
        return builder.build();
    }

    static boolean isLoopback(String host) {
        String value = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (value.equals("localhost")) {
            return true;
        }
        try {
            return value.matches("[0-9.:a-f]+") && InetAddress.getByName(value).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Имя для модели: латиница, цифры, {@code _} и {@code -}, до 64 символов
     * (так требует OpenAI). При совпадении с уже занятым — с именем сервера в
     * начале.
     */
    static String exposedName(McpServer server, String tool, Set<String> taken) {
        String name = sanitize(tool);
        if (!taken.contains(name)) {
            return name;
        }
        // Из имени сервера — только латиница и цифры: «EDT (рабочая)» → «EDT»; кириллица целиком — «mcp».
        String prefix = sanitize(server.name()).replaceAll("_+", "_").replaceAll("^_|_$", "");
        String candidate = (prefix.isEmpty() ? "mcp" : prefix) + "__" + name;
        candidate = candidate.length() > MAX_NAME ? candidate.substring(0, MAX_NAME) : candidate;
        int suffix = 2;
        String unique = candidate;
        while (taken.contains(unique)) {
            String tail = "_" + suffix++;
            unique = candidate.substring(0, Math.min(candidate.length(), MAX_NAME - tail.length())) + tail;
        }
        return unique;
    }

    private static String sanitize(String value) {
        String name = value.replaceAll("[^A-Za-z0-9_-]", "_");
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    private static ToolUse problem(McpServer server, String message) {
        return new ToolUse("mcp:" + server.name(), "mcp", "MCP «" + server.name() + "»", "не подключился: " + message,
                ToolUse.Status.FAILED);
    }
}
