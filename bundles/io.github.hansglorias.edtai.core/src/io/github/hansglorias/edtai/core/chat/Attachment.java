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

import io.github.hansglorias.edtai.core.documents.DocumentText;

/**
 * Файл, приложенный к вопросу: картинка (скриншот формы, ошибки) уходит
 * модели изображением, текстовый файл (лог, XML, выгрузка) — текстом,
 * документ (Word, Excel, PowerPoint, LibreOffice, PDF) — своим текстом.
 * Хранится в сообщении: повтор запроса и перезапуск EDT отправляют то же.
 * У ответа ассистента — файлы, которые сделала модель (документ Word,
 * CSV): их пользователь сохраняет или открывает.
 *
 * @param name      имя файла для подписи
 * @param mediaType {@code image/png}… у картинок, {@code text/…} у текста, иначе — двоичный файл
 * @param data      текст — как есть, остальное — в base64
 */
public record Attachment(String name, String mediaType, String data) {

    /** Больше картинку модели не принимают (Anthropic — 5 МБ в base64). */
    public static final int MAX_IMAGE_BYTES = 3_750_000;
    /** Текстовый файл больше — скорее всего, не то, что хотели приложить. */
    public static final int MAX_TEXT_CHARS = 200_000;
    /** Предел файла, который делает модель: больше в историю чата не кладём. */
    public static final int MAX_FILE_BYTES = 5_000_000;

    private static final Map<String, String> IMAGES = Map.of("png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "gif", "image/gif", "webp", "image/webp");

    public Attachment {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(data, "data");
    }

    /** Тип вложения-скилла: инструкция, которую модель выполняет. */
    public static final String SKILL = "text/x-skill";
    /** Тип вложения-режима: в каком виде нужен ответ ({@link DialogMode}). */
    public static final String MODE = "text/x-mode";
    /** Текст, взятый из документа: сам файл модели не уходит. */
    public static final String DOCUMENT = "text/x-document";

    /** Инструкция скилла к вопросу: команда {@code /имя}. */
    public static Attachment skill(String name, String instructions) {
        return new Attachment(name, SKILL, instructions);
    }

    /** Режим вопроса: {@link DialogMode#attachment()}. */
    public static Attachment mode(String label, String instruction) {
        return new Attachment(label, MODE, instruction);
    }

    public boolean isImage() {
        return mediaType.startsWith("image/");
    }

    /** Текст (в том числе скилл): данные — сам текст, а не base64. */
    public boolean isText() {
        return mediaType.startsWith("text/");
    }

    /** Двоичный файл, например документ Word. */
    public static Attachment binary(String name, String mediaType, byte[] bytes) {
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Файл «" + name + "» больше " + MAX_FILE_BYTES / 1_000_000 + " МБ");
        }
        return new Attachment(name, mediaType, Base64.getEncoder().encodeToString(bytes));
    }

    /** Содержимое файла: текст — в UTF-8. */
    public byte[] bytes() {
        return isText() ? data.getBytes(StandardCharsets.UTF_8) : Base64.getDecoder().decode(data);
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
     * Файл с диска: картинка по расширению, документ — его текст, иначе
     * текст в UTF-8 (или Windows-1251, как у старых выгрузок 1С).
     *
     * @throws IllegalArgumentException если файл не картинка, не документ и не текст или слишком большой
     */
    public static Attachment file(String name, byte[] bytes) {
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? null : IMAGES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (type != null) {
            return image(name, type, bytes);
        }
        if (DocumentText.isDocument(name)) {
            return new Attachment(name, DOCUMENT, DocumentText.extract(name, bytes));
        }
        String text = decode(bytes);
        if (text == null || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("«" + name + "» — не текст: приложить можно картинки, текстовые файлы, документы "
                    + "Word, Excel, PowerPoint, LibreOffice и PDF");
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
        if (MODE.equals(mediaType)) {
            return "режим «" + name + "»";
        }
        if (isImage()) {
            return name + ", " + Math.max(1, imageBytes() / 1024) + " КБ";
        }
        if (!isText()) {
            return name + ", " + size(data.length() / 4 * 3);
        }
        long lines = data.isEmpty() ? 0 : data.lines().count();
        return name + ", " + lines + " " + (lines % 10 == 1 && lines % 100 != 11 ? "строка"
                : lines % 10 >= 2 && lines % 10 <= 4 && (lines % 100 < 12 || lines % 100 > 14) ? "строки" : "строк");
    }

    /** Размер для подписи: «850 байт», «12 КБ», «1,4 МБ». */
    public static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " байт";
        }
        if (bytes < 1024 * 1024) {
            return bytes / 1024 + " КБ";
        }
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024)).replace('.', ',') + " МБ";
    }

    /** Как модель видит вложение в тексте вопроса: текст файла целиком, у картинки — упоминание (сама она идёт отдельно). */
    public String promptText() {
        if (isImage()) {
            return "[Приложено изображение: " + name + "]";
        }
        if (MODE.equals(mediaType)) {
            return "Режим ответа «" + name + "»: " + data;
        }
        if (!isText()) {
            return "[Приложен файл: " + name + "]";
        }
        String fence = "```";
        while (data.contains(fence)) {
            fence += "`";
        }
        if (SKILL.equals(mediaType)) {
            return "Выполни задачу по скиллу «" + name + "» — инструкция:\n" + fence + "markdown\n" + data.stripTrailing() + "\n"
                    + fence;
        }
        String what = DOCUMENT.equals(mediaType) ? " (текст документа)" : "";
        return "Файл " + name + what + ":\n" + fence + "\n" + data.stripTrailing() + "\n" + fence;
    }
}
