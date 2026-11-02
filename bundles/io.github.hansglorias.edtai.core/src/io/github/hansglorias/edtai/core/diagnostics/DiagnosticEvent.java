package io.github.hansglorias.edtai.core.diagnostics;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import io.github.hansglorias.edtai.core.json.Json;

/** Завершение одного запроса: только закрытая схема без содержимого запроса и ответа. */
public record DiagnosticEvent(Id runId, Id requestId, RequestType requestType, Status status,
        long durationMillis, long requestBytes, long responseBytes, long toolCalls, long retries,
        Tokens inputTokens, Tokens outputTokens) {

    public enum RequestType { ANSWER, TITLE, SUMMARY, RETRY }
    public enum Status { SUCCESS, CANCELLED, FAILED, TIMEOUT, REJECTED }
    public enum Provenance { SERVER, ESTIMATE, UNKNOWN }

    /** Непрозрачный случайный ID; нельзя передать ID сервера, чата или проекта. */
    public static final class Id {
        private final UUID value = UUID.randomUUID();

        private Id() {
        }

        public static Id random() {
            return new Id();
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    /** Неизвестное значение — null, известный ноль отличается от отсутствия usage. */
    public record Tokens(Long value, Provenance provenance) {
        public Tokens {
            Objects.requireNonNull(provenance);
            if (provenance == Provenance.UNKNOWN ? value != null : value == null || value < 0) {
                throw new IllegalArgumentException("Некорректный счётчик токенов");
            }
        }

        public static Tokens unknown() {
            return new Tokens(null, Provenance.UNKNOWN);
        }

        private Object json() {
            return Json.object("value", value, "provenance", name(provenance));
        }
    }

    public DiagnosticEvent {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(requestType);
        Objects.requireNonNull(status);
        Objects.requireNonNull(inputTokens);
        Objects.requireNonNull(outputTokens);
        if (durationMillis < 0 || requestBytes < 0 || responseBytes < 0 || toolCalls < 0 || retries < 0) {
            throw new IllegalArgumentException("Метрики не могут быть отрицательными");
        }
    }

    String json() {
        return Json.write(Json.object("version", 1, "run_id", runId.toString(), "request_id", requestId.toString(),
                "request_type", name(requestType), "status", name(status), "duration_ms", durationMillis,
                "request_bytes", requestBytes, "response_bytes", responseBytes, "tool_calls", toolCalls,
                "retries", retries, "input_tokens", inputTokens.json(), "output_tokens", outputTokens.json()));
    }

    private static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
