package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.settings.SettingsStore;

class AiPreferencePageTest extends AbstractChatTest {

    @Test void localDiagnosticsIsOptInAndPersists() throws Exception {
        var preferences = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE.getNode(SettingsStore.QUALIFIER);
        preferences.remove(UiDiagnosticLog.ENABLED);
        PreferenceDialog dialog = openPreferences();
        try {
            var settings = (AiPreferencePage) dialog.getSelectedPage();
            dialog.getShell().setSize(900, 700);
            dialog.getShell().setLocation(0, 0);
            dialog.getShell().forceActive();
            settle();
            dialog.getShell().layout(true, true);
            var scroll = assertInstanceOf(ScrollArea.class, settings.localDiagnosticsField().getParent().getParent().getParent());
            waitUntil(() -> scroll.viewport().height > 0);
            assertTrue(scroll.vertical().isShown(), "настройки доступны прокруткой в небольшом окне");
            settings.localDiagnosticsField().setFocus();
            scroll.showControl(settings.localDiagnosticsField());
            settle();
            assertDiagnosticsVisible(settings, scroll);
            Screenshots.capture(dialog.getShell(), "preferences-diagnostics");
            assertDiagnosticsVisible(settings, scroll);
            assertFalse(settings.localDiagnosticsField().getSelection());
            settings.localDiagnosticsField().setSelection(true);
            assertTrue(settings.performOk());
            assertTrue(UiDiagnosticLog.preference());
            settings.performDefaults();
            assertFalse(settings.localDiagnosticsField().getSelection());
            assertTrue(settings.performOk());
            assertFalse(UiDiagnosticLog.preference());
        } finally {
            preferences.remove(UiDiagnosticLog.ENABLED);
            dialog.close();
        }
    }


    @Test void mcpIntervalIsEditableAndPersists() throws Exception {
        PreferenceDialog dialog = openPreferences();
        try {
            var settings = (AiPreferencePage) dialog.getSelectedPage();
            assertEquals(5, settings.current().mcpCheckMinutes());
            settings.mcpIntervalField().select(0);
            assertEquals(0, settings.current().mcpCheckMinutes());
            assertTrue(settings.performOk());
            assertEquals(0, SettingsStore.workspace().load().mcpCheckMinutes());
            settings.mcpIntervalField().select(1);
            assertEquals(3, settings.current().mcpCheckMinutes());
        } finally { dialog.close(); }
    }

    @Test
    void previewsWindowAndItsSourceFromUnsavedFields() throws Exception {
        PreferenceDialog dialog = openPreferences();
        try {
            AiPreferencePage settings = assertInstanceOf(AiPreferencePage.class, dialog.getSelectedPage());
            assertEquals(40_000, settings.current().contextLimit());
            assertEquals(0, settings.current().maxTokens());
            assertEquals(0, settings.current().modelWindow());
            assertWindow(settings, 32768, "предположение по имени/протоколу");

            settings.modelField().setText("qwen3-14b");
            assertWindow(settings, 32768, "предположение по имени/протоколу");
            settings.baseUrlField().setText("http://localhost:11434");
            dialog.getShell().setLocation(0, 0);
            Screenshots.capture(dialog.getShell(), "preferences-limits");

            settings.modelField().setText("deepseek-chat");
            assertWindow(settings, 64000, "предположение по имени/протоколу");
            settings.protocolField().select(Protocol.ANTHROPIC.ordinal());
            settings.protocolField().notifyListeners(SWT.Selection, new Event());
            assertWindow(settings, 200000, "предположение по имени/протоколу");

            settings.modelWindowField().setText("16384");
            assertWindow(settings, 16384, "настройка");
            settings.modelField().setText("gpt-4o");
            assertWindow(settings, 16384, "настройка");
            settings.protocolField().select(Protocol.OPENAI_COMPATIBLE.ordinal());
            settings.protocolField().notifyListeners(SWT.Selection, new Event());
            assertWindow(settings, 16384, "настройка");
            settings.modelWindowField().setText("");
            assertWindow(settings, 128000, "предположение по имени/протоколу");

            settings.performDefaults();
            assertWindow(settings, 32768, "предположение по имени/протоколу");
            assertEquals(AiSettings.defaults(), settings.current());
        } finally {
            dialog.close();
        }
        assertEquals(AiSettings.defaults(), SettingsStore.workspace().load(), "предпросмотр не сохраняет настройки");
    }

