package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;

import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.ChatAnalysisExport.Feedback;
import io.github.hansglorias.edtai.core.chat.ChatMessage;
import io.github.hansglorias.edtai.core.chat.ChatRole;
import io.github.hansglorias.edtai.core.json.Json;

class ChatAnalysisExportUiTest extends AbstractChatTest {
    @AfterEach
    void closeHistoryPanel() {
        // Вид запоминает боковую панель в memento: не менять раскладку следующих тестов.
        if (page.findView(ChatView.ID) instanceof ChatView view) view.showHistoryPanel(false);
    }

    @Test
    void dialogDefaultsToNoContentAndChoiceIsNotRemembered() throws Exception {
        var dialog = new AnalysisExportDialog(page.getWorkbenchWindow().getShell());
        try {
            dialog.create();
            dialog.getShell().open();
            assertFalse(dialog.includeButton().getSelection());
            assertFalse(dialog.expectedField().isEnabled());
            settle();
            Screenshots.capture(dialog.getShell().getChildren()[0], "analysis-export-default");
            dialog.includeButton().setSelection(true);
            dialog.includeButton().notifyListeners(SWT.Selection, new Event());
            assertTrue(dialog.expectedField().isEnabled());
            dialog.expectedField().setText("Найти реализацию");
            settle();
            Screenshots.capture(dialog.getShell().getChildren()[0], "analysis-export-content");
            dialog.okPressed();
            assertTrue(dialog.result().includeContent());
            assertEquals("Найти реализацию", dialog.result().feedback().expected());
        } finally {
            dialog.close();
        }
        var next = new AnalysisExportDialog(page.getWorkbenchWindow().getShell());
        try {
            next.create();
            assertFalse(next.includeButton().getSelection());
            assertEquals("", next.expectedField().getText());
        } finally {
            next.close();
            page.getWorkbenchWindow().getShell().forceActive();
        }
    }

    @Test
    void historyExportsSelectedSavedChatNotCurrentAndCleansContent() throws Exception {
        answering(List.of("Ответ"));
        ChatView view = showView();
        ask(view, "Выбранный разговор ui-demo-secret https://example.invalid/private");
        view.newChatForTest();
        settle();
        ask(view, "ДРУГОЙ РАЗГОВОР");
        view.showHistoryPanel(true);
        Path output = Files.createTempDirectory("edtai-analysis-ui-").resolve("разбор.zip");
        view.analysisToken = () -> "ui-demo-secret";
        view.askAnalysisOptions = () -> new AnalysisExportDialog.Options(true, new Feedback("Ожидал ответ", "Получил ответ", "ui-demo-secret"));
        view.chooseAnalysisFile = name -> {
            assertEquals("chat-analysis.zip", name);
            return output;
        };
        view.historyPanel().runMenuItem(1, "Выгрузка для разбора");
        waitUntil(() -> Files.exists(output), () -> view.status().getText());
        waitUntil(() -> view.status().getText().contains("Выгрузка сохранена"), () -> view.status().getText());
        String all = String.join("", unzip(output).values());
        assertTrue(all.contains("Выбранный разговор"));
        assertTrue(all.contains("Ожидал ответ"));
        assertFalse(all.contains("ДРУГОЙ РАЗГОВОР"));
        assertFalse(all.contains("ui-demo-secret"));
        assertFalse(all.contains("https://"));
        assertFalse(all.contains("example.invalid"));
        assertTrue(view.history().getText().contains("ДРУГОЙ РАЗГОВОР"), "экспорт не переключает текущий чат");
    }

