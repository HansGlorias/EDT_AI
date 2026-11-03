package io.github.hansglorias.edtai.core.llm;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.github.hansglorias.edtai.core.chat.TokenUsage;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Id;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Status;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Tokens;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Provenance;

/** Одно terminal-событие; mutable stream parser сюда не передаётся. */
final class RequestDiagnostics {
    private final Consumer<DiagnosticEvent> sink;
    private final RequestContext context;
    private final Id requestId = Id.random();
    private final long started = System.nanoTime();
    private final AtomicBoolean finished = new AtomicBoolean();
    private Long requestBytes;
    private int estimatedInputChars;
    private volatile CountedInputStream response;

    RequestDiagnostics(Consumer<DiagnosticEvent> sink, RequestContext context) {
        this.sink = sink;
        this.context = context;
    }

    void prepared(PreparedRequest request) {
        requestBytes = (long) request.utf8Bytes();
        estimatedInputChars = request.estimatedInputChars();
    }

    InputStream response(InputStream input) {
        CountedInputStream counted = new CountedInputStream(input);
        response = counted;
        return counted;
    }

    void finish(Status status, ModelReply reply, boolean preparationFailed) {
        if (!finished.compareAndSet(false, true)) return;
        TokenUsage usage = null;
        if (reply != null) {
            TokenUsage estimate = AgentLoop.estimate(estimatedInputChars, reply);
            usage = reply.usage() == null ? estimate : reply.usage().fillMissing(estimate);
        }
        CountedInputStream input = response;
        DiagnosticEvent event = new DiagnosticEvent(context.runId(), requestId, context.type(), status,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), requestBytes,
                preparationFailed ? null : Long.valueOf(input == null ? 0 : input.count),
                reply == null ? 0 : reply.calls().size(), context.retryOrdinal(),
                tokens(usage == null ? 0 : usage.input(), usage == null ? TokenUsage.Source.UNKNOWN : usage.inputSource()),
                tokens(usage == null ? 0 : usage.output(), usage == null ? TokenUsage.Source.UNKNOWN : usage.outputSource()));
        try { sink.accept(event); } catch (Throwable ignored) { /* Диагностика не влияет на запрос. */ }
    }

    private static Tokens tokens(long value, TokenUsage.Source source) {
        return switch (source) {
            case SERVER -> new Tokens(value, Provenance.SERVER);
            case ESTIMATE -> new Tokens(value, Provenance.ESTIMATE);
            default -> Tokens.unknown();
        };
    }

    private static final class CountedInputStream extends FilterInputStream {
        private volatile long count;
        CountedInputStream(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) count++;
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = in.read(buffer, offset, length);
            if (read > 0) count += read;
            return read;
        }
    }
}
