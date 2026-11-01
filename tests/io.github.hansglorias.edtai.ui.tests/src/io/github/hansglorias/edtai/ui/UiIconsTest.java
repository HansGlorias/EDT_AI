package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.swt.graphics.ImageData;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

/** Реальные ресурсы бандла и действия верхнего ряда в живом workbench. */
class UiIconsTest extends AbstractChatTest {

    @Test
    void iconsHaveTransparentNormalAndHidpiResources() throws Exception {
        var bundle = FrameworkUtil.getBundle(Icons.class);
        var entries = bundle.findEntries("icons", "*.png", false);
        int count = 0;
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            try (var input = entry.openStream()) {
                ImageData data = new ImageData(input);
                int size = entry.getPath().contains("@2x") ? 32 : 16;
                assertEquals(size, data.width, entry.toString());
                assertEquals(size, data.height, entry.toString());
                boolean transparent = false;
                boolean visible = false;
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        transparent |= data.getAlpha(x, y) == 0;
                        visible |= data.getAlpha(x, y) > 200;
                    }
                }
                assertTrue(transparent && visible, entry.toString());
                String name = entry.getPath().substring(entry.getPath().lastIndexOf('/') + 1).replace("@2x", "");
                assertNotNull(bundle.getEntry("icons/" + name.replace(".png", "@2x.png")));
                assertNotNull(bundle.getEntry("icons/" + name));
                count++;
            }
        }
        assertEquals(36, count, "18 значков, каждый в двух разрешениях");
    }

    @Test
    void reviewSymbolsFitTwelvePixelRulerCell() {
        for (String name : List.of("review_error", "review_warning", "review_info")) {
            for (int zoom : new int[] { 100, 200 }) {
                ImageData data = Icons.descriptor(name).getImageData(zoom);
                int margin = 2 * zoom / 100;
                for (int y = 0; y < data.height; y++) {
                    for (int x = 0; x < data.width; x++) {
                        if (x < margin || y < margin || x >= data.width - margin || y >= data.height - margin) {
                            assertEquals(0, data.getAlpha(x, y), name + " не обрезается в узкой линейке, zoom=" + zoom);
                        }
                    }
                }
            }
        }
    }

    @Test
    void headerHasThreeActionsAndKeepsPreviousChat() throws Exception {
        answering(List.of("Готово."));
        ChatView view = showView();
        view.showHistoryPanel(false);
        var items = view.getViewSite().getActionBars().getToolBarManager().getItems();
        List<IAction> actions = Arrays.stream(items).filter(ActionContributionItem.class::isInstance)
                .map(ActionContributionItem.class::cast).map(ActionContributionItem::getAction).toList();
        assertEquals(List.of("edtai.newChat", "edtai.history", "edtai.settings"), actions.stream().map(IAction::getId).toList());
        for (IAction action : actions) {
            assertNotNull(action.getImageDescriptor());
            assertFalse(action.getToolTipText().isBlank());
        }
        IAction history = actions.get(1);
        assertFalse(history.isChecked());
        history.setChecked(true);
        history.run();
        assertTrue(history.isChecked());

        ask(view, "Проверка нового чата");
        assertEquals("Готово.", view.session().messages().get(1).text(), view.history().getText());
        actions.get(0).run();
        assertTrue(view.session().messages().isEmpty());
        assertTrue(savedChats().contains("Проверка нового чата"));
        Screenshots.render(view.control().getShell().getChildren()[0], "unified-icons-header");
        page.getWorkbenchWindow().getShell().setSize(420, 780);
        view.showHistoryPanel(false);
        settle();
        Screenshots.render(view.control().getShell().getChildren()[0], "unified-icons-narrow");
    }
}
