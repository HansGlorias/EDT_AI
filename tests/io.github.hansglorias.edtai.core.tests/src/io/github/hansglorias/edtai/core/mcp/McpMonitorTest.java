package io.github.hansglorias.edtai.core.mcp;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import io.github.hansglorias.edtai.core.settings.McpServer;
import io.github.hansglorias.edtai.core.settings.McpTransport;
import io.github.hansglorias.edtai.core.tls.TrustSettings;

class McpMonitorTest {
    private static final TrustSettings TRUST = new TrustSettings(false, Optional.empty());
    private static final List<McpServer> SERVERS = List.of(new McpServer("test", McpTransport.HTTP, "http://localhost:1/mcp", true));
    static class FakeScheduler implements McpMonitor.Scheduler {
        static class Task implements McpMonitor.Ticket {
            final Duration delay; final Runnable action; boolean cancelled;
            Task(Duration delay, Runnable action) { this.delay = delay; this.action = action; }
            @Override public void cancel() { cancelled = true; }
        }
        final List<Task> tasks = new ArrayList<>();
        boolean closed;
        @Override public McpMonitor.Ticket schedule(Duration delay, Runnable action) {
            Task task = new Task(delay, action); tasks.add(task); return task;
        }
        Task take() { return tasks.remove(0); }
        void next() { Task task = take(); if (!task.cancelled) task.action.run(); }
        @Override public void close() { closed = true; }
    }

    @Test void firstOpenAndFiveMinuteTimerHaveOneFlightAndCancelOnClose() {
        FakeScheduler scheduler = new FakeScheduler(); AtomicInteger calls = new AtomicInteger();
        McpMonitor monitor = new McpMonitor(scheduler, (servers, trust) -> { calls.incrementAndGet(); return true; }, () -> { });
        monitor.configure(SERVERS, TRUST, 5);
        monitor.configure(SERVERS, TRUST, 5);
        assertEquals(1, scheduler.tasks.size());
        assertEquals(Duration.ZERO, scheduler.tasks.get(0).delay);
        scheduler.next();
        assertEquals(1, calls.get());
        assertEquals(Duration.ofMinutes(5), scheduler.tasks.get(0).delay);
        FakeScheduler.Task late = scheduler.take();
        monitor.close(); monitor.close();
        late.action.run();
        assertEquals(1, calls.get()); assertTrue(scheduler.closed);
    }

    @Test void busyServerRetriesLaterAndDisabledPeriodStillChecksOnce() {
        FakeScheduler scheduler = new FakeScheduler(); AtomicInteger calls = new AtomicInteger();
        try (McpMonitor monitor = new McpMonitor(scheduler, (servers, trust) -> calls.incrementAndGet() > 1, () -> { })) {
            monitor.configure(SERVERS, TRUST, 0);
            scheduler.next();
            assertEquals(Duration.ofSeconds(30), scheduler.tasks.get(0).delay);
            scheduler.next();
            assertEquals(2, calls.get()); assertTrue(scheduler.tasks.isEmpty());
        }
    }

    @Test void reconfigureDuringFlightCoalescesAndRemovalCancelsPeriod() {
        FakeScheduler scheduler = new FakeScheduler(); AtomicInteger invalidated = new AtomicInteger();
        McpMonitor[] holder = new McpMonitor[1];
        holder[0] = new McpMonitor(scheduler, (servers, trust) -> {
            holder[0].configure(List.of(), TRUST, 10);
            holder[0].configure(List.of(), TRUST, 10);
            return true;
        }, invalidated::incrementAndGet);
        try (McpMonitor monitor = holder[0]) {
            monitor.configure(SERVERS, TRUST, 3);
            scheduler.next();
            assertEquals(1, scheduler.tasks.size()); assertEquals(1, invalidated.get());
            scheduler.next();
            assertTrue(scheduler.tasks.isEmpty());
        }
    }
}
