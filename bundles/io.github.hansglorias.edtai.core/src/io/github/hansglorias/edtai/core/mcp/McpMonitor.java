package io.github.hansglorias.edtai.core.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.github.hansglorias.edtai.core.settings.McpServer;
import io.github.hansglorias.edtai.core.tls.TrustSettings;

/** Один монитор на вид, независимо от числа чатов. Сеть выполняется только в его worker. */
public final class McpMonitor implements AutoCloseable {
    public interface Ticket { void cancel(); }
    public interface Scheduler extends AutoCloseable {
        Ticket schedule(Duration delay, Runnable task);
        @Override void close();
    }
    @FunctionalInterface
    interface Probe { boolean check(List<McpServer> servers, TrustSettings trust); }
    private record Configuration(List<McpServer> servers, TrustSettings trust, int minutes) { }

    private final Scheduler scheduler;
    private final Probe probe;
    private final Runnable invalidate;
    private Configuration configuration;
    private Ticket ticket;
    private boolean running;
    private boolean closed;
    private long revision;

    public McpMonitor(McpHub hub) { this(executor(), hub::refreshAvailability, hub::invalidateConfiguration); }

    McpMonitor(Scheduler scheduler, Probe probe, Runnable invalidate) {
        this.scheduler = scheduler;
        this.probe = probe;
        this.invalidate = invalidate;
    }

    public synchronized void configure(List<McpServer> servers, TrustSettings trust, int minutes) {
        if (closed) return;
        Configuration next = new Configuration(List.copyOf(servers), Objects.requireNonNull(trust), minutes);
        if (next.equals(configuration)) return;
        boolean changedConnection = configuration != null
                && (!configuration.servers().equals(next.servers()) || !configuration.trust().equals(trust));
        configuration = next;
        revision++;
        if (ticket != null) ticket.cancel();
        if (changedConnection) invalidate.run();
        if (!running) enqueue(Duration.ZERO);
    }

    private void enqueue(Duration delay) {
        if (closed) return;
        long expected = revision;
        ticket = scheduler.schedule(delay, () -> run(expected));
    }

    private void run(long expected) {
        Configuration current;
        synchronized (this) {
            if (closed || expected != revision || running) return;
            running = true;
            ticket = null;
            current = configuration;
        }
        boolean complete = true;
        try {
            complete = probe.check(current.servers(), current.trust());
        } finally {
            synchronized (this) {
                running = false;
                if (!closed) {
                    if (expected != revision) enqueue(Duration.ZERO);
                    else if (!complete) enqueue(Duration.ofSeconds(30));
                    else if (current.minutes() > 0 && current.servers().stream().anyMatch(McpServer::enabled)) {
                        enqueue(Duration.ofMinutes(current.minutes()));
                    }
                }
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        revision++;
        if (ticket != null) ticket.cancel();
        invalidate.run();
        scheduler.close();
    }

    private static Scheduler executor() {
        ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "EDT AI: монитор MCP");
            thread.setDaemon(true);
            return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        return new Scheduler() {
            @Override public Ticket schedule(Duration delay, Runnable task) {
                var future = worker.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
                return () -> future.cancel(false);
            }
            @Override public void close() { worker.shutdownNow(); }
        };
    }
}
