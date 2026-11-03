package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.List;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.settings.SettingsStore;

class ChatDiagnosticsTest extends AbstractChatTest {
    @Test void preferenceAppliesToOpenViewAndItsExistingSession() throws Exception {
        answering(List.of("diagnostic-private-answer"));
        ChatView view = showView();
        var preferences = InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER);
        var file = UiDiagnosticLog.location();
        long initialSize = Files.exists(file) ? Files.size(file) : 0;
        ask(view, "diagnostic-private-question");
        assertEquals(initialSize, Files.exists(file) ? Files.size(file) : 0);
        preferences.putBoolean(UiDiagnosticLog.ENABLED, true);
        waitUntil(() -> view.status().getToolTipText().contains("Локальный журнал: включён"));
        ask(view, "diagnostic-private-question");
        waitUntil(() -> {
            try { return Files.size(file) > 0; } catch (Exception e) { return false; }
        });
        preferences.putBoolean(UiDiagnosticLog.ENABLED, false);
        waitUntil(() -> view.status().getToolTipText().contains("Локальный журнал: выключен"));
        long savedSize = Files.size(file);
        String saved = Files.readString(file);
        assertFalse(saved.contains("diagnostic-private-question"));
        assertFalse(saved.contains("diagnostic-private-answer"));
        assertFalse(saved.contains(server.url()));
        ask(view, "off-again");
        assertEquals(savedSize, Files.size(file));
        assertTrue(view.status().getToolTipText().contains("пропущено событий:"));
    }
}
