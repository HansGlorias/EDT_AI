package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.ui.IWorkbenchPage;
import org.junit.jupiter.api.Test;

class ReplyIndicatorTest extends AbstractChatTest {
    @Test void framesDoNotRepaintOrChangeTranscriptAndStopOffscreen() throws Exception {
        Shell shell = new Shell(page.getWorkbenchWindow().getShell(), SWT.SHELL_TRIM);
        try {
            shell.setLayout(new FillLayout());
            Composite transcript = new Composite(shell, SWT.NONE);
            GridLayoutFactory.fillDefaults().numColumns(2).spacing(0, 0).applyTo(transcript);
            StyledText text = new StyledText(transcript, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY);
            GridDataFactory.fillDefaults().grab(true, true).hint(0, 0).applyTo(text);
            text.setMargins(0, 24, 24, 24);
            text.setText("Рассуждает…\n" + "Сохранённый текст\n".repeat(80));
            ReplyIndicator indicator = new ReplyIndicator(text);
            GridDataFactory.fillDefaults().grab(false, true).hint(ReplyIndicator.GUTTER, SWT.DEFAULT).applyTo(indicator);
            shell.setSize(420, 320);
            shell.open();
            indicator.follow(0);
            settle();
            assertTrue(indicator.getVisible());
            text.setSelection(3, 9);
            text.update();
            String before = text.getText();
            var selection = text.getSelection();
            var bounds = indicator.getBounds();
            AtomicInteger paints = new AtomicInteger();
            text.addPaintListener(event -> paints.incrementAndGet());
            int frame = indicator.frames();
            waitUntil(() -> indicator.frames() >= frame + 6);
            assertEquals(0, paints.get(), "кадры ядра не перерисовывают StyledText");
            assertEquals(before, text.getText());
            assertEquals(selection, text.getSelection());
            assertEquals(bounds, indicator.getBounds(), "значок не прыгает при смене кадра");

            text.setTopPixel(300);
            indicator.reposition();
            assertFalse(indicator.getVisible());
            assertFalse(indicator.isAnimating());
            int stoppedFrame = indicator.frames();
            settle();
            assertEquals(stoppedFrame, indicator.frames(), "вне экрана таймер остановлен");
            text.setTopPixel(0);
            indicator.reposition();
            assertTrue(indicator.getVisible());
            indicator.follow(-1);
            assertFalse(indicator.getVisible());
            assertFalse(indicator.isAnimating());
        } finally { shell.dispose(); }
        settle(); // отменённый таймер не обращается к уничтоженному Canvas
    }

    @Test void sameIndicatorSurvivesReasoningTextAndChatSwitchThenStops() throws Exception {
        CountDownLatch write = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        server = FakeModelServer.http().handler((request, response) -> {
            response.start(200, "text/event-stream");
            response.write("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\n");
            write.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            response.write(openAiPart("Готовлю ответ"));
            finish.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            response.write("data: [DONE]\n\n");
            response.end();
        });
        configure(server);
        ChatView view = showView();
        view.showHistoryPanel(false);
        page.setPartState(page.getReference(view), IWorkbenchPage.STATE_RESTORED);
        settle();
        page.setPartState(page.getReference(view), IWorkbenchPage.STATE_MAXIMIZED);
        settle();
        view.input().setText("Вопрос");
        view.send();
        waitUntil(() -> view.history().getText().contains("рассуждает") && view.replyIndicator().getVisible(),
                () -> "лента=" + view.history().getClientArea() + ", текст=" + view.history().getText());
        ReplyIndicator indicator = view.replyIndicator();
        assertEquals(view.history().getParent(), indicator.getParent(), "анимация рядом с лентой, вне StyledText");
        assertTrue(indicator.getBounds().x + indicator.getSize().x <= view.history().getBounds().x);
        AtomicInteger hides = new AtomicInteger();
        AtomicInteger transcriptPaints = new AtomicInteger();
        indicator.addListener(SWT.Hide, event -> hides.incrementAndGet());
        view.history().addPaintListener(event -> transcriptPaints.incrementAndGet());
        int initialFrame = indicator.frames();
        waitUntil(() -> indicator.frames() >= initialFrame + 30);
        assertEquals(0, hides.get(), "обновление секунд ожидания не скрывает значок");
        assertTrue(transcriptPaints.get() < 6, "лента не перерисовывается на каждом кадре: " + transcriptPaints.get());
        Screenshots.render(view.control(), "core-reasoning");
        String id = view.chats().list().get(0).id();
        view.newChatForTest();
        assertFalse(indicator.getVisible());
        assertFalse(indicator.isAnimating());
        view.openChatForTest(id);
        waitUntil(indicator::getVisible);
        write.countDown();
        waitUntil(() -> view.history().getText().contains("Готовлю ответ"));
        assertSame(indicator, view.replyIndicator());
        assertTrue(indicator.isAnimating());
        Screenshots.render(view.control(), "core-writing");
        finish.countDown();
        waitUntil(() -> !view.session().isBusy());
        view.refresh();
        assertFalse(indicator.getVisible());
        assertFalse(indicator.isAnimating());
    }

    @Test void logoKeepsCornersAndCoreVisibleOnEveryFrame() throws Exception {
        Path directory = Path.of(System.getProperty("edtai.screenshots", "screenshots"), "core-animation");
        Files.createDirectories(directory);
        org.eclipse.swt.graphics.ImageData first = null;
        for (int frame = 0; frame < 32; frame++) {
            Image image = new Image(page.getWorkbenchWindow().getShell().getDisplay(), 160, 80);
            GC gc = new GC(image);
            try {
                for (int theme = 0; theme < 2; theme++) {
                    Color background = theme == 0 ? new Color(255, 255, 255) : new Color(30, 32, 36);
                    gc.setBackground(background);
                    gc.fillRectangle(theme * 80, 0, 80, 80);
                    ReplyIndicator.paintLogo(gc, new Rectangle(theme * 80, 0, 80, 80), frame / 32.0);
                    background.dispose();
                }
                var data = image.getImageData();
                if (first == null) first = data;
                for (int theme = 0; theme < 2; theme++) {
                    int x = theme * 80;
                    assertEquals(new org.eclipse.swt.graphics.RGB(0x5B, 0x7B, 0xD5), data.palette.getRGB(data.getPixel(x + 40, 40)));
                    assertEquals(first.getPixel(x + 40, 40), data.getPixel(x + 40, 40), "ядро не исчезает");
                    for (int y = 8; y < 26; y++) for (int at = x + 8; at < x + 26; at++) {
                        assertEquals(first.getPixel(at, y), data.getPixel(at, y), "углы неподвижны");
                    }
                }
                ImageLoader loader = new ImageLoader();
                loader.data = new org.eclipse.swt.graphics.ImageData[] { data };
                loader.save(directory.resolve(String.format("frame-%02d.png", frame)).toString(), SWT.IMAGE_PNG);
            } finally { gc.dispose(); image.dispose(); }
        }
    }
}
