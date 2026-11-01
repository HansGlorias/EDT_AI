package io.github.hansglorias.edtai.core.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.TokenUsage;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.JsonException;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tools.ProjectBrief;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolApprover;
import io.github.hansglorias.edtai.core.tools.ToolException;
import io.github.hansglorias.edtai.core.tools.ToolSource;

/**
 * Цикл агента: запрос к модели → вызовы инструментов → результаты обратно
 * модели → … пока модель не ответит текстом или не кончится лимит шагов.
 * Текст всех шагов идёт в один ответ.
 */
final class AgentLoop {

    /**
     * Сколько ответов модели с вызовами инструментов подряд допускаем: правка
     * формы или нескольких модулей — десятки вызовов; это защита от
     * зацикливания, а не от работы.
     */
    static final int MAX_STEPS = 50;

    /** Вместо результата старого шага, когда запрос не помещается в окно модели. */
    static final String OMITTED = AgentTranscript.OMITTED;

    static final String REPEATED = "Этот вызов с теми же аргументами уже был в этом ответе, его результат или ссылка для дочитывания выше. Не повторяй его:"
            + " используй полученный результат или вызови с другими аргументами.";

    /** Сколько раз урезать запрос после ответа «не помещается». */
    static final int MAX_SHRINK = 3;

    static final String MCP_REJECTED = "сервер модели не принял их описания, ответ только со встроенными инструментами. ";

    static final String TOOLS_UNSUPPORTED = "Сервер не принимает инструменты — ответ без чтения проекта."
            + " Отключите «Инструменты» в настройках, чтобы не видеть это сообщение.";

    private final ModelClient client;
    private final ModelConnection base;
    private final List<ChatMessage> history;
    private final Consumer<String> onText;
    private final Consumer<ToolUse> onTool;
    private final Consumer<TokenUsage> onUsage;
    private final ProtocolAdapter adapter;
    private final ToolSource source;
    private final ToolApprover approver;
    private final CompletableFuture<String> result = new CompletableFuture<>();

    private List<Tool> tools = List.of();
    private volatile CompletableFuture<ModelReply> current;
    private final StringBuilder answer = new StringBuilder();
    private boolean separate;
    /** Шаги этого ответа: вызовы модели и их результаты. */
    private final AgentTranscript transcript = new AgentTranscript();
    private final ToolExecutionLedger ledger = new ToolExecutionLedger();
    private final ToolResultStore resultStore;
    private final Tool resultReader;
    private final Map<ToolExecutionLedger.Key, String> resultReferences = new HashMap<>();
    /** Первый шаг, результат которого передаётся модели в последнем запросе. */
    private int firstVisibleStep;
    /** Что последним сообщили в ленту про обрезку истории. */
    private ToolUse trimmed;

    /** История и шаги, которые помещаются в окно модели. */
    private record Prepared(List<ChatMessage> history, List<Object> extra) {
    }
    /** Сколько раз сервер ответил «не помещается»: бюджет делится на 2 в этой степени. */
    private int shrink;
    /** Сервер отклонил запрос с инструментами: больше их не передаём. */
    private boolean toolsRejected;
    /** Токены ответа за прошедшие шаги. */
    private TokenUsage usage;
    /** Токены последнего запроса к модели. */
    private TokenUsage lastStep;

    /**
     * @param source   инструменты; запрашиваются в фоновом потоке перед первым запросом
     * @param approver спрашивает пользователя перед вызовом инструмента, которому нужно подтверждение
     */
    AgentLoop(ModelClient client, ModelConnection connection, List<ChatMessage> history, ToolSource source, ToolApprover approver,
            Consumer<String> onText, Consumer<ToolUse> onTool, Consumer<TokenUsage> onUsage) {
        this.client = client;
        this.base = connection;
        this.history = history;
        this.source = source;
        this.approver = approver;
        this.onText = onText;
        this.onTool = onTool;
        this.onUsage = onUsage;
        this.adapter = ProtocolAdapter.of(connection.protocol());
        this.resultStore = new ToolResultStore(Math.max(512, Math.min(6000, RequestBudget.chars(connection) / 8)), 1_000_000);
        this.resultReader = resultStore.tool();
    }

    boolean toolsRejected() {
        return toolsRejected;
    }

