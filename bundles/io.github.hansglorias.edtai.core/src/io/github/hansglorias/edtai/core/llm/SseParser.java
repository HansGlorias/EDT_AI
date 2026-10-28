package io.github.hansglorias.edtai.core.llm;

/**
 * Разбор потока Server-Sent Events по строкам. Строки подаются без
 * перевода строки (как их отдаёт {@link java.io.BufferedReader#readLine()}),
 * событие завершается пустой строкой.
 */
final class SseParser {

    private final StringBuilder data = new StringBuilder();
    private String event = "";
    private boolean hasData;

    /** @return событие, если строка его завершила, иначе {@code null} */
    SseEvent line(String line) {
        if (line.isEmpty()) {
            return dispatch();
        }
        if (line.startsWith(":")) {
            return null;
        }
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        switch (field) {
            case "event" -> event = value;
            case "data" -> {
                if (hasData) {
                    data.append('\n');
                }
                data.append(value);
                hasData = true;
            }
            default -> {
                // id, retry и неизвестные поля не нужны.
            }
        }
        return null;
    }

    /** Конец потока: последнее событие могло остаться без завершающей пустой строки. */
    SseEvent finish() {
        return dispatch();
    }

    private SseEvent dispatch() {
        SseEvent result = hasData ? new SseEvent(event, data.toString()) : null;
        data.setLength(0);
        event = "";
        hasData = false;
        return result;
    }
}
