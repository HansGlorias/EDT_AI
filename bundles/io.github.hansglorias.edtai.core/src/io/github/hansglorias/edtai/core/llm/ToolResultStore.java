package io.github.hansglorias.edtai.core.llm;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import io.github.hansglorias.edtai.core.chat.ToolUse;
import io.github.hansglorias.edtai.core.tools.Tool;
import io.github.hansglorias.edtai.core.tools.ToolException;

/** Неизменяемые снимки больших результатов на время одного цикла агента, только в памяти. */
final class ToolResultStore {

    private final int pageChars;
    private final int retainedChars;
    private final String prefix = UUID.randomUUID().toString();
    private final Map<String, Snapshot> snapshots = new HashMap<>();
    private final Tool reader = new Reader();
    private int usedChars;
    private int nextId;

    ToolResultStore(int pageChars, int retainedChars) {
        if (pageChars < 512 || retainedChars < pageChars) {
            throw new IllegalArgumentException("Страница должна содержать не меньше 512 символов,"
                    + " лимит хранения — не меньше страницы");
        }
        this.pageChars = pageChars;
        this.retainedChars = retainedChars;
    }

    /** Маленькие ответы не меняются; большой сохраняется целиком либо остаётся только предпросмотр. */
    String capture(String text, boolean error) {
        return captureResult(text, error).text();
    }

    synchronized Captured captureResult(String text, boolean error) {
        Objects.requireNonNull(text);
        if (text.length() <= pageChars) {
            return new Captured(text, "");
        }
        if (text.length() > retainedChars - usedChars) {
            return new Captured("Результат не сохранён: превышен лимит хранения. Продолжение недоступно.\n"
                    + errorHeader(error) + "total_chars: " + text.length() + "\noffset: 0\n"
                    + "Предпросмотр исходного результата (не новое чтение источника):\n\n"
                    + text.substring(0, pageEnd(text, 0)), "");
        }
        String id = prefix + "-" + ++nextId;
        Snapshot snapshot = new Snapshot(text, error);
        snapshots.put(id, snapshot);
        usedChars += text.length();
        String reference = "Снимок результата инструмента (не новое чтение источника).\nresult_id: " + id + "\n"
                + errorHeader(error) + "total_chars: " + text.length()
                + "\nПрочитать снимок: read_tool_result с этим result_id и offset: 0 (UTF-16).";
        return new Captured(page(id, snapshot, 0), reference);
    }

    /** Ссылка остаётся доступной, даже если страница была убрана из контекста. */
    record Captured(String text, String reference) {
        @Override
        public String toString() {
            return "Captured[reference=" + reference + "]";
        }
    }

    synchronized boolean hasResults() {
        return !snapshots.isEmpty();
    }

    Tool tool() {
        return reader;
    }

    private String page(String id, Snapshot snapshot, int offset) {
        int end = pageEnd(snapshot.text, offset);
        return "Снимок результата инструмента (не новое чтение источника).\n"
                + "result_id: " + id + "\n" + errorHeader(snapshot.error)
                + "total_chars: " + snapshot.text.length() + "\noffset: " + offset + "\n"
                + (end == snapshot.text.length() ? "EOF\n"
                        : "next_offset: " + end + "\nПродолжение: read_tool_result с result_id и next_offset в поле offset.\n")
                + "Смещения и длина — в символах UTF-16.\n\n" + snapshot.text.substring(offset, end);
    }

    private static String errorHeader(boolean error) {
        return "original_error: " + error + (error ? " (исходный вызов завершился ошибкой)\n" : "\n");
    }

    private int pageEnd(String text, int offset) {
        int end = offset + Math.min(pageChars, text.length() - offset);
        return splitsPair(text, end) ? end - 1 : end;
    }

    private static boolean splitsPair(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }

    private synchronized Tool.Result read(Map<String, Object> input) throws ToolException {
        if (input == null || input.size() != 2 || !input.containsKey("result_id") || !input.containsKey("offset")) {
            throw badArguments("Нужны только поля result_id (строка) и offset (целое число от 0)");
        }
        if (!(input.get("result_id") instanceof String id) || id.isBlank()) {
            throw badArguments("Поле result_id должно быть непустой строкой");
        }
        int offset = offset(input.get("offset"));
        Snapshot snapshot = snapshots.get(id);
        if (snapshot == null) {
            throw badArguments("Снимок результата не найден в текущем ответе. Проверьте result_id в предыдущем результате;"
                    + " не повторяйте операцию изменения данных ради чтения результата");
        }
        if (offset > snapshot.text.length() || splitsPair(snapshot.text, offset)) {
            throw badArguments("Поле offset выходит за границы снимка или разделяет пару суррогатов UTF-16");
        }
        return new Tool.Result(page(id, snapshot, offset), "снимок: символы " + offset + "–"
                + pageEnd(snapshot.text, offset) + " из " + snapshot.text.length());
    }

    private static int offset(Object value) throws ToolException {
        if (value instanceof Number number) {
            try {
                int offset = new BigDecimal(number.toString()).intValueExact();
                if (offset >= 0) {
                    return offset;
                }
            } catch (ArithmeticException | NumberFormatException e) {
                // Дроби, бесконечность и переполнение не превращаем в допустимое смещение.
            }
        }
        throw badArguments("Поле offset должно быть целым числом от 0 до " + Integer.MAX_VALUE);
    }

    private static ToolException badArguments(String message) {
        return new ToolException(ToolUse.Problem.BAD_ARGUMENTS, message);
    }

    /** Не record: автоматически сгенерированный toString не должен раскрывать текст. */
    private static final class Snapshot {
        private final String text;
        private final boolean error;

        private Snapshot(String text, boolean error) {
            this.text = text;
            this.error = error;
        }
    }

    private final class Reader implements Tool {
        @Override
        public String name() {
            return "read_tool_result";
        }

        @Override
        public String description() {
            return "Читает следующую страницу сохранённого результата по result_id и next_offset в поле offset."
                    + " Это снимок в текущем ответе, не новое чтение источника. Смещения — UTF-16.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of("type", "object", "properties", Map.of(
                    "result_id", Map.of("type", "string", "description", "Идентификатор сохранённого результата"),
                    "offset", Map.of("type", "integer", "minimum", 0, "description", "next_offset предыдущей страницы, UTF-16")),
                    "required", List.of("result_id", "offset"), "additionalProperties", false);
        }

        @Override
        public boolean repeatableRead() {
            return true;
        }

        @Override
        public String summary(Map<String, Object> input) {
            return "Читает сохранённый результат инструмента";
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            return read(input);
        }
    }
}
