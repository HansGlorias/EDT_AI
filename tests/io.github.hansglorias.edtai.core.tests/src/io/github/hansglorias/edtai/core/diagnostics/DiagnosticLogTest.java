package io.github.hansglorias.edtai.core.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Id;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Provenance;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.RequestType;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Status;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Tokens;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticLog.State;
import io.github.hansglorias.edtai.core.json.Json;

class DiagnosticLogTest {

    @TempDir
    Path temp;

    @Test
    void defaultIsOffAndDoesNotCreateFiles() throws Exception {
        try (DiagnosticLog log = new DiagnosticLog()) {
            assertEquals(State.DISABLED, log.state());
            assertEquals(State.DISABLED, log.write(event(0)));
        }
        try (var paths = Files.list(temp)) {
            assertEquals(0, paths.count());
        }
    }

    @Test
    void schemaHasOnlyFixedFieldsAndTypesAndRandomIds() throws Exception {
        Set<Class<?>> allowed = Set.of(Id.class, RequestType.class, Status.class, long.class, Tokens.class);
        for (var component : DiagnosticEvent.class.getRecordComponents()) {
            assertTrue(allowed.contains(component.getType()), component.getName());
        }
        for (var constructor : Id.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
        assertEquals(Set.of(Long.class, Provenance.class), Set.of(
                Tokens.class.getRecordComponents()[0].getType(), Tokens.class.getRecordComponents()[1].getType()));
        DiagnosticEvent event = event(19);
        assertEquals(4, UUID.fromString(event.runId().toString()).version());
        assertEquals(4, UUID.fromString(event.requestId().toString()).version());
        assertNotEquals(event.runId().toString(), event.requestId().toString());
        Path file = temp.resolve("журнал/metrics.jsonl");
        try (DiagnosticLog log = DiagnosticLog.open(file)) {
            assertEquals(State.AVAILABLE, log.write(event));
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.endsWith("\n"));
        assertFalse(text.contains("\r"));
        assertEquals(1, text.lines().count());
        Map<String, Object> row = Json.parseObject(text);
        assertEquals(Set.of("version", "run_id", "request_id", "request_type", "status", "duration_ms",
                "request_bytes", "response_bytes", "tool_calls", "retries", "input_tokens", "output_tokens"), row.keySet());
        assertEquals(1L, row.get("version"));
        assertEquals(event.runId().toString(), row.get("run_id"));
        assertEquals(event.requestId().toString(), row.get("request_id"));
        assertEquals("answer", row.get("request_type"));
        assertEquals("success", row.get("status"));
        assertEquals(19L, row.get("duration_ms"));
        assertEquals(20L, row.get("request_bytes"));
        assertEquals(21L, row.get("response_bytes"));
        assertEquals(2L, row.get("tool_calls"));
        assertEquals(1L, row.get("retries"));
        assertEquals(Json.object("value", 0L, "provenance", "server"), row.get("input_tokens"));
        assertEquals(Json.object("value", null, "provenance", "unknown"), row.get("output_tokens"));
    }

    @Test
    void enumsAndTokenProvenanceRoundTripWithoutConflatingUnknownAndZero() {
        for (RequestType type : RequestType.values()) {
            for (Status status : Status.values()) {
                for (Provenance provenance : Provenance.values()) {
                    Tokens tokens = new Tokens(provenance == Provenance.UNKNOWN ? null : 0L, provenance);
                    var event = new DiagnosticEvent(Id.random(), Id.random(), type, status,
                            Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, tokens, tokens);
                    var row = Json.parseObject(event.json());
                    assertEquals(type.name().toLowerCase(java.util.Locale.ROOT), row.get("request_type"));
                    assertEquals(status.name().toLowerCase(java.util.Locale.ROOT), row.get("status"));
                    assertEquals(provenance.name().toLowerCase(java.util.Locale.ROOT),
                            Json.at(row, "input_tokens", "provenance"));
                    assertEquals(tokens.value(), Json.at(row, "output_tokens", "value"));
                }
            }
        }
    }

    @Test
    void invalidMetricsAndMissingFieldsCannotEnterSchema() {
        for (Provenance provenance : List.of(Provenance.SERVER, Provenance.ESTIMATE)) {
            assertThrows(IllegalArgumentException.class, () -> new Tokens(null, provenance));
            assertThrows(IllegalArgumentException.class, () -> new Tokens(-1L, provenance));
        }
        assertThrows(IllegalArgumentException.class, () -> new Tokens(0L, Provenance.UNKNOWN));
        assertThrows(NullPointerException.class, () -> new Tokens(0L, null));
        for (int index = 0; index < 5; index++) {
            long[] values = { 0, 0, 0, 0, 0 };
            values[index] = -1;
            assertThrows(IllegalArgumentException.class, () -> new DiagnosticEvent(Id.random(), Id.random(),
                    RequestType.ANSWER, Status.SUCCESS, values[0], values[1], values[2], values[3], values[4],
                    Tokens.unknown(), Tokens.unknown()));
        }
        var valid = event(0);
        Object[] fields = { valid.runId(), valid.requestId(), valid.requestType(), valid.status(),
                valid.inputTokens(), valid.outputTokens() };
        for (int index = 0; index < fields.length; index++) {
            Object[] missing = fields.clone();
            missing[index] = null;
            assertThrows(NullPointerException.class, () -> new DiagnosticEvent((Id) missing[0], (Id) missing[1],
                    (RequestType) missing[2], (Status) missing[3], 0, 0, 0, 0, 0, (Tokens) missing[4], (Tokens) missing[5]));
        }
    }

    @Test
    void rotationBoundsEveryFileAndRetainsNewestEventsAcrossReopen() throws Exception {
        Path file = temp.resolve("metrics.jsonl");
        Path unrelated = temp.resolve("other.jsonl");
        Files.writeString(unrelated, "не журнал");
        long last = -1;
        for (int run = 0; run < 4; run++) {
            try (DiagnosticLog log = smallLog(file)) {
                for (int i = 0; i < 40; i++) {
                    last++;
                    assertEquals(State.AVAILABLE, log.write(event(last)));
                    assertBounded(file, 2048, 3);
                }
            }
        }
        List<Long> retained = new ArrayList<>();
        for (int slot = 2; slot >= 0; slot--) {
            Path path = slot == 0 ? file : file.resolveSibling("metrics.jsonl." + slot);
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                retained.add((Long) Json.parseObject(line).get("duration_ms"));
            }
        }
        assertTrue(retained.size() < 160);
        assertTrue(retained.size() > 3);
        assertEquals(last, retained.get(retained.size() - 1));
        for (int i = 1; i < retained.size(); i++) {
            assertEquals(retained.get(i - 1) + 1, retained.get(i));
        }
        assertEquals("не журнал", Files.readString(unrelated));
    }