    CompletableFuture<String> start() {
        Thread worker = new Thread(this::run, "EDT AI: ответ модели с инструментами");
        worker.setDaemon(true);
        result.whenComplete((text, error) -> {
            if (result.isCancelled()) {
                CompletableFuture<ModelReply> request = current;
                if (request != null) {
                    request.cancel(true);
                }
                worker.interrupt();
            }
        });
        worker.start();
        return result;
    }

    private void run() {
        try {
            result.complete(loop());
        } catch (Throwable e) {
            result.completeExceptionally(e);
        }
    }

    private String loop() throws Exception {
        try {
            tools = List.copyOf(source.tools(onTool));
        } catch (RuntimeException e) {
            onTool.accept(new ToolUse("tools", "tools", "Инструменты", "не удалось получить список: " + e.getMessage(),
                    ToolUse.Status.FAILED));
        }
        if (tools.stream().anyMatch(tool -> tool.name().equals(resultReader.name()))) {
            throw new IllegalStateException("Имя read_tool_result зарезервировано для чтения результатов");
        }
        for (int step = 1;; step++) {
            checkCancelled();
            ModelReply reply = request();
            if (reply.calls().isEmpty()) {
                return ChatMessage.stripTrace(ThinkFilter.strip(answer.toString())).strip();
            }
            if (step >= MAX_STEPS) {
                text("_Остановлено: модель вызвала инструменты " + MAX_STEPS + " раз подряд. Напишите «продолжай»,"
                        + " чтобы она продолжила, или уточните задачу._");
                return answer.toString().strip();
            }
            List<ProtocolAdapter.ToolResult> results = new ArrayList<>();
            // Токены шага — у первого его вызова: шаг один, вызовов в нём может быть несколько.
            TokenUsage tokens = lastStep;
            for (ToolCall call : reply.calls()) {
                checkCancelled();
                results.add(execute(call, tokens));
                tokens = null;
            }
            transcript.add(reply, results);
            separate = answer.length() > 0;
        }
    }

    /**
     * Запрос под окно модели: сначала вместо результатов старых шагов —
     * пометка {@link #OMITTED}, потом обрезается история.
     */
    private Prepared prepare() {
        int budget = RequestBudget.chars(connection()) >> shrink;
        int omitted = 0;
        List<Object> extra = transcript.project(adapter, omitted);
        while (omitted < transcript.size() - 1 && RequestBudget.size(connection(), history, extra, tools) > budget) {
            extra = transcript.project(adapter, ++omitted);
        }
        // Обрезка меняет только представление: выполненные операции не забываются.
        firstVisibleStep = omitted;
        List<Object> fixed = extra;
        RequestBudget.Fitted fitted = RequestBudget.fit(history, part -> RequestBudget.size(connection(), part, fixed, tools), budget);
        if (fitted.trimmed() && !fitted.notice().equals(trimmed)) {
            trimmed = fitted.notice();
            onTool.accept(trimmed);
        }
        return new Prepared(fitted.history(), extra);
    }

    /**
     * Запрос к модели. Если сервер отклонил первый запрос из-за
     * инструментов — сначала без инструментов MCP-серверов (часто виновата
     * схема одного из них), потом совсем без инструментов.
     */
    private ModelReply request() throws Exception {
        if (resultStore.hasResults() && !tools.contains(resultReader)) {
            tools = new ArrayList<>(tools);
            tools.add(resultReader);
        }
        while (true) {
            checkCancelled();
            try {
                Prepared request = prepare();
                ModelReply reply = await(client.request(connection(), request.history(), request.extra(), tools, this::text));
                TokenUsage step = reply.usage() != null ? reply.usage()
                        : estimate(RequestBudget.size(connection(), request.history(), request.extra(), tools), reply);
                lastStep = step;
                usage = usage == null ? step : usage.plus(step);
                onUsage.accept(usage);
                return reply;
            } catch (ModelException e) {
                // Сервер говорит «не помещается» (окно угадали или задали больше настоящего): урезаем и повторяем.
                if (e.contextOverflow() && shrink < MAX_SHRINK) {
                    shrink++;
                    continue;
                }
                if (!e.rejectsTools() || tools.isEmpty() || !transcript.isEmpty()) {
                    throw e;
                }
                boolean anthropic = base.protocol() == Protocol.ANTHROPIC;
                // Встроенные инструменты — без подтверждения; инструменты MCP — с ним.
                List<Tool> builtIn = tools.stream().filter(tool -> !tool.needsApproval()).toList();
                if (!builtIn.isEmpty() && builtIn.size() < tools.size()) {
                    tools = builtIn;
                    onTool.accept(new ToolUse("tools-mcp", "tools", "Инструменты MCP", MCP_REJECTED + e.getMessage(),
                            ToolUse.Status.FAILED));
                } else if (anthropic) {
                    // Anthropic инструменты поддерживает всегда: ошибка про них — в самом запросе, её и покажем,
                    // а не будем молча отвечать без инструментов (модель тогда «делает» на словах).
                    throw e;
                } else {
                    toolsRejected = true;
                    tools = List.of();
                    onTool.accept(new ToolUse("tools", "tools", "Инструменты", TOOLS_UNSUPPORTED + " Причина: " + e.getMessage(),
                            ToolUse.Status.FAILED));
                }
            }
        }
    }

