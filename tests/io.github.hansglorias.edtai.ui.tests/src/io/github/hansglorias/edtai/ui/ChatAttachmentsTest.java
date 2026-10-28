package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.handlers.IHandlerService;
import org.junit.jupiter.api.Test;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.json.Json;

/** Вложения: файлы с диска и скриншот из буфера — на панели, в запросе модели, в ленте. */
class ChatAttachmentsTest extends AbstractChatTest {

    @Test
    void filesAndScreenshotGoToModel() throws Exception {
        answering(List.of("На скриншоте ошибка."));
        ChatView view = showView();
        Path folder = Files.createTempDirectory("edtai-files");
        Path log = Files.writeString(folder.resolve("log.txt"), "Ошибка при проведении\nстрока 2\n", StandardCharsets.UTF_8);
        Path binary = Files.write(folder.resolve("base.1cd"), new byte[] { 1, 0, 2, 0 });

        view.attachFiles(List.of(log.toString(), binary.toString(), folder.toString()));
        assertEquals(List.of("log.txt, 2 строки"), view.contextBar().labels());
        assertTrue(view.status().getText().contains("«base.1cd» — не текст и не картинка"), view.status().getText());
        assertTrue(view.status().getText().contains("— папка"), view.status().getText());

        view.attachImage(new ImageData(40, 20, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF)));
        view.attachImage(picture(600, 300));
        List<String> labels = view.contextBar().labels();
        assertEquals(3, labels.size());
        assertTrue(labels.get(1).matches("скриншот-\\d{6}\\.png, \\d+ КБ"), labels.get(1));
        Screenshots.capture(view.control(), "attachments");

        ask(view, "Что за ошибка?");

        assertTrue(view.contextBar().labels().isEmpty(), "после отправки панель пуста");
        Map<String, Object> request = Json.parseObject(server.requests().get(0).body());
        List<Object> messages = Json.asList(request.get("messages"));
        Object question = messages.get(messages.size() - 1);
        assertEquals("image_url", Json.stringAt(question, "content", 0, "type"));
        assertTrue(Json.stringAt(question, "content", 0, "image_url", "url").startsWith("data:image/png;base64,iVBORw0KGgo"),
                "PNG из буфера");
        assertEquals("image_url", Json.stringAt(question, "content", 1, "type"));
        String text = Json.stringAt(question, "content", 2, "text");
        assertTrue(text.contains("Файл log.txt:\n```\nОшибка при проведении\nстрока 2\n```"), text);
        assertTrue(text.endsWith("Что за ошибка?"), text);
        assertTrue(view.history().getText().contains(MessageRenderer.FILES + "log.txt, 2 строки; скриншот-"), view.history().getText());
        int place = view.history().getText().indexOf(MessageRenderer.OBJECT);
        assertTrue(place > 0, "миниатюра скриншота в ленте");
        StyleRange thumbnail = view.history().getStyleRangeAtOffset(place);
        assertTrue(thumbnail.data instanceof MessageRenderer.Thumbnail, String.valueOf(thumbnail.data));
        assertEquals(40, thumbnail.metrics.width - 8, "маленькая картинка не увеличивается");
        List<Path> opened = new ArrayList<>();
        view.openFile = opened::add;
        ((Runnable) thumbnail.data).run();
        assertEquals(1, opened.size());
        assertTrue(opened.get(0).getFileName().toString().startsWith("скриншот-"));
        assertTrue(Math.abs(saved(view).get(1).imageBytes() - Files.size(opened.get(0))) <= 2, "файл — сама картинка");
        Screenshots.capture(view.control(), "thumbnails");

        page.hideView(view);
        ChatView reopened = showView();
        assertTrue(reopened.history().getText().indexOf(MessageRenderer.OBJECT) > 0, "миниатюра после перезапуска");
        List<Attachment> saved = reopened.session().messages().get(0).attachments();
        assertEquals(List.of("log.txt", "image/png"), List.of(saved.get(0).name(), saved.get(1).mediaType()), "вложения сохраняются");
        assertFalse(reopened.pasteAttachments(), "в буфере нет картинок и файлов — обычная вставка текста");
    }

    @Test
    void ctrlVGoesThroughWorkbenchPasteCommand() throws Exception {
        answering(List.of("Ответ."));
        ChatView view = showView();
        view.input().setFocus();
        settle();
        IHandlerService handlers = view.getSite().getService(IHandlerService.class);

        Clipboard clipboard = new Clipboard(view.input().getDisplay());
        try {
            clipboard.setContents(new Object[] { new ImageData(30, 10, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF)) },
                    new Transfer[] { ImageTransfer.getInstance() });
            handlers.executeCommand(IWorkbenchCommandConstants.EDIT_PASTE, null);
            assertEquals(1, view.contextBar().labels().size(), view.contextBar().labels().toString());
            assertTrue(view.contextBar().labels().get(0).startsWith("скриншот-"));
            assertEquals("", view.input().getText(), "картинка не вставляется текстом");

            clipboard.setContents(new Object[] { "обычный текст" }, new Transfer[] { TextTransfer.getInstance() });
            handlers.executeCommand(IWorkbenchCommandConstants.EDIT_PASTE, null);
            assertEquals("обычный текст", view.input().getText());
            assertEquals(1, view.contextBar().labels().size());
        } finally {
            clipboard.dispose();
        }
    }

    private static List<Attachment> saved(ChatView view) {
        return view.session().messages().get(0).attachments();
    }

    /** Картинка с полосами: на скриншоте видно, что миниатюра уменьшена, а не обрезана. */
    private static ImageData picture(int width, int height) {
        ImageData data = new ImageData(width, height, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF));
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                data.setPixel(x, y, (x / 50 + y / 50) % 2 == 0 ? 0x2E7DC4 : 0xF0F0F0);
            }
        }
        return data;
    }
}