    @Test
    void publicLimitsAreFixedAndProductionRotationIsBounded() throws Exception {
        assertEquals(1024 * 1024, DiagnosticLog.MAX_FILE_BYTES);
        assertEquals(3, DiagnosticLog.MAX_FILES);
        Path file = temp.resolve("metrics.jsonl");
        try (DiagnosticLog log = DiagnosticLog.open(file)) {
            var event = event(Long.MAX_VALUE - 2);
            for (int i = 0; i < 7500; i++) {
                assertEquals(State.AVAILABLE, log.write(event));
            }
        }
        assertBounded(file, DiagnosticLog.MAX_FILE_BYTES, DiagnosticLog.MAX_FILES);
        assertTrue(Files.exists(temp.resolve("metrics.jsonl.2")));
    }

    @Test
    void concurrentCallsProduceWholeJsonLinesWithoutLoss() throws Exception {
        Path file = temp.resolve("metrics.jsonl");
        Set<String> expected = new HashSet<>();
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try (DiagnosticLog log = DiagnosticLog.open(file)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                List<DiagnosticEvent> events = new ArrayList<>();
                for (int i = 0; i < 100; i++) {
                    DiagnosticEvent event = event(i);
                    expected.add(event.requestId().toString());
                    events.add(event);
                }
                futures.add(executor.submit(() -> {
                    start.await();
                    for (DiagnosticEvent event : events) {
                        assertEquals(State.AVAILABLE, log.write(event));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(800, lines.size());
        Set<String> actual = new HashSet<>();
        for (String line : lines) {
            assertTrue(actual.add(Json.stringAt(Json.parseObject(line), "request_id")));
        }
        assertEquals(expected, actual);
    }

    @Test
    void ioFailureAtOpenIsUnavailableAndNeverRetries() throws Exception {
        Path parent = temp.resolve("parent");
        Files.writeString(parent, "занято");
        DiagnosticLog log = DiagnosticLog.open(parent.resolve("metrics.jsonl"));
        assertEquals(State.UNAVAILABLE, log.state());
        Files.delete(parent);
        for (int i = 0; i < 5; i++) {
            assertEquals(State.UNAVAILABLE, log.write(event(i)));
        }
        log.close();
        log.close();
        assertEquals(State.UNAVAILABLE, log.state());
        assertFalse(Files.exists(parent));
    }

    @Test
    void writeFlushAndCloseFailuresDoNotEscapeOrRetryOrLeakErrorText() {
        for (String failure : List.of("write", "flush", "close", "security")) {
            AtomicInteger opened = new AtomicInteger();
            AtomicInteger writes = new AtomicInteger();
            AtomicInteger closes = new AtomicInteger();
            DiagnosticLog log = new DiagnosticLog(temp.resolve(failure), 2048, 3, path -> {
                opened.incrementAndGet();
                return new OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        writes.incrementAndGet();
                        if (failure.equals("write")) {
                            throw new IOException("секрет и путь из исключения");
                        }
                        if (failure.equals("security")) {
                            throw new SecurityException("секрет и путь из исключения");
                        }
                    }

                    @Override
                    public void flush() throws IOException {
                        if (failure.equals("flush")) {
                            throw new IOException("секрет и путь из исключения");
                        }
                    }

                    @Override
                    public void close() throws IOException {
                        closes.incrementAndGet();
                        if (failure.equals("close")) {
                            throw new IOException("секрет и путь из исключения");
                        }
                    }
                };
            });
            log.write(event(0));
            log.close();
            int attempted = writes.get();
            for (int i = 0; i < 5; i++) {
                assertEquals(State.UNAVAILABLE, log.write(event(i)));
                log.close();
            }
            assertEquals(State.UNAVAILABLE, log.state());
            assertEquals(attempted, writes.get());
            assertEquals(1, opened.get());
            assertEquals(1, closes.get());
            assertFalse(log.toString().contains("секрет"));
        }
    }

    @Test
    void rotationFailureStopsFurtherAttempts() throws Exception {
        Path file = temp.resolve("metrics.jsonl");
        AtomicInteger opened = new AtomicInteger();
        DiagnosticLog log = new DiagnosticLog(file, 2048, 3, path -> {
            if (opened.incrementAndGet() > 1) {
                throw new IOException("недоступно");
            }
            return Files.newOutputStream(path);
        });
        for (int i = 0; i < 20 && log.state() == State.AVAILABLE; i++) {
            log.write(event(i));
        }
        assertEquals(State.UNAVAILABLE, log.state());
        log.write(event(99));
        log.close();
        assertEquals(2, opened.get());
        assertBounded(file, 2048, 3);
    }

    @Test
    void malformedExistingStorageIsUnavailableWithoutDeletingIt() throws Exception {
        Path directory = temp.resolve("directory.jsonl");
        Files.createDirectory(directory);
        try (DiagnosticLog log = DiagnosticLog.open(directory)) {
            assertEquals(State.UNAVAILABLE, log.state());
        }
        assertTrue(Files.isDirectory(directory));
        Path oversized = temp.resolve("large.jsonl");
        Files.write(oversized, new byte[DiagnosticLog.MAX_FILE_BYTES + 1]);
        try (DiagnosticLog log = DiagnosticLog.open(oversized)) {
            assertEquals(State.UNAVAILABLE, log.state());
        }
        assertEquals(DiagnosticLog.MAX_FILE_BYTES + 1, Files.size(oversized));
    }

    @Test
    void closeIsIdempotentAndDoesNotWriteAgainAndReleasesTheFile() throws Exception {
        Path file = temp.resolve("metrics.jsonl");
        DiagnosticLog log = DiagnosticLog.open(file);
        log.write(event(0));
        log.close();
        long size = Files.size(file);
        log.close();
        assertEquals(State.CLOSED, log.write(event(1)));
        assertEquals(size, Files.size(file));
        assertEquals(State.CLOSED, log.state());
        Files.delete(file); // Проверяет освобождение дескриптора и на Windows.
        assertFalse(Files.exists(file));
        assertNull(Tokens.unknown().value());
    }

    private DiagnosticLog smallLog(Path file) {
        return new DiagnosticLog(file, 2048, 3, path -> Files.newOutputStream(path));
    }

    private void assertBounded(Path file, int maxBytes, int maxFiles) throws IOException {
        try (var paths = Files.list(temp)) {
            List<Path> owned = paths.filter(path -> path.getFileName().toString()
                    .startsWith(file.getFileName().toString())).toList();
            assertTrue(owned.size() <= maxFiles);
            for (Path path : owned) {
                assertTrue(Files.size(path) <= maxBytes);
            }
        }
    }

    private static DiagnosticEvent event(long duration) {
        return new DiagnosticEvent(Id.random(), Id.random(), RequestType.ANSWER, Status.SUCCESS,
                duration, duration + 1, duration + 2, 2, 1, new Tokens(0L, Provenance.SERVER), Tokens.unknown());
    }
}
