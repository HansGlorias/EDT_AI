package io.github.hansglorias.edtai.ui;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.framework.FrameworkUtil;

import io.github.hansglorias.edtai.core.diagnostics.DiagnosticEvent;
import io.github.hansglorias.edtai.core.diagnostics.DiagnosticLog;
import io.github.hansglorias.edtai.core.settings.SettingsStore;

/**
 * One log per view, shared by all its chats. The JVM-wide daemon serializes I/O,
 * including close/reopen. Only events queue (128); control changes coalesce per
 * owner and cannot be lost under event pressure. No SWT or I/O under LOCK.
 */
final class UiDiagnosticLog implements Consumer<DiagnosticEvent>, AutoCloseable {
    static final String ENABLED = "localDiagnosticsEnabled";
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Pending> EVENTS = new ArrayDeque<>();
    private static final Set<UiDiagnosticLog> DIRTY = new LinkedHashSet<>();
    // Accessed only by the I/O daemon: another workbench window must not rotate a live file.
    private static final Map<Path, UiDiagnosticLog> OWNERS = new HashMap<>();
    static {
        Thread worker = new Thread(UiDiagnosticLog::work, "EDT AI: локальная диагностика");
        worker.setDaemon(true);
        worker.start();
    }

    private record Pending(UiDiagnosticLog owner, long generation, DiagnosticEvent event) { }
    private final Path path;
    private final Runnable changed;
    private volatile DiagnosticLog.State state = DiagnosticLog.State.DISABLED;
    private volatile long dropped;
    private DiagnosticLog log; // Only the I/O daemon touches the writer.
    private boolean enabled;
    private boolean closed;
    private long generation;
    private CompletableFuture<Void> transition = CompletableFuture.completedFuture(null);

    UiDiagnosticLog(Path path, Runnable changed) {
        this.path = path.toAbsolutePath().normalize();
        this.changed = changed;
    }

    static Path location() {
        return Platform.getStateLocation(FrameworkUtil.getBundle(UiDiagnosticLog.class))
                .toFile().toPath().resolve("diagnostics").resolve("requests.jsonl");
    }

    static boolean preference() {
        return InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER).getBoolean(ENABLED, false);
    }

    boolean isEnabled() {
        synchronized (LOCK) {
            return enabled && !closed;
        }
    }

    CompletableFuture<Void> setEnabled(boolean value) {
        synchronized (LOCK) {
            if (closed || enabled == value) {
                return transition;
            }
            enabled = value;
            generation++;
            EVENTS.removeIf(event -> event.owner == this);
            if (transition.isDone()) {
                transition = new CompletableFuture<>();
            }
            DIRTY.add(this);
            LOCK.notifyAll();
            return transition;
        }
    }

    @Override
    public void accept(DiagnosticEvent event) {
        if (event == null) {
            return;
        }
        boolean notify = false;
        synchronized (LOCK) {
            if (!enabled || closed) {
                return;
            }
            if (EVENTS.size() == 128) {
                notify = dropped++ == 0;
            } else {
                EVENTS.addLast(new Pending(this, generation, event));
                LOCK.notifyAll();
            }
        }
        if (notify) {
            notifyChanged();
        }
    }

    private static void work() {
        while (true) {
            UiDiagnosticLog control = null;
            Pending event = null;
            boolean enable = false;
            long version = 0;
            CompletableFuture<Void> done = null;
            synchronized (LOCK) {
                while (DIRTY.isEmpty() && EVENTS.isEmpty()) {
                    try {
                        LOCK.wait();
                    } catch (InterruptedException ignored) {
                        // The daemon follows the JVM lifetime, not a view's cancellation.
                    }
                }
                if (!DIRTY.isEmpty()) {
                    control = DIRTY.iterator().next();
                    DIRTY.remove(control);
                    enable = control.enabled && !control.closed;
                    version = control.generation;
                    done = control.transition;
                    // A newer change gets its own completion while this I/O is in flight.
                    control.transition = new CompletableFuture<>();
                } else {
                    event = EVENTS.removeFirst();
                    if (event.owner.closed || !event.owner.enabled || event.generation != event.owner.generation) {
                        event = null;
                    }
                }
            }
            if (control != null) {
                try {
                    control.configure(enable);
                } catch (RuntimeException ignored) {
                    control.unavailable();
                }
                synchronized (LOCK) {
                    if (version == control.generation && !DIRTY.contains(control)) {
                        control.transition.complete(null);
                    }
                }
                done.complete(null);
                control.notifyChanged();
            } else if (event != null) {
                UiDiagnosticLog owner = event.owner;
                if (owner.log != null) {
                    DiagnosticLog.State previous = owner.state;
                    try {
                        owner.state = owner.log.write(event.event);
                    } catch (RuntimeException ignored) {
                        owner.unavailable();
                    }
                    if (owner.state != previous) {
                        owner.notifyChanged();
                    }
                }
            }
        }
    }

    private void notifyChanged() {
        try {
            changed.run();
        } catch (RuntimeException ignored) {
            // A disposed SWT display must not terminate the shared I/O daemon.
        }
    }

    private void unavailable() {
        if (log != null) {
            try {
                log.close();
            } catch (RuntimeException ignored) {
                // Never disclose exception text or paths in an event.
            }
            log = null;
        }
        OWNERS.remove(path, this);
        state = DiagnosticLog.State.UNAVAILABLE;
    }

    private void configure(boolean enable) {
        if (log != null) {
            log.close();
            log = null;
            OWNERS.remove(path, this);
        }
        state = DiagnosticLog.State.DISABLED;
        if (enable) {
            if (OWNERS.containsKey(path)) {
                state = DiagnosticLog.State.UNAVAILABLE;
            } else {
                log = DiagnosticLog.open(path);
                state = log.state();
                if (state == DiagnosticLog.State.AVAILABLE) {
                    OWNERS.put(path, this);
                }
            }
        }
    }

    String description() {
        String label = switch (state) {
            case DISABLED, CLOSED -> "выключен";
            case AVAILABLE -> "включён";
            case UNAVAILABLE -> "недоступен";
        };
        return "Локальный журнал: " + label + "; пропущено событий: " + dropped
                + "\n" + path + "\nТолько метрики; без текстов и автоматической отправки.";
    }

    @Override
    public void close() {
        synchronized (LOCK) {
            if (closed) {
                return;
            }
            setEnabled(false);
            closed = true;
        }
    }
}
