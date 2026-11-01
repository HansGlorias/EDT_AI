package io.github.hansglorias.edtai.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;

/**
 * Снимки UI для просмотра при ревью. Сохраняются в каталог из свойства
 * {@code edtai.screenshots} (в сборке — {@code target/screenshots}).
 * С эталонами не сравниваются: шрифты на разных машинах разные.
 */
final class Screenshots {

    private Screenshots() {
    }

    /** Отрисовка самих виджетов: не захватывает перекрывающие окна других приложений. */
    static Path render(Control control, String name) throws IOException {
        flush(control.getDisplay());
        Point size = control.getSize();
        Image image = new Image(control.getDisplay(), size.x, size.y);
        try {
            GC gc = new GC(image);
            try {
                if (!control.print(gc)) {
                    throw new IOException("SWT не смог отрисовать " + name);
                }
            } finally {
                gc.dispose();
            }
            return save(image, name);
        } finally {
            image.dispose();
        }
    }

    /** Снимает область экрана, которую занимает {@code control}, в {@code <name>.png}. */
    static Path capture(Control control, String name) throws IOException {
        Display display = control.getDisplay();
        control.getShell().forceActive();
        control.redraw();
        control.update();
        flush(display);

        Point size = control.getSize();
        Rectangle bounds = display.map(control.getParent(), null, control.getBounds());
        Image image = new Image(display, size.x, size.y);
        try {
            GC gc = new GC(display);
            try {
                gc.copyArea(image, bounds.x, bounds.y);
            } finally {
                gc.dispose();
            }
            return save(image, name);
        } finally {
            image.dispose();
        }
    }

    private static Path save(Image image, String name) throws IOException {
        Path dir = Path.of(System.getProperty("edtai.screenshots", "screenshots"));
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".png");
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] { image.getImageData() };
        loader.save(file.toString(), SWT.IMAGE_PNG);
        return file;
    }

    private static void flush(Display display) {
        long deadline = System.currentTimeMillis() + 300;
        while (System.currentTimeMillis() < deadline) {
            if (!display.readAndDispatch()) {
                display.timerExec(20, () -> { });
                display.sleep();
            }
        }
    }
}
