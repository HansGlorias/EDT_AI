package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Test;

class ScreenshotsTest {

    @Test void childShellCaptureUsesDisplayCoordinates() {
        Shell parent = new Shell(Display.getCurrent());
        try {
            parent.setBounds(80, 90, 500, 400);
            Shell child = new Shell(parent, SWT.DIALOG_TRIM);
            child.setBounds(170, 180, 300, 200);
            Rectangle bounds = Screenshots.captureBounds(child);
            assertEquals(child.getLocation().x, bounds.x);
            assertEquals(child.getLocation().y, bounds.y);
            assertEquals(child.getSize().x, bounds.width);
            assertEquals(child.getSize().y, bounds.height);

            parent.setLocation(120, 130);
            assertEquals(bounds, Screenshots.captureBounds(child),
                    "перемещение родительского окна не смещает область снимка дочернего shell");
        } finally {
            parent.dispose();
        }
    }

    @Test void widgetCaptureUsesItsDisplayLocation() {
        Shell parent = new Shell(Display.getCurrent());
        try {
            parent.setBounds(80, 90, 500, 400);
            Button button = new Button(parent, SWT.PUSH);
            button.setBounds(25, 35, 100, 30);
            var location = button.toDisplay(0, 0);
            assertEquals(new Rectangle(location.x, location.y, 100, 30), Screenshots.captureBounds(button));
        } finally {
            parent.dispose();
        }
    }
}
