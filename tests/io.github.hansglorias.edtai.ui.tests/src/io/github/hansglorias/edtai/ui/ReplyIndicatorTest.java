package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.GlyphMetrics;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
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
            GridLayoutFactory.fillDefaults().applyTo(transcript);
            StyledText text = new StyledText(transcript, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY);
            GridDataFactory.fillDefaults().grab(true, true).hint(0, 0).applyTo(text);
            text.setMargins(24, 24, 24, 24);
            text.setText("\uFFFCРассуждает…\n" + "Сохранённый текст\n".repeat(80));
            StyleRange place = new StyleRange(0, 1, null, null);
            place.metrics = new GlyphMetrics(11, 3, ReplyIndicator.SIZE + ReplyIndicator.GAP);
            text.setStyleRange(place);
            ReplyIndicator indicator = new ReplyIndicator(text);
            shell.setSize(420, 320);
            shell.open();
            indicator.follow(0);
            settle();
            assertTrue(indicator.getVisible());
            text.setSelection(3, 9);
            // Каретка GTK сама вызывает Paint каждые полсекунды; здесь измеряем только кадры индикатора.
            text.getCaret().setVisible(false);
            text.update();
            settle(); // GTK дорисовывает выделение после возврата в цикл событий.
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
        assertStatusAlignment(view, "рассуждает");
        assertEquals(view.history().getParent(), indicator.getParent(), "анимация рядом с лентой, вне StyledText");
        assertEquals(view.history().getBounds().x + view.history().getLeftMargin(), indicator.getBounds().x,
                "значок на общей вертикали текста, внутри ленты");
        AtomicInteger hides = new AtomicInteger();
        AtomicInteger transcriptPaints = new AtomicInteger();
        indicator.addListener(SWT.Hide, event -> hides.incrementAndGet());
        view.history().addPaintListener(event -> transcriptPaints.incrementAndGet());
        Set<Long> visibleFrames = new HashSet<>();
        Path liveFrames = Path.of(System.getProperty("edtai.screenshots", "screenshots"), "core-live");
        Files.createDirectories(liveFrames);
        AtomicInteger captured = new AtomicInteger();
        // После вывода буфера: copyArea из Paint GC на Windows не учитывает начало частичного буфера.
        indicator.addPaintListener(event -> indicator.getDisplay().asyncExec(() -> {
            if (indicator.isDisposed() || !indicator.isVisible()) return;
            Image image = new Image(indicator.getDisplay(), ReplyIndicator.SIZE, ReplyIndicator.SIZE);
            GC painted = new GC(indicator);
            try {
                Rectangle area = indicator.logoBounds();
                painted.copyArea(image, area.x, area.y);
                ImageData data = image.getImageData();
                long hash = 1;
                for (int y = 0; y < data.height; y++) for (int x = 0; x < data.width; x++) {
                    hash = 31 * hash + data.palette.getRGB(data.getPixel(x, y)).hashCode();
                }
                visibleFrames.add(hash);
                int frame = captured.getAndIncrement();
                if (frame < 32) {
                    ImageLoader loader = new ImageLoader();
                    loader.data = new ImageData[] { data };
                    loader.save(liveFrames.resolve(String.format("frame-%02d.png", frame)).toString(), SWT.IMAGE_PNG);
                }
            } finally { painted.dispose(); image.dispose(); }
        }));
        int initialFrame = indicator.frames();
        waitUntil(() -> indicator.frames() >= initialFrame + 30);
        assertTrue(visibleFrames.size() >= 4, "настоящие кадры Canvas должны различаться: " + visibleFrames.size());
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
        assertStatusAlignment(view, "пишет ответ");
        Screenshots.render(view.control(), "core-writing");
        finish.countDown();
        waitUntil(() -> !view.session().isBusy());
        view.refresh();
        assertFalse(indicator.getVisible());
        assertFalse(indicator.isAnimating());
    }

    private static void assertStatusAlignment(ChatView view, String status) {
        int offset = view.history().getText().lastIndexOf(status);
        assertTrue(offset >= 0, view.history().getText());
        Rectangle glyph = view.history().getTextBounds(offset, offset);
        Rectangle logo = view.replyIndicator().getBounds();
        assertEquals(14, logo.width);
        assertEquals(14, logo.height);
        assertEquals(logo.x + logo.width + ReplyIndicator.GAP, view.history().getBounds().x + glyph.x);
        assertTrue(Math.abs(2 * (view.history().getBounds().y + glyph.y) + glyph.height - 2 * logo.y - logo.height) <= 1,
                "ядро по центру строки состояния: " + glyph + " / " + logo);
    }

    @Test void breathingChangesBrightnessAtActualSizeInBothThemes() {
        for (boolean dark : new boolean[] { false, true }) {
            ImageData[] frames = new ImageData[2];
            for (int frame = 0; frame < 2; frame++) {
                Image image = new Image(page.getWorkbenchWindow().getShell().getDisplay(), 14, 14);
                GC gc = new GC(image);
                Color background = dark ? new Color(30, 32, 36) : new Color(255, 255, 255);
                try {
                    gc.setBackground(background);
                    gc.fillRectangle(0, 0, 14, 14);
                    ReplyIndicator.paintLogo(gc, new Rectangle(0, 0, 14, 14), frame * 0.5);
                    frames[frame] = image.getImageData();
                } finally { background.dispose(); gc.dispose(); image.dispose(); }
            }
            int changed = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 14; x++) {
                boolean differs = !frames[0].palette.getRGB(frames[0].getPixel(x, y))
                        .equals(frames[1].palette.getRGB(frames[1].getPixel(x, y)));
                if (x < 4 && y < 4) assertFalse(differs, "углы неподвижны");
                if (differs) changed++;
            }
            assertTrue(changed >= 8, "дыхание должно быть видно при 14 px: " + changed);
            var bright = frames[0].palette.getRGB(frames[0].getPixel(7, 7));
            var dim = frames[1].palette.getRGB(frames[1].getPixel(7, 7));
            assertNotEquals(bright, dim, "меняется яркость самого ядра");
            assertNotEquals(dark ? new org.eclipse.swt.graphics.RGB(30, 32, 36)
                    : new org.eclipse.swt.graphics.RGB(255, 255, 255), dim, "ядро не исчезает полностью");
        }
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
                    var background = theme == 0 ? new org.eclipse.swt.graphics.RGB(255, 255, 255)
                            : new org.eclipse.swt.graphics.RGB(30, 32, 36);
                    assertNotEquals(background, data.palette.getRGB(data.getPixel(x + 40, 40)), "ядро не исчезает");
                    assertNotEquals(background, data.palette.getRGB(data.getPixel(x + 40, 25)), "верх ромба неподвижен");
                    assertEquals(background, data.palette.getRGB(data.getPixel(x + 51, 29)), "ромб не вращается");
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
