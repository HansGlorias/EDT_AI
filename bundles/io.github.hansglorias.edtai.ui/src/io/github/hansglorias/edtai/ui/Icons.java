package io.github.hansglorias.edtai.ui;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.ResourceLocator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;

/**
 * Значки плагина из {@code icons/}: 16 px и {@code @2x} для HiDPI (его
 * подхватывает сам {@link ImageDescriptor}). Картинки живут в общем
 * реестре JFace, их не нужно освобождать.
 */
final class Icons {

    static final String ATTACH = "attach";
    static final String HISTORY = "history";
    static final String AUTO = "auto";
    static final String NEW_CHAT = "new_chat";
    static final String SETTINGS = "settings";
    static final String STATUS_OK = "status_ok";
    static final String STATUS_ERROR = "status_error";
    static final String STATUS_OFF = "status_off";
    static final String FILE = "file";
    static final String READ_ONLY = "read_only";
    static final String COPY = "copy";
    static final String RETRY = "retry";
    static final String EDIT = "edit";

    private static final String PREFIX = "io.github.hansglorias.edtai.ui.icons/";

    private Icons() {
    }

    static ImageDescriptor descriptor(String name) {
        return ResourceLocator.imageDescriptorFromBundle(Icons.class, "icons/" + name + ".png")
                .orElse(ImageDescriptor.getMissingImageDescriptor());
    }

    /** Серый вариант значка: выключенный режим. */
    static Image gray(String name) {
        ImageRegistry registry = JFaceResources.getImageRegistry();
        String key = PREFIX + name + "#gray";
        Image image = registry.get(key);
        if (image == null) {
            registry.put(key, ImageDescriptor.createWithFlags(descriptor(name), SWT.IMAGE_GRAY));
            image = registry.get(key);
        }
        return image;
    }

    static Image image(String name) {
        ImageRegistry registry = JFaceResources.getImageRegistry();
        Image image = registry.get(PREFIX + name);
        if (image == null) {
            registry.put(PREFIX + name, descriptor(name));
            image = registry.get(PREFIX + name);
        }
        return image;
    }

    /** Общий размер и центр для значков Canvas-кнопок и состояний специалистов. */
    static void paint(GC gc, String name, Point size) {
        Image image = image(name);
        var source = image.getBounds();
        int side = Math.max(1, Math.min(16, Math.min(size.x, size.y) - 2));
        if (side > 1 && ((size.x - side) & 1) != 0 && ((size.y - side) & 1) != 0) side--;
        gc.setInterpolation(SWT.HIGH);
        gc.drawImage(image, 0, 0, source.width, source.height,
                (size.x - side) / 2, (size.y - side) / 2, side, side);
    }
}
