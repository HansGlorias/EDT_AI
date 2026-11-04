package io.github.hansglorias.edtai.core.tools;

/**
 * Правка текста файла: по текущему тексту — что заменить. Считается в
 * момент применения, чтобы не разойтись с правками пользователя в
 * редакторе.
 */
@FunctionalInterface
public interface TextChange {

    /** Заменить {@code length} символов с {@code offset} на {@code text}. */
    record Replacement(int offset, int length, String text) {
    }

    /**
     * @throws ToolException если правку нельзя применить к этому тексту
     */
    Replacement compute(String current) throws ToolException;

    /**
     * Замена единственного вхождения {@code oldText} на {@code newText}.
     * Переводы строк — как в файле: модель пишет {@code \n}, а файл может быть
     * с {@code \r\n}. Точного вхождения нет — ищутся те же целые строки без учёта
     * различий в отступах ({@link LooseMatch}).
     */
    static TextChange replace(String oldText, String newText) {
        return current -> {
            String delimiter = current.contains("\r\n") ? "\r\n" : "\n";
            String from = normalize(oldText, delimiter);
            String to = normalize(newText, delimiter);
            if (from.isEmpty()) {
                throw new ToolException("old_text пустой: укажи точный фрагмент модуля, который заменить");
            }
            int first = current.indexOf(from);
            if (first < 0) {
                // Точного нет: те же строки без учёта отступов (табы/пробелы, номера строк), отступы — как в модуле.
                return LooseMatch.replace(current, normalize(oldText, "\n"), normalize(newText, "\n"), delimiter);
            }
            int count = 0;
            for (int at = first; at >= 0; at = current.indexOf(from, at + 1)) {
                count++;
            }
            if (count > 1) {
                throw new ToolException("old_text встречается в модуле " + count + " раз: добавь соседние строки, чтобы"
                        + " фрагмент был единственным");
            }
            return new Replacement(first, from.length(), to);
        };
    }

    private static String normalize(String text, String delimiter) {
        String lines = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        return delimiter.equals("\n") ? lines : lines.replace("\n", delimiter);
    }
}
