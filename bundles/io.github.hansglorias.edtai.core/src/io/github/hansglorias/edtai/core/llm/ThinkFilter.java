package io.github.hansglorias.edtai.core.llm;

import java.util.regex.Pattern;

/**
 * Убирает рассуждения моделей вроде Qwen3 и DeepSeek-R1: блоки
 * {@code <think>…</think>} в тексте ответа (так их присылают Ollama, LM
 * Studio и vLLM без reasoning-parser). Части потока приходят как попало,
 * поэтому тег может быть разрезан между частями: хвост, похожий на начало
 * тега, придерживается до следующей части.
 */
final class ThinkFilter {

    static final String OPEN = "<think>";
    static final String CLOSE = "</think>";

    private static final Pattern BLOCK = Pattern.compile("(?s)<think>.*?(</think>|$)");

    private final StringBuilder pending = new StringBuilder();
    private boolean thinking;
    /** Только что закрылся блок рассуждений: пробелы и переводы строк после него не нужны. */
    private boolean afterThinking;

    /** Видимая часть очередного куска потока; может быть пустой. */
    String feed(String part) {
        pending.append(part);
        StringBuilder visible = new StringBuilder();
        while (true) {
            String tag = thinking ? CLOSE : OPEN;
            int found = pending.indexOf(tag);
            if (found >= 0) {
                if (!thinking) {
                    append(visible, pending.substring(0, found));
                }
                pending.delete(0, found + tag.length());
                afterThinking = thinking;
                thinking = !thinking;
                continue;
            }
            int keep = partialTag(pending, tag);
            if (!thinking) {
                append(visible, pending.substring(0, pending.length() - keep));
            }
            pending.delete(0, pending.length() - keep);
            return visible.toString();
        }
    }

    /** Остаток в конце потока: незакрытый тег — это не тег, а текст. */
    String finish() {
        StringBuilder rest = new StringBuilder();
        if (!thinking) {
            append(rest, pending.toString());
        }
        pending.setLength(0);
        return rest.toString();
    }

    private void append(StringBuilder visible, String text) {
        String shown = afterThinking ? text.stripLeading() : text;
        if (!shown.isEmpty()) {
            afterThinking = false;
            visible.append(shown);
        }
    }

    /**
     * Текст ответа целиком без рассуждений. Одинокий {@code </think>}
     * (открывающий тег подставил шаблон чата сервера) — всё до него было
     * рассуждением.
     */
    static String strip(String text) {
        String result = BLOCK.matcher(text).replaceAll("");
        int orphan = result.lastIndexOf(CLOSE);
        if (orphan >= 0) {
            result = result.substring(orphan + CLOSE.length());
        }
        return result.equals(text) ? text : result.stripLeading();
    }

    /** Сколько символов в конце могут быть началом тега. */
    private static int partialTag(CharSequence text, String tag) {
        for (int length = Math.min(tag.length() - 1, text.length()); length > 0; length--) {
            if (tag.startsWith(text.subSequence(text.length() - length, text.length()).toString())) {
                return length;
            }
        }
        return 0;
    }
}