    @Test
    void viewMenuExportsWithoutContentAndCancellationAndSecretFailureWriteNothing() throws Exception {
        answering(List.of("Ответ"));
        ChatView view = showView();
        ask(view, "ЧАСТНЫЙ ТЕКСТ");
        Path output = Files.createTempDirectory("edtai-analysis-ui-").resolve("safe.zip");
        view.analysisToken = () -> "demo-secret";
        AtomicInteger askedFile = new AtomicInteger();
        view.chooseAnalysisFile = name -> {
            askedFile.incrementAndGet();
            return output;
        };
        view.askAnalysisOptions = () -> null;
        runViewExport(view);
        assertEquals(0, askedFile.get());
        assertFalse(Files.exists(output));
        view.askAnalysisOptions = () -> new AnalysisExportDialog.Options(false, Feedback.empty());
        view.chooseAnalysisFile = name -> null;
        runViewExport(view);
        assertFalse(Files.exists(output));
        view.chooseAnalysisFile = name -> output;
        view.analysisToken = () -> { throw new IllegalStateException("demo-secret https://example.invalid"); };
        runViewExport(view);
        assertFalse(Files.exists(output));
        assertTrue(view.status().getText().contains("не удалось получить известные секреты"));
        assertFalse(view.status().getText().contains("demo-secret"));
        view.analysisToken = () -> "demo-secret";
        runViewExport(view);
        waitUntil(() -> Files.exists(output), () -> view.status().getText());
        Map<String, String> entries = unzip(output);
        assertEquals(false, Json.parseObject(entries.get("manifest.json")).get("content_included"));
        assertFalse(String.join("", entries.values()).contains("ЧАСТНЫЙ ТЕКСТ"));
        assertFalse(String.join("", entries.values()).contains("demo-secret"));
        assertTrue(entries.get("feedback.md").contains("## Ожидал"));
    }

    private static void runViewExport(ChatView view) {
        for (var item : view.getViewSite().getActionBars().getMenuManager().getItems()) {
            if (item instanceof ActionContributionItem action && action.getAction().getText().startsWith("Выгрузка этого чата")) {
                action.getAction().run();
                return;
            }
        }
        fail("Нет действия выгрузки в меню вида");
    }

    @Test
    void snapshotIsTakenAfterOptionsAndFileSelection() throws Exception {
        answering(List.of("Ответ"));
        ChatView view = showView();
        ask(view, "Текст до выбора");
        Path output = Files.createTempDirectory("edtai-analysis-ui-").resolve("fresh.zip");
        view.analysisToken = () -> "";
        view.askAnalysisOptions = () -> new AnalysisExportDialog.Options(true, Feedback.empty());
        view.chooseAnalysisFile = name -> {
            // Имитируем обновление живого чата, пока открыт модальный диалог файла.
            view.session().restore(List.of(ChatMessage.of(ChatRole.USER, "Текст после выбора")));
            return output;
        };
        runViewExport(view);
        waitUntil(() -> Files.exists(output), () -> view.status().getText());
        String all = String.join("", unzip(output).values());
        assertTrue(all.contains("Текст после выбора"));
        assertFalse(all.contains("Текст до выбора"));
    }

    @Test
    void exportCleansTokensOfAllConfiguredProviders() throws Exception {
        answering(List.of("Ответ"));
        var store = io.github.hansglorias.edtai.core.settings.SettingsStore.workspace();
        var first = store.providers().get(0);
        var second = io.github.hansglorias.edtai.core.settings.ApiProvider.of("export", "Второй", store.load());
        store.saveProviders(List.of(first, second), first.id());
        store.saveToken(first.id(), "first-export-secret");
        store.saveToken(second.id(), "second-export-secret");
        try {
            ChatView view = showView();
            ask(view, "первый first-export-secret второй second-export-secret");
            Path output = Files.createTempDirectory("edtai-providers-export-").resolve("safe.zip");
            view.askAnalysisOptions = () -> new AnalysisExportDialog.Options(true, Feedback.empty());
            view.chooseAnalysisFile = name -> output;
            runViewExport(view);
            waitUntil(() -> Files.exists(output), () -> view.status().getText());
            waitUntil(() -> view.status().getText().contains("Выгрузка сохранена"));
            String all = String.join("", unzip(output).values());
            assertTrue(all.contains("первый"));
            assertFalse(all.contains("first-export-secret"));
            assertFalse(all.contains("second-export-secret"));
        } finally {
            store.saveToken(first.id(), "");
            store.saveToken(second.id(), "");
        }
    }

    private static Map<String, String> unzip(Path file) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
