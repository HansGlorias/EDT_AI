package io.github.hansglorias.edtai.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
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
        List<String> labels = view.contextBar().labels();
        assertEquals(2, labels.size());
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
        String text = Json.stringAt(question, "content", 1, "text");
        assertTrue(text.contains("Файл log.txt:\n```\nОшибка при проведении\nстрока 2\n```"), text);
        assertTrue(text.endsWith("Что за ошибка?"), text);
        assertTrue(view.history().getText().contains(MessageRenderer.FILES + "log.txt, 2 строки; скриншот-"), view.history().getText());

        page.hideView(view);
        ChatView reopened = showView();
        List<Attachment> saved = reopened.session().messages().get(0).attachments();
        assertEquals(List.of("log.txt", "image/png"), List.of(saved.get(0).name(), saved.get(1).mediaType()), "вложения сохраняются");
        assertFalse(reopened.pasteAttachments(), "в буфере нет картинок и файлов — обычная вставка текста");
    }
}
