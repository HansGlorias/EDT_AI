package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.tools.ModuleDiagnostics;
import io.github.hansglorias.edtai.core.tools.ModuleDiagnostics.Severity;
import io.github.hansglorias.edtai.core.tools.ModuleDiagnostics.Status;

class EdtModuleDiagnosticsTest {
    public enum Level { ERRORS, MAJOR, TRIVIAL }
    public static class Marker {
        public String getMessage() { return "problem"; }
        public String getCheckId() { return "test-check"; }
        public Level getSeverity() { return Level.ERRORS; }
    }
    public static class Extra {
        public static final Extra TEXT_LINE = new Extra();
        public Integer get(Marker marker) { return 7; }
    }
    public interface Manager {
        Marker[] getMarkers(IProject project, Object key);
    }

    private Class<?> load(String name) throws ClassNotFoundException {
        return switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "IMarkerManager" -> Manager.class;
            case "Marker" -> Marker.class;
            case "StandardExtraInfo" -> Extra.class;
            default -> throw new ClassNotFoundException();
        };
    }

    @Test void nativeReflectionUsesResourceKeyAndPublicApiAndBoundsRows() throws Exception {
        Manager manager = (project, key) -> {
            assertEquals("/example/src/Module.bsl", key);
            Marker[] rows = new Marker[25];
            java.util.Arrays.fill(rows, new Marker());
            return rows;
        };
        ModuleDiagnostics result = EdtModuleDiagnostics.reflect(manager, this::load, null, "/example/src/Module.bsl");
        assertEquals(Status.SNAPSHOT, result.status());
        assertEquals(20, result.entries().size());
        assertTrue(result.truncated());
        assertEquals(7, result.entries().get(0).line());
        assertEquals(Severity.ERROR, result.entries().get(0).severity());
    }

    @Test void emptyNativeStorageIsOnlySnapshotAndAbsentServiceIsUnavailable() throws Exception {
        assertEquals(Status.SNAPSHOT, EdtModuleDiagnostics.reflect((Manager) (project, key) -> new Marker[0],
                this::load, null, "/example/src/Module.bsl").status());
        assertEquals(Status.UNAVAILABLE, EdtModuleDiagnostics.fromService(null, null).status());
    }

    @Test void timeoutRetainsOnlySlotUntilNativeCallActuallyReturns() throws Exception {
        EdtModuleDiagnostics bridge = new EdtModuleDiagnostics(40);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try {
            assertEquals(Status.TIMEOUT, bridge.bounded(() -> {
                calls.incrementAndGet();
                try {
                    while (release.getCount() != 0) {
                        try { release.await(); } catch (InterruptedException ignored) { }
                    }
                    return ModuleDiagnostics.unavailable();
                } finally { exited.countDown(); }
            }).status());
            for (int i = 0; i < 30; i++) {
                assertEquals(Status.TIMEOUT, bridge.bounded(() -> {
                    calls.incrementAndGet();
                    return ModuleDiagnostics.unavailable();
                }).status());
            }
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
        }
        assertTrue(exited.await(2, TimeUnit.SECONDS));
    }

    @Test void nativeFailureIsSanitizedAndCancellationPreservesInterrupt() {
        EdtModuleDiagnostics bridge = new EdtModuleDiagnostics(500);
        assertEquals(Status.UNAVAILABLE, bridge.bounded(() -> {
            throw new IllegalStateException("private path must not escape");
        }).status());
        Thread.currentThread().interrupt();
        try {
            assertEquals(Status.UNAVAILABLE, bridge.bounded(ModuleDiagnostics::unavailable).status());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void fatalNativeFailureIsNotConvertedToUnavailable() {
        EdtModuleDiagnostics bridge = new EdtModuleDiagnostics(500);
        assertThrows(OutOfMemoryError.class, () -> bridge.bounded(() -> {
            throw new java.lang.reflect.InvocationTargetException(new OutOfMemoryError("test"));
        }));
    }

    @Test void serviceIsLoadedFromProviderAndReleasedAfterRead() throws Exception {
        AtomicInteger released = new AtomicInteger();
        Bundle provider = proxy(Bundle.class, (object, method, args) -> {
            if (method.getName().equals("loadClass")) return load((String) args[0]);
            return null;
        });
        ServiceReference<?> reference = proxy(ServiceReference.class, (object, method, args) ->
                method.getName().equals("getBundle") ? provider : null);
        BundleContext context = proxy(BundleContext.class, (object, method, args) -> switch (method.getName()) {
            case "getAllServiceReferences" -> new ServiceReference<?>[] { reference };
            case "getService" -> (Manager) (project, key) -> new Marker[0];
            case "ungetService" -> { released.incrementAndGet(); yield true; }
            default -> throw new AssertionError(method.getName());
        });
        IFile file = proxy(IFile.class, (object, method, args) -> switch (method.getName()) {
            case "getProject" -> null;
            case "getFullPath" -> new org.eclipse.core.runtime.Path("/example/Module.bsl");
            default -> null;
        });
        assertEquals(Status.SNAPSHOT, EdtModuleDiagnostics.fromService(context, file).status());
        assertEquals(1, released.get());
    }

    @Test void savedBomDirtyBufferAndChangedRevisionAreDistinguished() throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("edtai-diagnostics-test");
        project.create(null);
        project.open(null);
        IFile file = project.getFile("Module.bsl");
        var buffers = FileBuffers.getTextFileBufferManager();
        try {
            file.create(new ByteArrayInputStream("\uFEFFvalue".getBytes(StandardCharsets.UTF_8)), true, null);
            file.setCharset("UTF-8", null);
            EclipseWorkspaceFiles files = EclipseWorkspaceFiles.current();
            var snapshot = new ModuleDiagnostics(Status.SNAPSHOT, List.of(), java.time.Instant.now());
            assertEquals(Status.SNAPSHOT, files.diagnostics(file, "value", () -> snapshot).status());
            assertEquals(Status.STALE, files.diagnostics(file, "different", () -> {
                fail("stale input must not read native storage"); return snapshot;
            }).status());
            assertEquals(Status.STALE, files.diagnostics(file, "value", () -> {
                try { file.setContents(new ByteArrayInputStream("new".getBytes(StandardCharsets.UTF_8)), true, false, null); }
                catch (Exception e) { throw new RuntimeException(e); }
                return snapshot;
            }).status());
            buffers.connect(file.getFullPath(), LocationKind.IFILE, null);
            try {
                var buffer = buffers.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
                buffer.getDocument().set("dirty");
                assertEquals(Status.UNSAVED, files.diagnostics(file, "new", () -> {
                    fail("dirty text must not read native storage"); return snapshot;
                }).status());
                assertTrue(buffer.isDirty());
            } finally { buffers.disconnect(file.getFullPath(), LocationKind.IFILE, null); }
        } finally { project.delete(true, true, null); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }
}