    /** Сервер не прислал usage: по длине запроса и ответа. */
    static TokenUsage estimate(int requestChars, ModelReply reply) {
        int answerChars = reply.text().length() + reply.calls().stream().mapToInt(call -> call.name().length() + call.arguments().length()).sum();
        return TokenUsage.step(requestChars / RequestBudget.CHARS_PER_TOKEN, answerChars / RequestBudget.CHARS_PER_TOKEN, true);
    }

    /** Подключение; если есть инструменты — с правилами работы с ними в системном промпте. */
    private ModelConnection connection() {
        return tools.isEmpty() ? base : base.withSystemPromptPart(ProjectBrief.TOOL_RULES);
    }

    private ProtocolAdapter.ToolResult execute(ToolCall call, TokenUsage tokens) {
        Consumer<ToolUse> onTool = use -> this.onTool.accept(tokens == null ? use : use.withUsage(tokens));
        Tool tool = tools.stream().filter(candidate -> candidate.name().equals(call.name())).findFirst().orElse(null);
        if (tool == null) {
            String error = "Инструмента «" + call.name() + "» нет";
            onTool.accept(ToolUse.failed(call.id(), call.name(), call.name(), ToolUse.Problem.NO_TOOL, ""));
            return new ProtocolAdapter.ToolResult(call, error, true);
        }
        Map<String, Object> input;
        try {
            input = call.input();
        } catch (JsonException e) {
            String error = "Аргументы вызова — не JSON-объект: " + e.getMessage();
            onTool.accept(ToolUse.failed(call.id(), call.name(), tool.name(), ToolUse.Problem.BAD_ARGUMENTS, e.getMessage()));
            return new ProtocolAdapter.ToolResult(call, error, true);
        }
        String summary = tool.summary(input);
        ToolExecutionLedger.Key key = ToolExecutionLedger.key(call.name(), input);
        // Ссылка на снимок остаётся даже в сокращённой истории: повтор источника не нужен.
        int visibleFrom = resultReferences.getOrDefault(key, "").isEmpty() ? firstVisibleStep : 0;
        ToolExecutionLedger.Decision decision = ledger.decide(key, visibleFrom);
        if (decision.action() != ToolExecutionLedger.Action.RUN) {
            transcript.remember(call.id(), resultReferences.getOrDefault(key, ""));
            return previous(call, summary, decision, onTool);
        }
        checkCancelled();
        if (tool.needsApproval()) {
            ToolUse waiting = new ToolUse(call.id(), call.name(), summary, "", ToolUse.Status.AWAITING);
            onTool.accept(waiting);
            ToolApprover.Decision approval = decide(waiting, call.arguments());
            checkCancelled();
            if (approval == ToolApprover.Decision.DENY) {
                ledger.denied(key, transcript.size());
                onTool.accept(ToolUse.failed(call.id(), call.name(), summary, ToolUse.Problem.DENIED, ""));
                return new ProtocolAdapter.ToolResult(call, "Пользователь отклонил вызов этого инструмента. Не вызывай его"
                        + " повторно с теми же аргументами; объясни, что хотел сделать, или ответь без него.", true);
            }
        }
        onTool.accept(new ToolUse(call.id(), call.name(), summary, "", ToolUse.Status.RUNNING));
        checkCancelled();
        ledger.started(key, tool, transcript.size());
        try {
            Tool.Result output = tool.call(input);
            String projected = tool == resultReader ? output.text() : capture(call, key, output.text(), false);
            ledger.succeeded(key, projected);
            onTool.accept(new ToolUse(call.id(), call.name(), summary, output.outcome(), ToolUse.Status.DONE).withFile(output.file()));
            return new ProtocolAdapter.ToolResult(call, projected, false);
        } catch (ToolException | RuntimeException e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            ToolUse.Problem problem = e instanceof ToolException failure ? failure.problem() : ToolUse.Problem.FAILED;
            error = capture(call, key, error, true);
            ledger.failed(key, error, problem);
            onTool.accept(ToolUse.failed(call.id(), call.name(), summary, problem, error));
            return new ProtocolAdapter.ToolResult(call, error, true);
        }
    }

