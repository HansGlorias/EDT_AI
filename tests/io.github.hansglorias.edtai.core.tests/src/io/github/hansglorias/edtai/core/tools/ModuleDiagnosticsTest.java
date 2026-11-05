package io.github.hansglorias.edtai.core.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleDiagnosticsTest {
    @TempDir Path temp;
    private static final String PATH = "src/CommonModules/Test/Module.bsl";

    @Test
    void snapshotIsImmutableBoundedAndNeverClaimsClean() {
        List<ModuleDiagnostics.Diagnostic> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(new ModuleDiagnostics.Diagnostic(i, null, "c".repeat(100), "x".repeat(1000)));
        ModuleDiagnostics snapshot = new ModuleDiagnostics(ModuleDiagnostics.Status.SNAPSHOT, rows, Instant.EPOCH);
        rows.clear();
        assertEquals(20, snapshot.entries().size());
        assertEquals(300, snapshot.entries().get(0).message().length());
        assertEquals(80, snapshot.entries().get(0).checkId().length());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().clear());
        assertTrue(snapshot.text().length() <= 6000);
        assertTrue(snapshot.text().contains("сокращён"));
        assertTrue(snapshot.text().contains("свежесть и полнота анализа не подтверждены"));
        for (ModuleDiagnostics.Status status : ModuleDiagnostics.Status.values()) {
            assertTrue(new ModuleDiagnostics(status, List.of(), null).text().contains("не подтверждает отсутствие ошибок"));
        }
    }

    @Test
    void checkReadsSavedTextWithoutBomAndNeverWrites() throws Exception {
        Path file = module();
        Files.writeString(file, "\ufeffВозврат;\n");
        AtomicInteger reads = new AtomicInteger();
        WorkspaceFiles files = workspace(reads, false, null);
        Tool check = check(files);
        assertTrue(check.repeatableRead());
        assertTrue(check.safeInPlan());
        assertTrue(ProjectTools.isBuiltInRead(check));
        assertFalse(check.modifies());
        for (int i = 0; i < 2; i++) assertTrue(check.call(Map.of("module", PATH)).text().contains("свежесть"));
        assertEquals(2, reads.get());
        assertEquals("\ufeffВозврат;\n", Files.readString(file));
        for (Object value : List.of(42, true, List.of())) {
            assertThrows(ToolException.class, () -> check.call(Map.of("module", value)));
            assertThrows(ToolException.class, () -> check.call(Map.of("module", PATH, "project", value)));
        }
        for (String value : List.of("", "../outside.bsl", "src/Configuration.mdo", "src/Missing.bsl")) {
            assertThrows(ToolException.class, () -> check.call(Map.of("module", value)));
        }
        assertEquals(2, reads.get());
    }

    @Test
    void unsavedAndCancelledDoNotReadDiagnostics() throws Exception {
        module();
        AtomicInteger reads = new AtomicInteger();
        assertTrue(check(workspace(reads, true, null)).call(Map.of("module", PATH)).text().contains("не сохранён"));
        Thread.currentThread().interrupt();
        try {
            assertTrue(check(workspace(reads, false, null)).call(Map.of("module", PATH)).text().contains("недоступна"));
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, reads.get());
    }

    @Test
    void diagnosticFailuresDoNotUndoSuccessfulEdit() throws Exception {
        Path file = module();
        for (Throwable failure : List.of(new IllegalStateException(), new NoClassDefFoundError())) {
            Files.writeString(file, "Возврат;\n");
            AtomicInteger changes = new AtomicInteger();
            Tool edit = EditTools.create(workspace(new AtomicInteger(), false, failure), change -> changes.incrementAndGet()).get(0);
            Tool.Result result = edit.call(Map.of("module", PATH, "old_text", "Возврат;", "new_text", "Сообщить(1);"));
            assertEquals("Сообщить(1);\n", Files.readString(file));
            assertEquals(1, changes.get());
            assertTrue(result.text().startsWith("Изменено:"));
            assertTrue(result.text().contains("Диагностика EDT недоступна"));
        }
    }

    @Test
    void savedEditChecksNewRevisionAndUnsavedEditSkipsNativeRead() throws Exception {
        Path file = module();
        for (boolean saved : List.of(true, false)) {
            Files.writeString(file, "// before\n");
            AtomicInteger calls = new AtomicInteger();
            WorkspaceFiles files = new WorkspaceFiles() {
                public List<String> projects() { return List.of("Test"); }
                public Optional<Path> root(String project) { return Optional.of(temp); }
                public Edit edit(String project, String path, TextChange change) throws ToolException {
                    Edit edit = WorkspaceFiles.super.edit(project, path, change);
                    return new Edit(edit.text(), edit.replacement(), saved, edit.before());
                }
                public ModuleDiagnostics diagnostics(String project, String path, String expectedText) {
                    calls.incrementAndGet();
                    assertEquals("// after\n", expectedText);
                    return new ModuleDiagnostics(ModuleDiagnostics.Status.SNAPSHOT, List.of(
                            new ModuleDiagnostics.Diagnostic(1, ModuleDiagnostics.Severity.ERROR, "test", "problem")), null);
                }
            };
            Tool.Result result = EditTools.create(files).get(0).call(Map.of("module", PATH,
                    "old_text", "// before", "new_text", "// after"));
            assertEquals(saved ? 1 : 0, calls.get());
            assertTrue(result.text().contains(saved ? "problem" : "Модуль не сохранён"));
        }
    }

    private Path module() throws Exception {
        Path file = temp.resolve(PATH);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "Возврат;\n");
        return file;
    }

    private Tool check(WorkspaceFiles files) {
        return ProjectTools.create(files, () -> 10000).stream().filter(tool -> tool.name().equals("check_module")).findFirst().orElseThrow();
    }

    private WorkspaceFiles workspace(AtomicInteger reads, boolean unsaved, Throwable failure) {
        return new WorkspaceFiles() {
            public List<String> projects() { return List.of("Test"); }
            public Optional<Path> root(String project) { return Optional.of(temp); }
            public Optional<String> unsavedText(String project, String path) { return unsaved ? Optional.of("Возврат;") : Optional.empty(); }
            public ModuleDiagnostics diagnostics(String project, String path, String expectedText) {
                reads.incrementAndGet();
                if (failure instanceof RuntimeException e) throw e;
                if (failure instanceof Error e) throw e;
                assertEquals("Возврат;\n", expectedText);
                return new ModuleDiagnostics(ModuleDiagnostics.Status.SNAPSHOT, List.of(), null);
            }
        };
    }
}
