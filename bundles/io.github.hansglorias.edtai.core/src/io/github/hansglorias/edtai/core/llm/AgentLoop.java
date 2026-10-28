package io.github.hansglorias.edtai.core.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.json.JsonException;
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

    /** Сколько ответов модели с вызовами инструментов подряд допускаем. */
    static final int MAX_STEPS = 10;

    static final String TOOLS_UNSUPPORTED = "Сервер не принимает инструменты — ответ без чтения проекта."
            + " Отключите «Инструменты» в настройках, чтобы не видеть это сообщение.";

    private final ModelClient client;
    private final ModelConnection connection;
    private final List<ChatMessage> history;
    private final Consumer<String> onText;
    private final Consumer<ToolUse> onTool;
    private final ProtocolAdapter adapter;
    private final ToolSource source;
    private final ToolApprover approver;
    private final CompletableFuture<String> result = new CompletableFuture<>();

    private List<Tool> tools = List.of();
    private volatile CompletableFuture<ModelReply> current;
    private final StringBuilder answer = new StringBuilder();
    private boolean separate;
    /** Сервер отклонил запрос с инструментами: больше их не передаём. */
    private boolean toolsRejected;

    /**
     * @param source   инструменты; запрашиваются в фоновом потоке перед первым запросом
     * @param approver спрашивает пользователя перед вызовом инструмента, которому нужно подтверждение
     */
    AgentLoop(ModelClient client, ModelConnection connection, List<ChatMessage> history, ToolSource source, ToolApprover approver,
            Consumer<String> onText, Consumer<ToolUse> onTool) {
        this.client = client;
        this.connection = connection;
        this.history = history;
        this.source = source;
        this.approver = approver;
        this.onText = onText;
        this.onTool = onTool;
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
        List<Object> extra = new ArrayList<>();
        for (int step = 1;; step++) {
            ModelReply reply;
            try {
                reply = await(client.request(connection, history, extra, tools, this::text));
            } catch (ModelException e) {
                if (!e.rejectsTools() || tools.isEmpty() || !extra.isEmpty()) {
                    throw e;
                }
                toolsRejected = true;
                tools = List.of();
                onTool.accept(new ToolUse("tools", "tools", "Инструменты", TOOLS_UNSUPPORTED, ToolUse.Status.FAILED));
                reply = await(client.request(connection, history, extra, tools, this::text));
            }
            if (reply.calls().isEmpty()) {
                return answer.toString().strip();
            }
            if (step >= MAX_STEPS) {
                text("_Остановлено: модель вызвала инструменты " + MAX_STEPS + " раз подряд. Уточните вопрос или"
                        + " нажмите «Повторить»._");
                return answer.toString().strip();
            }
            extra.add(adapter.assistantMessage(reply));
            List<ProtocolAdapter.ToolResult> results = new ArrayList<>();
            for (ToolCall call : reply.calls()) {
                results.add(execute(call));
            }
            extra.addAll(adapter.toolResults(results));
            separate = answer.length() > 0;
        }
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
