package io.github.hansglorias.edtai.core.chat;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Файл, приложенный к вопросу: картинка (скриншот формы, ошибки) уходит
 * модели изображением, текстовый файл (лог, XML, выгрузка) — текстом.
 * Хранится в сообщении: повтор запроса и перезапуск EDT отправляют то же.
 *
 * @param name      имя файла для подписи
 * @param mediaType {@code image/png}… у картинок, {@code text/plain} у текста
 * @param data      картинка — в base64, текст — как есть
 */
public record Attachment(String name, String mediaType, String data) {

    /** Больше картинку модели не принимают (Anthropic — 5 МБ в base64). */
    public static final int MAX_IMAGE_BYTES = 3_750_000;
    /** Текстовый файл больше — скорее всего, не то, что хотели приложить. */
    public static final int MAX_TEXT_CHARS = 200_000;

    private static final Map<String, String> IMAGES = Map.of("png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "gif", "image/gif", "webp", "image/webp");

    public Attachment {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(data, "data");
    }

    /** Тип вложения-скилла: инструкция, которую модель выполняет. */
    public static final String SKILL = "text/x-skill";

    /** Инструкция скилла к вопросу: команда {@code /имя}. */
    public static Attachment skill(String name, String instructions) {
        return new Attachment(name, SKILL, instructions);
    }

    public boolean isImage() {
        return mediaType.startsWith("image/");
    }

    /** Картинка PNG: скриншот из буфера обмена. */
    public static Attachment png(String name, byte[] bytes) {
        return image(name, "image/png", bytes);
    }

    private static Attachment image(String name, String mediaType, byte[] bytes) {
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Картинка «" + name + "» больше " + MAX_IMAGE_BYTES / 1_000_000
                    + " МБ: модель такую не примет. Уменьшите или обрежьте её");
        }
        return new Attachment(name, mediaType, Base64.getEncoder().encodeToString(bytes));
    }

    /**
     * Файл с диска: картинка по расширению, иначе текст в UTF-8 (или
     * Windows-1251, как у старых выгрузок 1С).
     *
     * @throws IllegalArgumentException если файл не картинка и не текст или слишком большой
     */
    public static Attachment file(String name, byte[] bytes) {
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? null : IMAGES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (type != null) {
            return image(name, type, bytes);
        }
        String text = decode(bytes);
        if (text == null || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("«" + name + "» — не текст и не картинка (PNG, JPEG, GIF, WebP)");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("«" + name + "» длиннее " + MAX_TEXT_CHARS / 1000
                    + " тыс. символов: приложите нужную часть");
        }
        return new Attachment(name, "text/plain", text.replace("\r\n", "\n").replace('\r', '\n'));
    }

    private static String decode(byte[] bytes) {
        int start = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        ByteBuffer buffer = ByteBuffer.wrap(bytes, start, bytes.length - start);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buffer).toString();
        } catch (CharacterCodingException e) {
            try {
                return Charset.forName("windows-1251").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException | UnsupportedOperationException again) {
                return null;
            }
        }
    }

    /** Размер картинки в байтах (до base64). */
    public int imageBytes() {
        return isImage() ? data.length() / 4 * 3 : 0;
    }

    /** Подпись: «screenshot.png, 120 КБ», «log.txt, 35 строк». */
    public String label() {
        if (SKILL.equals(mediaType)) {
            return "скилл " + name;
        }
        if (isImage()) {
            return name + ", " + Math.max(1, imageBytes() / 1024) + " КБ";
        }
        long lines = data.isEmpty() ? 0 : data.lines().count();
        return name + ", " + lines + " " + (lines % 10 == 1 && lines % 100 != 11 ? "строка"
                : lines % 10 >= 2 && lines % 10 <= 4 && (lines % 100 < 12 || lines % 100 > 14) ? "строки" : "строк");
    }

    /** Как модель видит вложение в тексте вопроса: текст файла целиком, у картинки — упоминание (сама она идёт отдельно). */
    public String promptText() {
        if (isImage()) {
            return "[Приложено изображение: " + name + "]";
        }
        String fence = "```";
        while (data.contains(fence)) {
            fence += "`";
        }
        if (SKILL.equals(mediaType)) {
            return "Выполни задачу по скиллу «" + name + "» — инструкция:\n" + fence + "markdown\n" + data.stripTrailing() + "\n"
                    + fence;
        }
        return "Файл " + name + ":\n" + fence + "\n" + data.stripTrailing() + "\n" + fence;
    }
}
