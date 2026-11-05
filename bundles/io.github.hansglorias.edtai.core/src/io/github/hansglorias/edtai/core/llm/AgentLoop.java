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
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Id;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.RequestType;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.JsonException;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.tools.ProjectBrief;
import io.github.hansglorias.edtai.core.tools.ProjectTools;
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

    /** После этого шага модели напоминают, что пора отвечать: небольшие модели иначе ищут без конца. */
    static final int WRAP_UP_STEP = 30;

    static final String WRAP_UP = "(Служебная пометка EDT AI: это уже " + WRAP_UP_STEP + "-й шаг с инструментами из "
            + MAX_STEPS + " возможных. Если найденного достаточно — отвечай пользователю. Если нет — продолжай только по делу,"
            + " не перебирай варианты наугад.)";

    static final String LAST_STEP = "(Служебная пометка EDT AI: это был последний шаг с инструментами. Больше их не вызывай:"
            + " ответь пользователю по найденному — что выяснил, со ссылками Модуль:строка, и чего не хватило.)";

    /** Вместо результата старого шага, когда запрос не помещается в окно модели. */
    static final String OMITTED = AgentTranscript.OMITTED;

    static final String REPEATED = "Этот вызов с теми же аргументами уже был в этом ответе, его результат или ссылка для дочитывания выше. Не повторяй его:"
            + " используй полученный результат или вызови с другими аргументами.";

    /** Шаг, вызовы которого в основном — заблокированные повторы: предупреждение модели. */
    static final String STUCK = "(Служебная пометка EDT AI: вызовы этого шага в основном повторяют уже сделанные, повторы"
            + " не выполнены. Их результаты выше или дочитываются read_tool_result. Ещё один такой шаг — и инструменты будут"
            + " отключены: переходи к делу — правке или ответу пользователю.)";

    /** Повторы не прекратились или исчерпан лимит разведки: последний запрос без инструментов. */
    static final String FINAL = "(Служебная пометка EDT AI: инструменты отключены — вызовы этого шага не выполнены."
            + " Ответь по уже найденному: что выяснил, со ссылками Модуль:строка, что собирался сделать дальше и чего не хватило.)";

    static final String SUMMARY_ONLY = "Лимит работы специалиста достигнут. Больше не вызывай инструменты."
            + " Дай текстовый итог по уже проверенному, ссылки Модуль:строка и то, чего не удалось проверить.";

    /** После скольких шагов подряд, в основном из повторов, инструменты отключаются. */
    static final int MAX_STUCK_STEPS = 2;

    /** Модель пишет о правках, не вызвав инструмент изменения: один раз просим внести их или исправить ответ. */
    static final String UNMADE_CHANGES = "(Служебная пометка EDT AI: в этом ответе не было ни одного успешного вызова"
            + " инструмента изменения, а ты пишешь, что правки сделаны. Файлы не изменены. Если правки нужны — внеси их"
            + " инструментом сейчас и проверь результат; если нет — прямо скажи пользователю, что ничего не менял.)";

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
    private Runnable onReasoning = () -> { };
    private final ProtocolAdapter adapter;
    private final ToolSource source;
    private final ToolApprover approver;
    private final int maxSteps;
    private final boolean exploration;
    private int requests;
    private boolean incomplete;
    private String incompleteReason = "";
    private boolean uncertainChildUsage;
    private final Map<String, TokenUsage> childUsage = new HashMap<>();
    private TokenUsage displayedUsage;
    private final CompletableFuture<String> result = new CompletableFuture<>();

    private List<Tool> tools = List.of();
    private List<Tool> fixedTools = List.of();
    private McpToolCatalog catalog;
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
    /** Сколько первых шагов уже без результатов: только растёт, чтобы начало запроса не менялось. */
    private int omittedSteps;
    /** Что последним сообщили в ленту про обрезку истории. */
    private ToolUse trimmed;

    /** История и шаги, которые помещаются в окно модели. */
    private record FittedRequest(List<ChatMessage> history, List<Object> extra) {
    }
    /** Сколько раз сервер ответил «не помещается»: бюджет делится на 2 в этой степени. */
    private int shrink;
    /** Сервер отклонил запрос с инструментами: больше их не передаём. */
    private boolean toolsRejected;
    /** Последний запрос ответа — без инструментов: модель зациклилась или разведка исчерпала лимит. */
    private boolean toolsOff;
    /** В этом ответе успешно выполнен инструмент изменения. */
    private boolean changed;
    /** Ответ уже проверяли на правки без инструментов. */
    private boolean claimChecked;
    /** Вызовов текущего шага, заблокированных как повторы. */
    private int blockedInStep;
    /** Шагов подряд, не меньше половины вызовов которых — заблокированные повторы. */
    private int stuckSteps;
    /** Токены ответа за прошедшие шаги. */
    private TokenUsage usage;
    /** Токены последнего запроса к модели. */
    private TokenUsage lastStep;
    private final Id diagnosticRun;

    /**
     * @param source   инструменты; запрашиваются в фоновом потоке перед первым запросом
     * @param approver спрашивает пользователя перед вызовом инструмента, которому нужно подтверждение
     */
    AgentLoop(ModelClient client, ModelConnection connection, List<ChatMessage> history, ToolSource source, ToolApprover approver,
            Consumer<String> onText, Consumer<ToolUse> onTool, Consumer<TokenUsage> onUsage) {
        this(client, connection, history, source, approver, onText, onTool, onUsage, MAX_STEPS, false);
    }

    AgentLoop(ModelClient client, ModelConnection connection, List<ChatMessage> history, ToolSource source, ToolApprover approver,
            Consumer<String> onText, Consumer<ToolUse> onTool, Consumer<TokenUsage> onUsage, int maxSteps, boolean exploration) {
        this.client = client;
        this.diagnosticRun = client.newRunId();
        this.base = connection;
        this.history = history;
        this.source = source;
        this.approver = approver;
        this.onText = onText;
        this.onTool = onTool;
        this.onUsage = onUsage;
        this.maxSteps = maxSteps;
        this.exploration = exploration;
        this.adapter = ProtocolAdapter.of(connection.protocol());
        this.resultStore = new ToolResultStore(RequestBudget.page(connection), 1_000_000);
        this.resultReader = resultStore.tool();
    }

    /** Сообщать, что модель прислала часть рассуждения; до {@link #start()}. */
    void onReasoning(Runnable value) {
        onReasoning = value;
    }

    boolean toolsRejected() {
        return toolsRejected;
    }

    boolean incomplete() {
        return incomplete || toolsRejected;
    }

    String incompleteReason() {
        return !incompleteReason.isEmpty() ? incompleteReason
                : toolsRejected ? "сервер модели не принял инструменты" : "";
    }

    private void markIncomplete(String reason) {
        incomplete = true;
        if (incompleteReason.isEmpty()) incompleteReason = reason;
    }

    private synchronized void publishUsage(TokenUsage parent) {
        if (exploration && uncertainChildUsage) parent = uncertain(parent);
        displayedUsage = parent;
        TokenUsage total = parent;
        for (TokenUsage child : childUsage.values()) total = total == null ? child : child.plus(total);
        if (total != null && !result.isDone()) onUsage.accept(total);
    }

    private static TokenUsage uncertain(TokenUsage value) {
        return new TokenUsage(value.input(), value.output(), value.context(), TokenUsage.Source.UNKNOWN,
                TokenUsage.Source.UNKNOWN, value.contextSource());
    }

    private synchronized void childUsage(String id, TokenUsage value) {
        childUsage.put(id, value);
        publishUsage(displayedUsage);
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
            if (!exploration) {
                List<Tool> reads = new ArrayList<>(source.explorationTools().stream().filter(tool -> ProjectTools.isBuiltInRead(tool)
                        || io.github.hansglorias.edtai.core.tools.GitTools.isBuiltInRead(tool)).toList());
                tools.stream().filter(tool -> tool.mcp() && tool.safeInPlan() && !tool.modifies()).forEach(reads::add);
                if (!reads.isEmpty()) {
                    tools = new ArrayList<>(tools);
                    ExploreProject runner = new ExploreProject(client, base, reads, use -> {
                        if (!result.isDone()) onTool.accept(use);
                    }, this::childUsage, approver);
                    tools.add(runner);
                    tools.add(new DelegateTask(runner));
                }
            }
        } catch (RuntimeException e) {
            onTool.accept(new ToolUse("tools", "tools", "Инструменты", "не удалось получить список: " + e.getMessage(),
                    ToolUse.Status.FAILED));
        }
        if (tools.stream().anyMatch(tool -> (tool.name().equals("explore_project") && !(tool instanceof ExploreProject))
                || (tool.name().equals("delegate_task") && !(tool instanceof DelegateTask)))) {
            throw new IllegalStateException("Имена explore_project и delegate_task зарезервированы");
        }
        if (tools.stream().anyMatch(tool -> tool.name().equals(resultReader.name()))) {
            throw new IllegalStateException("Имя read_tool_result зарезервировано для чтения результатов");
        }
        catalog = new McpToolCatalog(tools);
        if (!catalog.empty()) {
            if (tools.stream().anyMatch(tool -> tool.name().equals(McpToolCatalog.NAME))) {
                throw new IllegalStateException("Имя discover_mcp_tools зарезервировано");
            }
            fixedTools = new ArrayList<>(tools.stream().filter(tool -> !tool.mcp()).toList());
            fixedTools.add(catalog);
            tools = List.copyOf(fixedTools);
            catalog.fits(selected -> {
                List<Tool> candidate = new ArrayList<>(fixedTools);
                candidate.addAll(selected);
                candidate.add(resultReader); // Резерв для дочитывания результатов.
                List<Object> retained = transcript.project(adapter, transcript.size());
                int budget = RequestBudget.chars(connection()) >> shrink;
                RequestBudget.Fitted fitted = RequestBudget.fit(history,
                        part -> RequestBudget.size(connection(), part, retained, candidate), budget);
                return RequestBudget.size(connection(), fitted.history(), retained, candidate) <= budget;
            });
        }
        for (int step = 1;; step++) {
            checkCancelled();
            ModelReply reply = request();
            if (reply.calls().isEmpty() && unmadeChanges(reply)) {
                continue;
            }
            if (reply.calls().isEmpty() || toolsOff) {
                if (reply.truncated()) markIncomplete("сервер остановил генерацию по лимиту токенов ответа");
                if (toolsOff && !incomplete) markIncomplete("инструменты отключены до завершения исследования");
                if (exploration) return ChatMessage.stripTraces(ThinkFilter.strip(reply.text())).strip();
                return ChatMessage.stripTraces(ThinkFilter.strip(answer.toString())).strip();
            }
            if (step >= maxSteps) {
                markIncomplete("достигнут лимит " + maxSteps + " шагов специалиста");
                if (exploration) {
                    // Работа разведчика не пропадает: последний запрос без инструментов — итог по найденному.
                    if (finalWithoutTools(reply)) continue;
                    return "Лимит запросов разведки исчерпан; итог не завершён.";
                }
                text("_Остановлено: модель вызвала инструменты " + MAX_STEPS + " раз подряд. Напишите «продолжай»,"
                        + " чтобы она продолжила, или уточните задачу._");
                return answer.toString().strip();
            }
            List<Tool> previousSelection = catalog.selected();
            List<ProtocolAdapter.ToolResult> results = new ArrayList<>();
            // Токены шага — у первого его вызова: шаг один, вызовов в нём может быть несколько.
            TokenUsage tokens = lastStep;
            blockedInStep = 0;
            for (ToolCall call : reply.calls()) {
                checkCancelled();
                results.add(execute(call, tokens));
                tokens = null;
            }
            // Модель повторяет одни и те же вызовы: предупреждаем, затем отвечаем без инструментов.
            stuckSteps = blockedInStep > 0 && blockedInStep * 2 >= reply.calls().size() ? stuckSteps + 1 : 0;
            if (stuckSteps >= MAX_STUCK_STEPS) {
                markIncomplete("модель повторяла уже выполненные вызовы инструментов");
                onTool.accept(new ToolUse("loop", "tools", "Повторы вызовов", "модель повторяла одни и те же вызовы "
                        + stuckSteps + " шага подряд — инструменты отключены, ответ по найденному", ToolUse.Status.FAILED));
                transcript.add(reply, withNote(results, FINAL));
                toolsOff = true;
                continue;
            }
            String note = stuckSteps > 0 ? STUCK : step == maxSteps - 1 ? LAST_STEP : step == WRAP_UP_STEP ? WRAP_UP : "";
            if (!note.isEmpty()) {
                results = withNote(results, note);
            }
            if (!catalog.selected().equals(previousSelection) && !selectionFits(reply, results)) {
                catalog.restore(previousSelection);
                String error = "Выбор MCP-схем не применён: следующий запрос с результатами пакета не помещается "
                        + "в оценочный бюджет. Прежний набор сохранён; выбери меньше инструментов.";
                for (int i = 0; i < results.size(); i++) {
                    ProtocolAdapter.ToolResult value = results.get(i);
                    if (value.call().name().equals(McpToolCatalog.NAME) && !value.error()
                            && "select".equals(value.call().input().get("action"))) {
                        results.set(i, new ProtocolAdapter.ToolResult(value.call(), error, true));
                        onTool.accept(ToolUse.failed(value.call().id(), value.call().name(), catalog.summary(Map.of()),
                                ToolUse.Problem.BAD_ARGUMENTS, error));
                    }
                }
            }
            transcript.add(reply, results);
            separate = answer.length() > 0;
        }
    }

    /**
     * Модель закончила ответ словами о сделанных правках, но инструмент изменения в этом ответе успешно
     * не выполнялся, хотя был доступен: один раз возвращаем её к делу пометкой. В ленте — строка проверки.
     *
     * @return ответ продолжается
     */
    private boolean unmadeChanges(ModelReply reply) {
        if (exploration || toolsOff || claimChecked || changed || tools.stream().noneMatch(Tool::modifies)
                || !ChangeClaims.claims(ThinkFilter.strip(reply.text()))) {
            return false;
        }
        claimChecked = true;
        onTool.accept(new ToolUse("claim", "tools", "Проверка ответа", "модель написала о правках, но не вызывала"
                + " инструмент изменения — попросили внести их или исправить ответ", ToolUse.Status.FAILED));
        transcript.addNote(reply, UNMADE_CHANGES);
        separate = answer.length() > 0;
        return true;
    }

    /** Служебная пометка — к последнему результату пакета. */
    private static List<ProtocolAdapter.ToolResult> withNote(List<ProtocolAdapter.ToolResult> results, String note) {
        if (results.isEmpty()) {
            return results;
        }
        List<ProtocolAdapter.ToolResult> noted = new ArrayList<>(results);
        ProtocolAdapter.ToolResult last = noted.get(noted.size() - 1);
        noted.set(noted.size() - 1, new ProtocolAdapter.ToolResult(last.call(), last.text() + "\n\n" + note, last.error()));
        return noted;
    }

    /**
     * Следующий запрос — последний: вызовы шага не выполняются, модель отвечает по найденному.
     * Для Anthropic сохраняются описания инструментов, необходимые для истории tool_use/tool_result.
     *
     * @return будет ли такой запрос
     */
    private boolean finalWithoutTools(ModelReply reply) {
        if (toolsOff) {
            return false;
        }
        List<ProtocolAdapter.ToolResult> results = new ArrayList<>();
        for (ToolCall call : reply.calls()) {
            results.add(new ProtocolAdapter.ToolResult(call, "Не выполнено: лимит шагов.", true));
        }
        transcript.add(reply, withNote(results, FINAL));
        toolsOff = true;
        return true;
    }

    /** Проверяем весь пакет до публикации выбранных схем: последние результаты не обрезаются. */
    private boolean selectionFits(ModelReply reply, List<ProtocolAdapter.ToolResult> results) {
        List<Tool> candidate = new ArrayList<>(fixedTools);
        candidate.addAll(catalog.selected());
        candidate.add(resultReader);
        List<Object> extra = new ArrayList<>(transcript.project(adapter, transcript.size()));
        extra.add(adapter.assistantMessage(reply));
        extra.addAll(adapter.toolResults(results));
        int budget = RequestBudget.chars(connection()) >> shrink;
        RequestBudget.Fitted fitted = RequestBudget.fit(history,
                part -> RequestBudget.size(connection(), part, extra, candidate), budget);
        return RequestBudget.size(connection(), fitted.history(), extra, candidate) <= budget;
    }

    /**
     * Запрос под окно модели: сначала вместо результатов старых шагов —
     * пометка {@link #OMITTED}, потом обрезается история.
     */
    private FittedRequest prepare() throws ModelException {
        RequestBudget.requireInputSpace(connection());
        int budget = RequestBudget.chars(connection()) >> shrink;
        // Результаты старых шагов убираются уже сверх рабочей части окна, а не только у его края.
        int working = RequestBudget.working(budget);
        int omitted = Math.min(omittedSteps, Math.max(0, transcript.size() - 1));
        List<Object> extra = transcript.project(adapter, omitted);
        if (RequestBudget.size(connection(), history, extra, tools) > working) {
            // При большом окне убираем сразу пачкой, до двух третей рабочей части: начало запроса потом
            // несколько шагов не меняется, и кэш префикса на сервере модели не сбрасывается каждый шаг.
            int target = working < budget ? working * 2 / 3 : working;
            while (omitted < transcript.size() - 1 && RequestBudget.size(connection(), history, extra, tools) > target) {
                extra = transcript.project(adapter, ++omitted);
            }
        }
        omittedSteps = omitted;
        // Обрезка меняет только представление: выполненные операции не забываются.
        firstVisibleStep = omitted;
        List<Object> fixed = extra;
        RequestBudget.Fitted fitted = RequestBudget.fit(history, part -> RequestBudget.size(connection(), part, fixed, tools), budget);
        if (fitted.trimmed() && !fitted.notice().equals(trimmed)) {
            trimmed = fitted.notice();
            onTool.accept(trimmed);
        }
        return new FittedRequest(fitted.history(), extra);
    }

    /**
     * Запрос к модели. Если сервер отклонил первый запрос из-за
     * инструментов — сначала без инструментов MCP-серверов (часто виновата
     * схема одного из них), потом совсем без инструментов.
     */
    private ModelReply request() throws Exception {
        if (toolsOff && base.protocol() != Protocol.ANTHROPIC) {
            tools = List.of();
        } else if (catalog != null && !catalog.empty() && !toolsRejected) {
            // Только на границе запроса: весь предыдущий пакет выполнялся по предложенным ему схемам.
            tools = new ArrayList<>(fixedTools);
            tools.addAll(catalog.selected());
        }
        if (resultStore.hasResults() && !tools.contains(resultReader) && !(toolsOff && tools.isEmpty())) {
            tools = new ArrayList<>(tools);
            tools.add(resultReader);
        }
        RequestContext context = diagnosticRun == null ? null : new RequestContext(diagnosticRun, RequestType.ANSWER, 0);
        while (true) {
            checkCancelled();
            if (exploration && requests == maxSteps && !toolsOff) {
                markIncomplete("достигнут лимит " + maxSteps + " физических запросов специалиста");
                toolsOff = true;
                if (base.protocol() != Protocol.ANTHROPIC) tools = List.of();
            }
            if (exploration && ++requests > maxSteps + 1) {
                markIncomplete("исчерпан резерв последнего запроса итога специалиста");
                throw new ModelException("Лимит физических запросов разведки исчерпан");
            }
            RequestDiagnostics attempt = client.begin(context);
            try {
                FittedRequest fitted = prepare();
                ModelConnection connection = connection();
                PreparedRequest request = PreparedRequest.prepare(connection, fitted.history(), fitted.extra(), tools);
                TokenUsage sending = TokenUsage.sending(usage, request.estimatedInputTokens());
                publishUsage(exploration ? uncertain(sending) : sending);
                ModelReply reply;
                String prior = answer.toString();
                String gap = !separate || prior.isEmpty() || prior.endsWith("\n\n") ? ""
                        : prior.endsWith("\n") ? "\n" : "\n\n";
                try {
                    reply = await(client.request(connection, request, this::text, onReasoning, attempt, RequestBudget.chars(connection) >> shrink));
                } catch (Exception e) {
                    // Без usage прерванного/ошибочного запроса известна только часть расхода ребёнка.
                    if (exploration) uncertainChildUsage = true;
                    if (e instanceof GenerationStoppedException stopped) {
                        markIncomplete("повторяющаяся генерация остановлена");
                        throw new GenerationStoppedException(ChatMessage.stripTraces(prior
                                + (stopped.safeText().isEmpty() ? "" : gap + stopped.safeText())).stripTrailing());
                    }
                    throw e;
                }
                TokenUsage estimate = estimate(request.estimatedInputChars(), reply);
                TokenUsage step = reply.usage() == null ? estimate : reply.usage().fillMissing(estimate);
                lastStep = step;
                usage = usage == null ? step : usage.plus(step);
                publishUsage(usage);
                return reply;
            } catch (ModelException e) {
                client.localFailure(attempt, true);
                // Сервер говорит «не помещается» (окно угадали или задали больше настоящего): урезаем и повторяем.
                if (e.contextOverflow() && shrink < MAX_SHRINK) {
                    shrink++;
                    context = RequestContext.retry(context);
                    continue;
                }
                if (!e.rejectsTools() || tools.isEmpty()
                        || !transcript.isEmpty() && tools.stream().noneMatch(Tool::mcp)) {
                    throw e;
                }
                boolean anthropic = base.protocol() == Protocol.ANTHROPIC;
                // Встроенные инструменты — без подтверждения; инструменты MCP — с ним.
                List<Tool> builtIn = tools.stream().filter(tool -> !tool.needsApproval()).toList();
                if (!builtIn.isEmpty() && builtIn.size() < tools.size()) {
                    tools = builtIn;
                    if (catalog != null) catalog.disable();
                    fixedTools = builtIn;
                    onTool.accept(new ToolUse("tools-mcp", "tools", "Инструменты MCP", MCP_REJECTED + e.getMessage(),
                            ToolUse.Status.FAILED));
                } else if (anthropic) {
                    // Anthropic инструменты поддерживает всегда: ошибка про них — в самом запросе, её и покажем,
                    // а не будем молча отвечать без инструментов (модель тогда «делает» на словах).
                    throw e;
                } else {
                    toolsRejected = true;
                    if (catalog != null) catalog.disable();
                    tools = List.of();
                    onTool.accept(new ToolUse("tools", "tools", "Инструменты", TOOLS_UNSUPPORTED + " Причина: " + e.getMessage(),
                            ToolUse.Status.FAILED));
                }
                context = RequestContext.retry(context);
            } catch (RuntimeException e) {
                client.localFailure(attempt, false);
                throw e;
            }
        }
    }

    /** Сервер не прислал usage: по длине запроса и ответа. */
    static TokenUsage estimate(int requestChars, ModelReply reply) {
        // Рассуждение сервер тоже считает в выходе (и в max_tokens).
        int answerChars = reply.text().length() + reply.reasoning().text().length() + reply.calls().stream().mapToInt(call -> call.name().length() + call.arguments().length()).sum();
        return TokenUsage.step(requestChars / RequestBudget.CHARS_PER_TOKEN, answerChars / RequestBudget.CHARS_PER_TOKEN, true);
    }

    /** Подключение; если есть инструменты — с правилами работы с ними в системном промпте. */
    private ModelConnection connection() {
        if (exploration && toolsOff) return base.withSystemPromptPart(SUMMARY_ONLY);
        if (exploration || tools.isEmpty()) return base;
        ModelConnection connection = base.withSystemPromptPart(ProjectBrief.TOOL_RULES);
        return tools.stream().anyMatch(DelegateTask.class::isInstance)
                ? connection.withSystemPromptPart(ExploreProject.PARENT_RULES) : connection;
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
        // Локальный выбор схем не является фактом чтения/записи проекта. Повторный выбор допустим.
        boolean control = tool == catalog;
        ToolExecutionLedger.Decision decision = control
                ? new ToolExecutionLedger.Decision(ToolExecutionLedger.Action.RUN, null) : ledger.decide(key, visibleFrom);
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
        if (!control) ledger.started(key, tool, transcript.size());
        try {
            Tool.Result output = tool instanceof ExploreProject explore ? explore.call(input, call.id())
                    : tool instanceof DelegateTask delegate ? delegate.call(input, call.id()) : tool.call(input);
            if (tool instanceof ExploreProject || tool instanceof DelegateTask) checkCancelled();
            String projected = tool == resultReader || control ? output.text() : capture(call, key, output.text(), false);
            if (!control) ledger.succeeded(key, projected);
            if (tool.modifies()) changed = true;
            onTool.accept(new ToolUse(call.id(), call.name(), summary, output.outcome(), ToolUse.Status.DONE).withFile(output.file()));
            return new ProtocolAdapter.ToolResult(call, projected, false);
        } catch (CancellationException e) {
            throw e;
        } catch (ToolException | RuntimeException e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            ToolUse.Problem problem = e instanceof ToolException failure ? failure.problem() : ToolUse.Problem.FAILED;
            if (!control) error = capture(call, key, error, true);
            if (!control) ledger.failed(key, error, problem);
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
        if (problem == ToolUse.Problem.REPEATED) {
            blockedInStep++;
        }
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
