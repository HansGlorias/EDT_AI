package io.github.hansglorias.edtai.core.tools;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Ограниченный снимок диагностики IDE, без обещания завершённой проверки. */
public record ModuleDiagnostics(Status status, List<Diagnostic> entries, Instant capturedAt, boolean truncated) {
    public enum Status { SNAPSHOT, UNAVAILABLE, UNSAVED, STALE, TIMEOUT }
    public enum Severity { ERROR, WARNING, INFO, UNKNOWN }

    public record Diagnostic(int line, Severity severity, String checkId, String message) {
        public Diagnostic {
            line = Math.max(0, line);
            severity = Objects.requireNonNullElse(severity, Severity.UNKNOWN);
            checkId = bounded(checkId, 80);
            message = bounded(message, 300);
        }
    }

    public ModuleDiagnostics {
        status = Objects.requireNonNull(status);
        entries = Objects.requireNonNullElse(entries, List.of());
        truncated |= entries.size() > 20;
        entries = List.copyOf(entries.subList(0, Math.min(20, entries.size())));
    }

    public ModuleDiagnostics(Status status, List<Diagnostic> entries, Instant capturedAt) {
        this(status, entries, capturedAt, false);
    }

    public static ModuleDiagnostics unavailable() {
        return new ModuleDiagnostics(Status.UNAVAILABLE, List.of(), null);
    }

    public static ModuleDiagnostics unsaved() {
        return new ModuleDiagnostics(Status.UNSAVED, List.of(), null);
    }

    static ModuleDiagnostics read(WorkspaceFiles files, String project, String path, String expectedText) {
        if (Thread.currentThread().isInterrupted()) return unavailable();
        try {
            return Objects.requireNonNullElse(files.diagnostics(project, path, expectedText), unavailable());
        } catch (RuntimeException | LinkageError e) {
            return unavailable();
        }
    }

    public String text() {
        String heading = switch (status) {
            case SNAPSHOT -> "Снимок диагностик EDT; свежесть и полнота анализа не подтверждены.";
            case UNAVAILABLE -> "Диагностика EDT недоступна; проверка не выполнена.";
            case UNSAVED -> "Модуль не сохранён; диагностика EDT не прочитана, проверка не выполнена.";
            case STALE -> "Диагностика EDT устарела: текст модуля изменился; проверка не подтверждена.";
            case TIMEOUT -> "Истекло время чтения диагностики EDT; проверка не выполнена.";
        };
        StringBuilder text = new StringBuilder(heading);
        if (capturedAt != null) text.append("\nВремя снимка: ").append(capturedAt);
        if (entries.isEmpty()) text.append("\nСообщений в снимке нет; это не подтверждает отсутствие ошибок.");
        boolean clipped = truncated;
        for (Diagnostic entry : entries) {
            String row = "\n" + entry.severity() + (entry.line() > 0 ? " строка " + entry.line() : "")
                    + (entry.checkId().isEmpty() ? "" : " [" + entry.checkId() + "]") + ": " + entry.message();
            if (text.length() + row.length() > 5850) {
                clipped = true;
                break;
            }
            text.append(row);
            clipped |= entry.message().endsWith("…") || entry.checkId().endsWith("…");
        }
        if (clipped) text.append("\n(Снимок сокращён: не более 20 сообщений, сообщение до 300 символов, общий вывод до 6000.)");
        return text.toString();
    }

    private static String bounded(String value, int max) {
        String text = Objects.requireNonNullElse(value, "").replace('\r', ' ').replace('\n', ' ');
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
