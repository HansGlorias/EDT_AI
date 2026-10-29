package io.github.hansglorias.edtai.core.documents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Общее для файлов Office (.docx, .xlsx, .pptx): zip из частей XML и экранирование текста. */
final class Ooxml {

    static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    /** Связи между частями пакета. */
    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/package/2006/relationships";
    static final String CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types";

    private Ooxml() {
    }

    /** Пакет: части по порядку, первой — {@code [Content_Types].xml}. */
    static byte[] zip(Map<String, String> parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** {@code _rels/.rels}: главная часть пакета. */
    static String rootRels(String type, String target) {
        return XML + "<Relationships xmlns=\"" + RELATIONSHIPS + "\"><Relationship Id=\"rId1\" Type=\"" + type + "\" Target=\""
                + target + "\"/></Relationships>";
    }

    /** Экранирование для XML; символы, недопустимые в XML 1.0, выбрасываются. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    boolean surrogatePair = Character.isHighSurrogate(c) && i + 1 < text.length()
                            && Character.isLowSurrogate(text.charAt(i + 1));
                    if (surrogatePair) {
                        out.append(c).append(text.charAt(++i));
                    } else if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20 && c < 0xD800 || c >= 0xE000 && c <= 0xFFFD) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
