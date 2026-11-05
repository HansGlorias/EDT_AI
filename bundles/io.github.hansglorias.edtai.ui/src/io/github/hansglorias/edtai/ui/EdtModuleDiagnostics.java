package io.github.hansglorias.edtai.ui;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import io.github.hansglorias.edtai.core.tools.ModuleDiagnostics;
import io.github.hansglorias.edtai.core.tools.ModuleDiagnostics.Diagnostic;
import io.github.hansglorias.edtai.core.tools.ModuleDiagnostics.Severity;
import io.github.hansglorias.edtai.core.tools.ModuleDiagnostics.Status;

/** Optional read-only bridge: no EDT dependency and no validation/build request. */
final class EdtModuleDiagnostics {
    private static final String PREFIX = "com._1c.g5.v8.dt.validation.marker.";
    private static final EdtModuleDiagnostics INSTANCE = new EdtModuleDiagnostics(3000);
    private final AtomicBoolean busy = new AtomicBoolean();
    private final long timeoutMillis;

    EdtModuleDiagnostics(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    static ModuleDiagnostics read(IFile file) {
        return INSTANCE.bounded(() -> {
            var bundle = FrameworkUtil.getBundle(EdtModuleDiagnostics.class);
            return fromService(bundle == null ? null : bundle.getBundleContext(), file);
        });
    }

    /** A timed-out native call retains the only slot until it really returns. No queue. */
    ModuleDiagnostics bounded(Callable<ModuleDiagnostics> read) {
        if (Thread.currentThread().isInterrupted()) return status(Status.UNAVAILABLE);
        if (!busy.compareAndSet(false, true)) return status(Status.TIMEOUT);
        FutureTask<ModuleDiagnostics> task = new FutureTask<>(() -> {
            try {
                return read.call();
            } finally {
                busy.set(false);
            }
        });
        Thread worker = new Thread(task, "edtai-module-diagnostics");
        worker.setDaemon(true);
        worker.start();
        try {
            return task.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            return status(Status.UNAVAILABLE);
        } catch (TimeoutException e) {
            worker.interrupt();
            return status(Status.TIMEOUT);
        } catch (ExecutionException e) {
            rethrowFatal(e.getCause());
            return ModuleDiagnostics.unavailable();
        }
    }

    private static void rethrowFatal(Throwable failure) {
        while (failure instanceof InvocationTargetException invocation) {
            failure = invocation.getCause();
        }
        if (failure instanceof VirtualMachineError error) throw error;
        if (failure instanceof ThreadDeath death) throw death;
    }

    static ModuleDiagnostics fromService(BundleContext context, IFile file) throws Exception {
        if (context == null) return ModuleDiagnostics.unavailable();
        // We deliberately do not import the optional EDT package. Include references
        // not assignable to this bundle and load the public API from the provider.
        ServiceReference<?>[] references = context.getAllServiceReferences(PREFIX + "IMarkerManager", null);
        if (references == null || references.length == 0) return ModuleDiagnostics.unavailable();
        ServiceReference<?> reference = references[0];
        Object service = context.getService(reference);
        if (service == null) return ModuleDiagnostics.unavailable();
        try {
            return reflect(service, reference.getBundle()::loadClass, file.getProject(), file.getFullPath().toString());
        } finally {
            context.ungetService(reference);
        }
    }

    @FunctionalInterface
    interface Classes {
        Class<?> load(String name) throws ClassNotFoundException;
    }

    static ModuleDiagnostics reflect(Object service, Classes classes, IProject project, String key) throws Exception {
        Class<?> manager = classes.load(PREFIX + "IMarkerManager");
        Class<?> marker = classes.load(PREFIX + "Marker");
        Class<?> extra = classes.load(PREFIX + "StandardExtraInfo");
        Object markers = manager.getMethod("getMarkers", IProject.class, Object.class).invoke(service, project, key);
        Object textLine = extra.getField("TEXT_LINE").get(null);
        var getLine = extra.getMethod("get", marker);
        var getMessage = marker.getMethod("getMessage");
        var getCheck = marker.getMethod("getCheckId");
        var getSeverity = marker.getMethod("getSeverity");
        int count = markers == null ? 0 : Array.getLength(markers);
        List<Diagnostic> entries = new ArrayList<>();
        for (int i = 0; i < Math.min(count, 20); i++) {
            Object item = Array.get(markers, i);
            Object line = getLine.invoke(textLine, item);
            Object severity = getSeverity.invoke(item);
            Severity level = switch (severity instanceof Enum<?> value ? value.name() : "") {
                case "ERRORS", "BLOCKER", "CRITICAL" -> Severity.ERROR;
                case "MAJOR", "MINOR" -> Severity.WARNING;
                case "TRIVIAL", "NONE" -> Severity.INFO;
                default -> Severity.UNKNOWN;
            };
            entries.add(new Diagnostic(line instanceof Integer number ? Math.max(0, number) : 0, level,
                    (String) getCheck.invoke(item), (String) getMessage.invoke(item)));
        }
        // Storage flush does not prove validation completed, even for an empty array.
        return new ModuleDiagnostics(Status.SNAPSHOT, entries, Instant.now(), count > 20);
    }

    static ModuleDiagnostics status(Status status) {
        return new ModuleDiagnostics(status, List.of(), Instant.now());
    }
}
