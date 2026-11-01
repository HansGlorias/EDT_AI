package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.Protocol;
import io.github.hansglorias.edtai.core.settings.SettingsStore;

class AiPreferencePageTest extends AbstractChatTest {

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