    @Test
    void preservesValidationAndEmptyAutomaticValues() {
        PreferenceDialog dialog = openPreferences();
        try {
            AiPreferencePage settings = assertInstanceOf(AiPreferencePage.class, dialog.getSelectedPage());
            for (Text field : new Text[] { settings.maxTokensField(), settings.contextLimitField(), settings.modelWindowField() }) {
                for (String invalid : new String[] { "0", "-1", "много", "2147483648" }) {
                    field.setText(invalid);
                    assertFalse(settings.isValid(), invalid);
                }
                field.setText("");
                assertTrue(settings.isValid());
            }
            for (String invalid : new String[] { "2047", "10000001" }) {
                settings.modelWindowField().setText(invalid);
                assertFalse(settings.isValid());
                assertTrue(settings.windowState().getText().contains("исправьте"));
            }
            for (String valid : new String[] { "2048", "10000000" }) {
                settings.modelWindowField().setText(valid);
                assertTrue(settings.isValid());
                assertWindow(settings, Integer.parseInt(valid), "настройка");
            }
        } finally {
            dialog.close();
        }
    }

    @Test
    void providerDraftsKeepTokensSeparateAndCancelDoesNotSave() throws Exception {
        PreferenceDialog dialog = openPreferences();
        try {
            AiPreferencePage settings = (AiPreferencePage) dialog.getSelectedPage();
            settings.providerNameField().setText("Локальный");
            settings.baseUrlField().setText("http://localhost:11434");
            settings.modelField().setText("qwen-local");
            settings.tokenField().setText("test-first-token");
            settings.addProvider();
            assertEquals("", settings.tokenField().getText(), "новый профиль не наследует секрет");
            settings.providerNameField().setText("Облачный");
            settings.baseUrlField().setText("https://llm.example.invalid");
            settings.modelField().setText("remote-model");
            settings.tokenField().setText("test-second-token");
            settings.switchProvider(0);
            assertEquals("qwen-local", settings.modelField().getText());
            assertEquals("test-first-token", settings.tokenField().getText());
            assertEquals(1, SettingsStore.workspace().providers().size(), "до Apply ничего не сохранено");
            settings.switchProvider(1);
            assertEquals("test-second-token", settings.tokenField().getText());
            assertTrue(settings.performOk());
            var store = SettingsStore.workspace();
            assertEquals(2, store.providers().size());
            assertEquals("test-first-token", store.loadToken(store.providers().get(0).id()));
            assertEquals("test-second-token", store.loadToken(store.providers().get(1).id()));
            dialog.getShell().setSize(900, 800);
            var scroll = (ScrollArea) settings.providerNameField().getParent().getParent().getParent();
            scroll.setOrigin(0, 0);
            Screenshots.capture(dialog.getShell(), "preferences-providers");
            settings.deleteProvider();
            assertEquals(2, store.providers().size(), "удаление остаётся черновиком до Apply");
        } finally {
            dialog.close();
            var store = SettingsStore.workspace();
            for (var provider : store.providers()) store.saveToken(provider.id(), "");
        }
    }

    @Test
    void deletingProviderRemovesItsTokenOnlyOnApply() throws Exception {
        var store = SettingsStore.workspace();
        var first = store.providers().get(0);
        var second = io.github.hansglorias.edtai.core.settings.ApiProvider.of("temporary", "Временный", AiSettings.defaults());
        store.saveProviders(java.util.List.of(first, second), second.id());
        store.saveToken(second.id(), "test-delete-token");
        PreferenceDialog dialog = openPreferences();
        try {
            AiPreferencePage settings = (AiPreferencePage) dialog.getSelectedPage();
            settings.deleteProvider();
            assertTrue(store.hasToken(second.id()));
            assertTrue(settings.performOk());
            assertEquals(1, store.providers().size());
            assertFalse(store.hasToken(second.id()));
        } finally { dialog.close(); store.saveToken(second.id(), ""); }
    }

    private static void assertDiagnosticsVisible(AiPreferencePage settings, ScrollArea scroll) {
        var field = settings.localDiagnosticsField();
        String geometry = "origin=" + scroll.getOrigin() + ", field=" + field.getBounds()
                + ", scroller=" + scroll.getBounds() + ", shell=" + field.getShell().getBounds()
                + ", focus=" + field.getDisplay().getFocusControl();
        for (var ancestor = field.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            var bounds = field.getDisplay().map(field, ancestor,
                    new Rectangle(0, 0, field.getSize().x, field.getSize().y));
            var client = ancestor.getClientArea();
            assertTrue(client.contains(bounds.x, bounds.y)
                    && client.contains(bounds.x + bounds.width - 1, bounds.y + bounds.height - 1),
                    "флажок обрезан в " + ancestor + ": " + bounds + ", client=" + client + "; " + geometry);
            if (ancestor == field.getShell()) break;
        }
    }

    private PreferenceDialog openPreferences() {
        PreferenceDialog dialog = PreferencesUtil.createPreferenceDialogOn(
                page.getWorkbenchWindow().getShell(), AiPreferencePage.ID, null, null);
        dialog.setBlockOnOpen(false);
        dialog.open();
        return dialog;
    }

    private static void assertWindow(AiPreferencePage settings, int window, String source) {
        assertEquals("Для расчёта: " + window + " токенов · " + source, settings.windowState().getText());
    }
}
