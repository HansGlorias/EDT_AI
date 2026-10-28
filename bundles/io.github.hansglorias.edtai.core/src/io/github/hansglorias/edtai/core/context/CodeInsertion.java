package io.github.hansglorias.edtai.core.context;

/**
 * Подготовка кода из ответа к вставке в документ: переводы строк как в
 * документе, отступ строки, куда вставляем, — у второй и следующих строк.
 */
public final class CodeInsertion {

    private CodeInsertion() {
    }

    /**
     * @param code          код из ответа, любые переводы строк
     * @param linePrefix    текст строки документа от её начала до места вставки
     * @param lineDelimiter перевод строки документа
     */
    public static String adapt(String code, String linePrefix, String lineDelimiter) {
        String indent = indent(linePrefix);
        String[] lines = code.split("\r\n|\r|\n", -1);
        StringBuilder result = new StringBuilder(code.length() + lines.length * (indent.length() + 1));
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append(lineDelimiter);
                if (!lines[i].isEmpty()) {
                    result.append(indent);
                }
            }
            result.append(lines[i]);
        }
        return result.toString();
    }

    /** Ведущие пробелы и табуляции строки. */
    static String indent(String linePrefix) {
        int end = 0;
        while (end < linePrefix.length() && (linePrefix.charAt(end) == ' ' || linePrefix.charAt(end) == '\t')) {
            end++;
        }
        return linePrefix.substring(0, end);
    }
}