    private String capture(ToolCall call, ToolExecutionLedger.Key key, String text, boolean error) {
        ToolResultStore.Captured captured = resultStore.captureResult(text, error);
        resultReferences.put(key, captured.reference());
        transcript.remember(call.id(), captured.reference());
        return captured.text();
    }

    /** Повтор показывает подтверждённый результат, но не воспроизводит побочные эффекты и вложения. */
    private ProtocolAdapter.ToolResult previous(ToolCall call, String summary, ToolExecutionLedger.Decision decision,
            Consumer<ToolUse> onTool) {
        if (decision.action() == ToolExecutionLedger.Action.REPLAY) {
            String outcome = "результат прежнего вызова, повторно не выполнялся";
            onTool.accept(new ToolUse(call.id(), call.name(), summary, outcome, ToolUse.Status.DONE));
            return new ProtocolAdapter.ToolResult(call, "Операция уже была выполнена в этом ответе; повторно не выполнялась."
                    + " Сохранённый результат того вызова (не проверка текущего состояния):\n" + decision.previous().text(), false);
        }
        String text = switch (decision.action()) {
            case DENIED -> ToolExecutionLedger.DENIED;
            case UNCERTAIN -> ToolExecutionLedger.UNCERTAIN + "\nПредыдущая ошибка: " + decision.previous().text();
            case FAILED -> "Повтор чтения не выполнен: прежняя ошибка требует других аргументов или исчерпан лимит"
                    + " повторов временной ошибки.\n" + decision.previous().text();
            default -> REPEATED;
        };
        ToolUse.Problem problem = decision.action() == ToolExecutionLedger.Action.DENIED
                ? ToolUse.Problem.DENIED : ToolUse.Problem.REPEATED;
        onTool.accept(ToolUse.failed(call.id(), call.name(), summary, problem,
                decision.action() == ToolExecutionLedger.Action.REPEATED ? "" : text));
        return new ProtocolAdapter.ToolResult(call, text, true);
    }

    private void checkCancelled() {
        if (result.isDone() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    /** Ждёт решения пользователя; прерывание ответа — отказ и конец ответа. */
    private ToolApprover.Decision decide(ToolUse use, String arguments) {
        CompletableFuture<ToolApprover.Decision> decision = approver.approve(use, arguments);
        try {
            return decision.get();
        } catch (InterruptedException e) {
            decision.cancel(true);
            Thread.currentThread().interrupt();
            return ToolApprover.Decision.DENY;
        } catch (ExecutionException | CancellationException e) {
            return ToolApprover.Decision.DENY;
        }
    }

    /** Часть текста ответа; между шагами — пустая строка, чтобы абзацы не слипались. */
    private void text(String part) {
        if (part.isEmpty() || result.isDone()) {
            return;
        }
        if (separate) {
            separate = false;
            String gap = answer.toString().endsWith("\n\n") ? "" : answer.toString().endsWith("\n") ? "\n" : "\n\n";
            answer.append(gap);
            onText.accept(gap);
        }
        answer.append(part);
        onText.accept(part);
    }

    private ModelReply await(CompletableFuture<ModelReply> request) throws Exception {
        current = request;
        if (result.isDone()) {
            request.cancel(true);
            throw new CancellationException();
        }
        try {
            return request.get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        } catch (InterruptedException e) {
            request.cancel(true);
            throw new CancellationException();
        }
    }
}
