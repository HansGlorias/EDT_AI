package io.github.hansglorias.edtai.core.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.TokenUsage;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.Json;
import io.github.hansglorias.edtai.core.json.JsonException;
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
    static final String OMITTED = "(результат не передан: не помещается в окно модели; если он нужен — вызови инструмент снова)";

    static final String REPEATED = "Этот вызов с теми же аргументами уже был в этом ответе, его результат выше. Не повторяй его:"
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
    private final List<Step> steps = new ArrayList<>();
    /** Что последним сообщили в ленту про обрезку истории. */
    private ToolUse trimmed;

    private record Step(ModelReply reply, List<ProtocolAdapter.ToolResult> results) {
    }

    /** История и шаги, которые помещаются в окно модели. */
    private record Prepared(List<ChatMessage> history, List<Object> extra) {
    }
    /** Вызовы этого ответа (имя и аргументы) → номер шага: повтор не выполняем. */
    private final Map<String, Integer> seen = new HashMap<>();
    /** Сколько раз сервер ответил «не помещается»: бюджет делится на 2 в этой степени. */
    private int shrink;
    /** Сервер отклонил запрос с инструментами: больше их не передаём. */
    private boolean toolsRejected;
    /** Токены ответа за прошедшие шаги. */
    private TokenUsage usage;

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
        for (int step = 1;; step++) {
            ModelReply reply = request();
            if (reply.calls().isEmpty()) {
                return ThinkFilter.strip(answer.toString()).strip();
            }
            if (step >= MAX_STEPS) {
                text("_Остановлено: модель вызвала инструменты " + MAX_STEPS + " раз подряд. Напишите «продолжай»,"
                        + " чтобы она продолжила, или уточните задачу._");
                return answer.toString().strip();
            }
            List<ProtocolAdapter.ToolResult> results = new ArrayList<>();
            for (ToolCall call : reply.calls()) {
                results.add(execute(call));
            }
            steps.add(new Step(reply, results));
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
        List<Object> extra = extra(omitted);
        while (omitted < steps.size() - 1 && RequestBudget.size(connection(), history, extra, tools) > budget) {
            extra = extra(++omitted);
        }
        // Результат пропущенного шага модели больше не виден: повторить такой вызов можно.
        int visibleFrom = omitted;
        seen.values().removeIf(step -> step < visibleFrom);
        List<Object> fixed = extra;
        RequestBudget.Fitted fitted = RequestBudget.fit(history, part -> RequestBudget.size(connection(), part, fixed, tools), budget);
        if (fitted.trimmed() && !fitted.notice().equals(trimmed)) {
            trimmed = fitted.notice();
            onTool.accept(trimmed);
        }
        return new Prepared(fitted.history(), extra);
    }

    /** Вызовы и результаты шагов в формате протокола; у первых {@code omitted} шагов — без результатов. */
    private List<Object> extra(int omitted) {
        List<Object> extra = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            extra.add(adapter.assistantMessage(step.reply()));
            List<ProtocolAdapter.ToolResult> results = step.results();
            if (i < omitted) {
                results = results.stream().map(result -> new ProtocolAdapter.ToolResult(result.call(), OMITTED, false)).toList();
            }
            extra.addAll(adapter.toolResults(results));
        }
        return extra;
    }

    /**
     * Запрос к модели. Если сервер отклонил первый запрос из-за
     * инструментов — сначала без инструментов MCP-серверов (часто виновата
     * схема одного из них), потом совсем без инструментов.
     */
    private ModelReply request() throws Exception {
        while (true) {
            try {
                Prepared request = prepare();
                ModelReply reply = await(client.request(connection(), request.history(), request.extra(), tools, this::text));
                TokenUsage step = reply.usage() != null ? reply.usage()
                        : estimate(RequestBudget.size(connection(), request.history(), request.extra(), tools), reply);
                usage = usage == null ? step : usage.plus(step);
                onUsage.accept(usage);
                return reply;
            } catch (ModelException e) {
                // Сервер говорит «не помещается» (окно угадали или задали больше настоящего): урезаем и повторяем.
                if (e.contextOverflow() && shrink < MAX_SHRINK) {
                    shrink++;
                    continue;
                }
                if (!e.rejectsTools() || tools.isEmpty() || !steps.isEmpty()) {
                    throw e;
                }
                // Встроенные инструменты — без подтверждения; инструменты MCP — с ним.
                List<Tool> builtIn = tools.stream().filter(tool -> !tool.needsApproval()).toList();
                if (!builtIn.isEmpty() && builtIn.size() < tools.size()) {
                    tools = builtIn;
                    onTool.accept(new ToolUse("tools-mcp", "tools", "Инструменты MCP", MCP_REJECTED + e.getMessage(),
                            ToolUse.Status.FAILED));
                } else {
                    toolsRejected = true;
                    tools = List.of();
                    onTool.accept(new ToolUse("tools", "tools", "Инструменты", TOOLS_UNSUPPORTED, ToolUse.Status.FAILED));
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

    private ProtocolAdapter.ToolResult execute(ToolCall call) {
        Tool tool = tools.stream().filter(candidate -> candidate.name().equals(call.name())).findFirst().orElse(null);
        if (tool == null) {
            String error = "Инструмента «" + call.name() + "» нет";
            onTool.accept(new ToolUse(call.id(), call.name(), call.name(), error, ToolUse.Status.FAILED));
            return new ProtocolAdapter.ToolResult(call, error, true);
        }
        Map<String, Object> input;
        try {
            input = call.input();
        } catch (JsonException e) {
            String error = "Аргументы вызова — не JSON-объект: " + e.getMessage();
            onTool.accept(new ToolUse(call.id(), call.name(), tool.name(), error, ToolUse.Status.FAILED));
            return new ProtocolAdapter.ToolResult(call, error, true);
        }
        String summary = tool.summary(input);
        String key = call.name() + " " + Json.write(input);
        if (seen.containsKey(key)) {
            onTool.accept(new ToolUse(call.id(), call.name(), summary, "повтор того же вызова, не выполнен", ToolUse.Status.FAILED));
            return new ProtocolAdapter.ToolResult(call, REPEATED, true);
        }
        if (tool.needsApproval()) {
            ToolUse waiting = new ToolUse(call.id(), call.name(), summary, "", ToolUse.Status.AWAITING);
            onTool.accept(waiting);
            ToolApprover.Decision decision = decide(waiting, call.arguments());
            if (decision == ToolApprover.Decision.DENY) {
                onTool.accept(new ToolUse(call.id(), call.name(), summary, "отклонено", ToolUse.Status.FAILED));
                return new ProtocolAdapter.ToolResult(call, "Пользователь отклонил вызов этого инструмента. Не вызывай его"
                        + " повторно с теми же аргументами; объясни, что хотел сделать, или ответь без него.", true);
            }
        }
        onTool.accept(new ToolUse(call.id(), call.name(), summary, "", ToolUse.Status.RUNNING));
        seen.put(key, steps.size());
        try {
            Tool.Result output = tool.call(input);
            onTool.accept(new ToolUse(call.id(), call.name(), summary, output.outcome(), ToolUse.Status.DONE));
            return new ProtocolAdapter.ToolResult(call, output.text(), false);
        } catch (ToolException | RuntimeException e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            onTool.accept(new ToolUse(call.id(), call.name(), summary, error, ToolUse.Status.FAILED));
            return new ProtocolAdapter.ToolResult(call, error, true);
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
