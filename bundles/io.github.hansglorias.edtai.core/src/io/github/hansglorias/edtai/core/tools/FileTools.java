package io.github.hansglorias.edtai.core.tools;

import static io.github.hansglorias.edtai.core.tools.ProjectTools.schema;
import static io.github.hansglorias.edtai.core.tools.ProjectTools.string;
import static io.github.hansglorias.edtai.core.tools.ProjectTools.text;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.hansglorias.edtai.core.chat.Attachment;
import io.github.hansglorias.edtai.core.documents.DocxWriter;

/**
 * Файлы, которые модель отдаёт пользователю в ответе: документ Word из
 * Markdown, CSV для Excel, текст, XML, код. Файл встаёт под ответом,
 * пользователь сохраняет или открывает его. Проект не меняется, поэтому
 * инструмент есть и в режиме «только вопросы».
 */
public final class FileTools {

    /** Форматы, которые из текста модели не сделать. */
    static final Set<String> UNSUPPORTED = Set.of("pdf", "doc", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "png", "jpg",
            "jpeg", "gif", "webp", "bmp", "ico", "zip", "rar", "7z", "gz", "exe", "dll", "epf", "erf", "cf", "cfe", "dt", "mxl");
    private static final Map<String, String> TEXT_TYPES = Map.of("csv", "text/csv", "md", "text/markdown", "html",
            "text/html", "htm", "text/html", "xml", "text/xml");
    private static final int MAX_NAME = 120;

    private FileTools() {
    }

    public static List<Tool> create() {
        return List.of(new AttachFile());
    }

    private record AttachFile() implements Tool {

        @Override
        public String name() {
            return "attach_file";
        }

        @Override
        public String description() {
            return "Прикладывает к ответу файл, который пользователь сохранит или откроет: документ Word (.docx — содержимое"
                    + " пиши в Markdown: заголовки, списки, таблицы, жирный), таблицу для Excel (.csv, разделитель «;»), текст"
                    + " (.txt, .md), .xml, .json, .html, код (.bsl). Вызывай, когда пользователь просит документ или файл, а не"
                    + " ответ в чате. Проект не меняет: модули правят другие инструменты. Содержимое файла в ответе не повторяй.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema(Map.of("name", string("Имя файла с расширением, например «Инструкция по обмену.docx», «остатки.csv»"),
                    "content", string("Содержимое: для .docx — Markdown, для остальных — текст файла как есть")),
                    List.of("name", "content"));
        }

        @Override
        public String summary(Map<String, Object> input) {
            return "Готовит файл " + text(input, "name");
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            Attachment file = file(text(input, "name"), String.valueOf(input.getOrDefault("content", "")));
            String size = Attachment.size(file.bytes().length);
            return new Result("Файл «" + file.name() + "» (" + size + ") приложен к ответу: пользователь видит его под ответом"
                    + " и может сохранить или открыть. Не повторяй содержимое файла — коротко скажи, что в нём.", size, file);
        }
    }

    /**
     * Файл по имени и содержимому от модели.
     *
     * @throws ToolException если имя без расширения, формат не поддерживается или файл слишком большой
     */
    static Attachment file(String rawName, String content) throws ToolException {
        String name = fileName(rawName);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            throw new ToolException("Укажите имя файла с расширением, например «отчёт.docx»");
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (UNSUPPORTED.contains(extension)) {
            throw new ToolException("Формат ." + extension + " сделать не могу. Можно: .docx (из Markdown), .csv (откроется в"
                    + " Excel), .txt, .md, .html, .xml, .json, .bsl и другие текстовые");
        }
        if (content.length() > Attachment.MAX_TEXT_CHARS) {
            throw new ToolException("Содержимое длиннее " + Attachment.MAX_TEXT_CHARS / 1000 + " тыс. символов: сократите");
        }
        try {
            if (extension.equals("docx")) {
                return Attachment.binary(name, DocxWriter.MEDIA_TYPE, DocxWriter.fromMarkdown(content));
            }
            String text = content.replace("\r\n", "\n");
            // Excel узнаёт UTF-8 в CSV только по BOM, иначе кириллица — кракозябры.
            if (extension.equals("csv") && !text.startsWith("﻿")) {
                text = "﻿" + text;
            }
            return new Attachment(name, TEXT_TYPES.getOrDefault(extension, "text/plain"), text);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
    }

    /** Имя без каталогов и запрещённых в Windows символов. */
    static String fileName(String raw) {
        String name = raw.strip();
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[:*?\"<>|\\p{Cntrl}]", "_").strip();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.length() > MAX_NAME) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
            name = name.substring(0, MAX_NAME - extension.length()).strip() + extension;
        }
        return name;
    }
}
