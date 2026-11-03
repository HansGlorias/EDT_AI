package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Id;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.RequestType;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Status;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent.Tokens;

class UiDiagnosticLogTest {
    @TempDir Path temporary;

    private UiDiagnosticLog controller(Path path) {
        return new UiDiagnosticLog(path, () -> { });
    }

    private DiagnosticEvent event() {
        return new DiagnosticEvent(Id.random(), Id.random(), RequestType.ANSWER, Status.SUCCESS,
                1L, 2L, 3L, 0L, 0L, Tokens.unknown(), Tokens.unknown());
    }

    private void settle(UiDiagnosticLog log, boolean enabled) throws Exception {
        log.setEnabled(enabled).get(5, TimeUnit.SECONDS);
    }

    private void awaitLines(Path path, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Files.exists(path) && Files.readAllLines(path).size() >= count) {
                return;
            }
            Thread.sleep(10);
        }
        fail("Expected diagnostic events");
    }

    @Test void disabledAndLateCallbacksDoNotCreateFiles() throws Exception {
        Path file = temporary.resolve("off/requests.jsonl");
        var log = controller(file);
        assertFalse(log.isEnabled());
        log.accept(event());
        settle(log, false);
        log.close();
        log.accept(event());
        settle(log, true);
        assertFalse(log.isEnabled(), "закрытый владелец не включается повторно");
        assertFalse(Files.exists(file.getParent()));
    }

    @Test void toggleDiscardsQueuedEventsAndReopenRotatesClosedWriter() throws Exception {
        Path file = temporary.resolve("requests.jsonl");
        var first = controller(file);
        settle(first, true);
        assertTrue(first.isEnabled());
        // Foreground and background callbacks share this single owner.
        Thread background = new Thread(() -> first.accept(event()));
        background.start();
        first.accept(event());
        background.join();
        awaitLines(file, 2);
        settle(first, false);
        assertFalse(first.isEnabled());
        long size = Files.size(file);
        first.accept(event());
        settle(first, false);
        assertEquals(size, Files.size(file));
        settle(first, true);
        first.accept(event());
        awaitLines(file, 1);
        for (int i = 0; i < 200; i++) {
            first.accept(event());
        }
        first.close(); // Must not lose close when the event queue is full.
        var reopened = controller(file);
        try {
            settle(reopened, true);
            assertTrue(reopened.description().contains("включён"));
            first.accept(event());
            reopened.accept(event());
            awaitLines(file, 1);
            settle(reopened, false);
            assertEquals(1, Files.readAllLines(file).size());
        } finally {
            reopened.close();
        }
    }

    @Test void anotherViewCannotRotateLiveWriter() throws Exception {
        Path file = temporary.resolve("requests.jsonl");
        var first = controller(file);
        var second = controller(file);
        try {
            settle(first, true);
            settle(second, true);
            assertTrue(second.description().contains("недоступен"));
            first.accept(event());
            awaitLines(file, 1);
            assertFalse(Files.exists(temporary.resolve("requests.jsonl.1")));
            settle(first, false);
            settle(second, false);
            settle(second, true);
            assertTrue(second.description().contains("включён"));
        } finally {
            first.close();
            second.close();
        }
    }

    @Test void throwingObserverCannotKillSharedWorker() throws Exception {
        Path file = temporary.resolve("requests.jsonl");
        var log = new UiDiagnosticLog(file, () -> { throw new IllegalStateException("observer"); });
        try {
            settle(log, true);
            assertDoesNotThrow(() -> log.accept(null));
            log.accept(event());
            awaitLines(file, 1);
            settle(log, false);
            settle(log, true);
            log.accept(event());
            awaitLines(file, 1);
        } finally {
            log.close();
            settle(log, false);
        }
        var next = controller(temporary.resolve("next.jsonl"));
        try {
            settle(next, true);
            next.accept(event());
            awaitLines(temporary.resolve("next.jsonl"), 1);
        } finally {
            next.close();
        }
    }

    @Test void ioErrorIsUnavailableAndDoesNotBreakCallbacks() throws Exception {
        Path blocked = temporary.resolve("blocked");
        Files.writeString(blocked, "file");
        var log = controller(blocked.resolve("requests.jsonl"));
        try {
            settle(log, true);
            assertTrue(log.description().contains("недоступен"));
            assertDoesNotThrow(() -> log.accept(event()));
            settle(log, false);
        } finally {
            log.close();
        }
    }
}
