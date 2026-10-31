package io.github.hansglorias.edtai.core.chat;

import java.util.Locale;

/**
 * Токены ответа: сколько ушло модели и сколько она написала за все шаги
 * ответа (с вызовами инструментов модель получает диалог на каждом шаге
 * заново), и сколько окна модели занято после последнего шага.
 *
 * @param input     токены запросов, сумма по шагам
 * @param output    токены ответа модели, сумма по шагам
 * @param context   занято окна после последнего шага: его запрос и ответ
 * @param estimated сервер не прислал usage — посчитано по длине текста
 */
public record TokenUsage(long input, long output, long context, boolean estimated) {

    /** Шаг ответа: запрос {@code input} токенов, ответ {@code output}. */
    public static TokenUsage step(long input, long output, boolean estimated) {
        return new TokenUsage(input, output, input + output, estimated);
    }

    /** Этот ответ и ещё один шаг. */
    public TokenUsage plus(TokenUsage step) {
        return new TokenUsage(input + step.input, output + step.output, step.context, estimated || step.estimated);
    }

    /**
     * Для строки состояния: «контекст 45k из 200k · ответ ↑120k ↓2,1k».
     *
     * @param window окно модели в токенах; 0 — не показывать
     */
    public String describe(long window) {
        String approx = estimated ? "≈" : "";
        String fill = "контекст " + approx + format(context) + (window > 0 ? " из " + format(window) : "");
        return fill + " · ответ ↑" + approx + format(input) + " ↓" + approx + format(output);
    }

    /** Шаг коротко, для строки вызова инструмента: «↑12k ↓310», по оценке — «↑≈12k ↓≈310». */
    public String describeStep() {
        String approx = estimated ? "≈" : "";
        return "↑" + approx + format(input) + " ↓" + approx + format(output);
    }

    /** Занятое окно по оценке, когда ответа в этом чате ещё не было. */
    public static String describeEstimate(long tokens, long window) {
        return "контекст ≈" + format(tokens) + (window > 0 ? " из " + format(window) : "");
    }

    /** Коротко: 950, 1,2k, 45k, 1,2M. */
    public static String format(long tokens) {
        if (tokens < 1000) {
            return Long.toString(tokens);
        }
        if (tokens < 10_000) {
            return String.format(Locale.ROOT, "%.1fk", tokens / 1000.0).replace('.', ',');
        }
        if (tokens < 1_000_000) {
            return tokens / 1000 + "k";
        }
        return String.format(Locale.ROOT, "%.1fM", tokens / 1_000_000.0).replace('.', ',');
    }
}
