package io.github.hansglorias.edtai.core.mcp;

import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<String, ServerStatus> lastStatus = new ConcurrentHashMap<>();
    private final Duration callTimeout;
    /** Пауза перед повтором вызова, пока EDT собирает проект; в тестах — короче. */
    volatile Duration buildRetryDelay = Duration.ofSeconds(3);

    public McpHub() {
        this(CALL_TIMEOUT);
    }

    McpHub(Duration callTimeout) {
        this.callTimeout = callTimeout;
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
        Set<String> taken = new HashSet<>(reserved);
        // Этот встроенный инструмент появится лениво, после первого большого результата.
        taken.add("read_tool_result");
        for (McpServer server : servers) {
            if (!server.enabled()) {
                continue;
            }
            if (server.transport() == McpTransport.STDIO) {
                remember(new ServerStatus(server, State.UNSUPPORTED, "", "", List.of()));
                problems.accept(problem(server, "stdio пока не поддерживается, нужен адрес http://…/mcp"));
                continue;
            }
            Connection connection;
            try {
                connection = connect(server, trust);
            } catch (McpException e) {
                remember(new ServerStatus(server, State.FAILED, e.getMessage(), "", List.of()));
                problems.accept(problem(server, e.getMessage()));
                continue;
            }
            remember(new ServerStatus(server, State.CONNECTED, "", connection.client().serverName(), connection.tools()));
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
        if (server.transport() == McpTransport.STDIO) {
            throw new McpException("Транспорт stdio пока не поддерживается: укажите адрес http://…/mcp");
        }
        forget(server.name());
        Connection connection = connect(server, trust);
        int all = connection.tools().size();
        long allowed = connection.tools().stream().filter(info -> server.allowsTool(info.name())).count();
        StringBuilder text = new StringBuilder("Подключено");
        if (!connection.client().serverName().isEmpty()) {
            text.append(": ").append(connection.client().serverName());
        }
        text.append(". Инструментов: ").append(all);
        if (allowed != all) {
            text.append(", модели доступно по фильтру: ").append(allowed);
        }
        text.append('.');
        if (allowed > MANY_TOOLS) {
            int size = connection.tools().stream().filter(info -> server.allowsTool(info.name()))
                    .mapToInt(info -> info.description().length() + Json.write(info.inputSchema()).length()).sum();
            text.append(" Описания займут около ").append(size / 1000).append(" тыс. символов в каждом запросе к модели —"
                    + " лучше перечислить нужные инструменты в поле «Инструменты».");
        }
        return text.toString();
    }

    /** Состояние сервера для окна {@code /mcp}. */
    public enum State {
        CONNECTED, FAILED, DISABLED, UNSUPPORTED,
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
    public record ServerStatus(McpServer server, State state, String message, String serverName, List<McpToolInfo> tools) {

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
                case UNKNOWN -> "Ещё не проверялся";
                case CONNECTED -> "Подключено" + (serverName.isEmpty() ? "" : ": " + serverName) + ". Инструментов: " + tools.size()
                        + ", модели доступно: " + allowed().size();
            };
        }
    }

    /**
     * Состояние серверов для команды {@code /mcp}: подключение и какие
     * инструменты модель получает. Подключается к включённым серверам, как
     * перед ответом.
     */
    public List<ServerStatus> status(List<McpServer> servers, TrustSettings trust) {
        List<ServerStatus> result = new ArrayList<>();
        try {
            check(servers, trust, result);
        } finally {
            result.forEach(this::remember);
        }
        return result;
    }

    private void check(List<McpServer> servers, TrustSettings trust, List<ServerStatus> result) {
        for (McpServer server : servers) {
            if (!server.enabled()) {
                result.add(new ServerStatus(server, State.DISABLED, "", "", List.of()));
            } else if (server.transport() == McpTransport.STDIO) {
                result.add(new ServerStatus(server, State.UNSUPPORTED, "", "", List.of()));
            } else {
                try {
                    Connection connection = connect(server, trust);
                    result.add(new ServerStatus(server, State.CONNECTED, "", connection.client().serverName(), connection.tools()));
                } catch (McpException e) {
                    result.add(new ServerStatus(server, State.FAILED, e.getMessage(), "", List.of()));
                }
            }
        }
    }

    private void remember(ServerStatus status) {
        lastStatus.put(status.server().name(), status);
    }

    /**
     * Последнее известное состояние серверов, без подключения: что было при
     * последнем ответе или {@code /mcp}. Сервер с изменёнными настройками —
     * {@link State#UNKNOWN}.
     */
    public List<ServerStatus> lastStatus(List<McpServer> servers) {
        List<ServerStatus> result = new ArrayList<>();
        for (McpServer server : servers) {
            ServerStatus last = lastStatus.get(server.name());
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
        } else {
            state = State.UNKNOWN;
        }
        StringBuilder text = new StringBuilder();
        for (ServerStatus server : servers) {
            text.append(text.isEmpty() ? "" : "\n").append(server.server().name()).append(": ").append(server.summary());
        }
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
        return connect(server, trust).tools().stream().map(McpToolInfo::name).toList();
    }

    McpCallResult call(McpServer server, String tool, Map<String, Object> arguments) throws ToolException {
        Connection connection = connections.get(server.name());
        if (connection == null) {
            throw new ToolException(ToolUse.Problem.SERVER, "MCP-сервер «" + server.name() + "» отключён");
        }
        try {
            return connection.client().callTool(tool, arguments);
        } catch (McpException e) {
            throw new ToolException(problem(e), e.getMessage());
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
        for (String name : List.copyOf(connections.keySet())) {
            forget(name);
        }
        lastStatus.clear();
    }

    @Override
    public void close() {
        reset();
    }

    private synchronized Connection connect(McpServer server, TrustSettings trust) throws McpException {
        Connection current = connections.get(server.name());
        if (current != null && current.server().target().equals(server.target()) && current.trust().equals(trust)) {
            return current;
        }
        forget(server.name());
        McpClient client = new McpClient(uri(server), httpClient(uri(server), trust), callTimeout);
        try {
            client.initialize();
            List<McpToolInfo> tools = List.copyOf(client.listTools());
            if (tools.isEmpty()) {
                // Пустой список не запоминаем: EDT-MCP сразу после запуска EDT может ещё не зарегистрировать инструменты.
                throw new McpException("сервер не вернул ни одного инструмента. Если EDT только что запущена, повторите вопрос"
                        + " чуть позже");
            }
            Connection connection = new Connection(server, trust, client, tools);
            connections.put(server.name(), connection);
            return connection;
        } catch (McpException e) {
            client.close();
            throw e;
        }
    }

    private synchronized void forget(String name) {
        Connection connection = connections.remove(name);
        if (connection != null) {
            connection.client().close();
        }
    }

    private static URI uri(McpServer server) throws McpException {
        try {
            URI uri = new URI(server.target());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
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
