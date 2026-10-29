package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import io.github.hansglorias.edtai.core.documents.XlsxWriter;
import io.github.hansglorias.edtai.core.json.Json;

/** Вложения: файлы с диска и скриншот из буфера — на панели, в запросе модели, в ленте. */
class ChatAttachmentsTest extends AbstractChatTest {

    @Test
    void excelAndPdfGoAsText() throws Exception {
        answering(List.of("Молока больше."));
        ChatView view = showView();
        Path folder = Files.createTempDirectory("edtai-documents");
        Path excel = Files.write(folder.resolve("Остатки.xlsx"), XlsxWriter.fromText("| Товар | Кол-во |\n|---|---|\n| Молоко | 10 |"));
        Path old = Files.write(folder.resolve("Старый.xls"), new byte[] { (byte) 0xD0, (byte) 0xCF });

        view.attachFiles(List.of(excel.toString(), old.toString()));
        assertEquals(List.of("Остатки.xlsx, 4 строки"), view.contextBar().labels());
        assertTrue(view.status().getText().contains("«Старый.xls» — старый формат Office: сохраните как .xlsx"), view.status().getText());

        ask(view, "Чего больше?");

        List<Object> messages = Json.asList(Json.parseObject(server.requests().get(0).body()).get("messages"));
        String text = Json.stringAt(messages.get(messages.size() - 1), "content");
        assertTrue(text.contains("Файл Остатки.xlsx (текст документа):\n```\n## Лист «Лист1»\n\nТовар\tКол-во\nМолоко\t10\n```"), text);
    }

    @Test
    void filesAndScreenshotGoToModel() throws Exception {
        answering(List.of("На скриншоте ошибка."));
        ChatView view = showView();
        Path folder = Files.createTempDirectory("edtai-files");
        Path log = Files.writeString(folder.resolve("log.txt"), "Ошибка при проведении\nстрока 2\n", StandardCharsets.UTF_8);
        Path binary = Files.write(folder.resolve("base.1cd"), new byte[] { 1, 0, 2, 0 });

        view.attachFiles(List.of(log.toString(), binary.toString(), folder.toString()));
        assertEquals(List.of("log.txt, 2 строки"), view.contextBar().labels());
        assertTrue(view.status().getText().contains("«base.1cd» — не текст: приложить можно картинки"), view.status().getText());
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

    @Test
    void modelAttachesWordDocument() throws Exception {
        String arguments = Json.write(Map.of("name", "Инструкция.docx", "content", "# Обмен\n\n1. Откройте настройки\n2. Запустите"));
        server = FakeModelServer.http().handler((request, response) -> {
            if (!request.body().contains("\"role\":\"tool\"")) {
                response.sse(List.of("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"type\":\"function\",\"function\":{\"name\":\"attach_file\",\"arguments\":" + Json.write(arguments)
                        + "}}]}}]}\n\n", "data: [DONE]\n\n"), 5);
            } else {
                response.sse(List.of(openAiPart("Инструкция в файле."), "data: [DONE]\n\n"), 5);
            }
        });
        configure(server);
        ChatView view = showView();

        ask(view, "Сделай инструкцию в Word");

        List<Object> messages = Json.asList(Json.parseObject(server.requests().get(1).body()).get("messages"));
        String result = Json.stringAt(messages.get(messages.size() - 1), "content");
        assertTrue(result.startsWith("Файл «Инструкция.docx» ("), result);
        List<Attachment> files = view.session().messages().get(1).attachments();
        assertEquals(1, files.size());
        byte[] document = files.get(0).bytes();
        assertEquals("PK", new String(document, 0, 2, StandardCharsets.US_ASCII), "docx — zip");
        String transcript = view.history().getText();
        assertTrue(transcript.contains("Инструкция в файле.\n\n" + MessageRenderer.OBJECT + "Инструкция.docx  "), transcript);
        assertTrue(transcript.endsWith(MessageRenderer.SAVE_FILE + "   " + MessageRenderer.OPEN_FILE), transcript);
        int icon = transcript.lastIndexOf(MessageRenderer.OBJECT);
        assertTrue(view.history().getStyleRangeAtOffset(icon).data instanceof MessageRenderer.FileIcon);
        assertTrue(view.hasCodeBackground(icon), "строка файла — карточкой, с фоном");
        Screenshots.capture(view.control(), "answer-file");

        Path folder = Files.createTempDirectory("edtai-save");
        view.chooseSaveFile = name -> folder.resolve(name);
        view.linkAction(MessageRenderer.SAVE_FILE).run();
        assertArrayEquals(document, Files.readAllBytes(folder.resolve("Инструкция.docx")));
        assertTrue(view.status().getText().startsWith("Сохранено: "), view.status().getText());

        List<Path> opened = new ArrayList<>();
        view.openFile = opened::add;
        view.linkAction(MessageRenderer.OPEN_FILE).run();
        assertEquals("Инструкция.docx", opened.get(0).getFileName().toString());
        assertArrayEquals(document, Files.readAllBytes(opened.get(0)));

        page.hideView(view);
        ChatView reopened = showView();
        assertTrue(reopened.history().getText().contains(MessageRenderer.OBJECT + "Инструкция.docx  "), "файл после перезапуска");
        assertArrayEquals(document, reopened.session().messages().get(1).attachments().get(0).bytes());
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
