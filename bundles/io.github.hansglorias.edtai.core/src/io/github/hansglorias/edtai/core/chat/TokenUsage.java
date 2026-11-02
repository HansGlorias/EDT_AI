package io.github.hansglorias.edtai.core.chat;

import java.util.Locale;

/** Токены шагов ответа и занятое окно последнего физического запроса. */
public record TokenUsage(long input, long output, long context,
        Source inputSource, Source outputSource, Source contextSource) {

    public TokenUsage {
        if (input < 0 || output < 0 || context < 0) throw new IllegalArgumentException("Отрицательное число токенов");
        java.util.Objects.requireNonNull(inputSource);
        java.util.Objects.requireNonNull(outputSource);
        java.util.Objects.requireNonNull(contextSource);
    }

    /** Происхождение поля; MIXED — сумма известных серверных чисел и оценок. */
    public enum Source { SERVER, ESTIMATE, UNKNOWN, MIXED }

    /** Совместимость с историей и существующими вызывающими сторонами. */
    public TokenUsage(long input, long output, long context, boolean estimated) {
        this(input, output, context, estimated ? Source.ESTIMATE : Source.SERVER,
                estimated ? Source.ESTIMATE : Source.SERVER, estimated ? Source.ESTIMATE : Source.SERVER);
    }

    public boolean estimated() {
        return inputSource != Source.SERVER || outputSource != Source.SERVER || contextSource != Source.SERVER;
    }

    public static TokenUsage step(long input, long output, boolean estimated) {
        return new TokenUsage(input, output, input + output, estimated);
    }

    /** Отрицательное число означает, что сервер не передал соответствующее поле. */
    public static TokenUsage serverStep(long input, long output) {
        return step(Math.max(0, input), Math.max(0, output),
                input < 0 ? Source.UNKNOWN : Source.SERVER, output < 0 ? Source.UNKNOWN : Source.SERVER);
    }

    public static TokenUsage step(long input, long output, Source inputSource, Source outputSource) {
        return new TokenUsage(input, output, input + output, inputSource, outputSource, combine(inputSource, outputSource));
    }

    private static Source combine(Source first, Source second) {
        if (first == Source.UNKNOWN || second == Source.UNKNOWN) return Source.UNKNOWN;
        return first == second ? first : Source.MIXED;
    }

    /** Заполнить только неизвестные поля явно обозначенной оценкой. */
    public TokenUsage fillMissing(TokenUsage estimate) {
        long in = inputSource == Source.UNKNOWN ? estimate.input : input;
        long out = outputSource == Source.UNKNOWN ? estimate.output : output;
        Source inSource = inputSource == Source.UNKNOWN ? estimate.inputSource : inputSource;
        Source outSource = outputSource == Source.UNKNOWN ? estimate.outputSource : outputSource;
        if (contextSource != Source.UNKNOWN) return new TokenUsage(in, out, context, inSource, outSource, contextSource);
        return step(in, out, inSource, outSource);
    }

    /** Сумма расхода шагов; окно и его источник относятся только к последнему шагу. */
    public TokenUsage plus(TokenUsage step) {
        return new TokenUsage(input + step.input, output + step.output, step.context,
                combine(inputSource, step.inputSource), combine(outputSource, step.outputSource), step.contextSource);
    }

    private static String describe(long value, Source source) {
        return source == Source.UNKNOWN ? "неизвестно" : (source == Source.SERVER ? "" : "≈") + format(value);
    }

    public String describe(long window) {
        return "контекст " + describe(context, contextSource) + (window > 0 ? " из " + format(window) : "")
                + " · ответ ↑" + describe(input, inputSource) + " ↓" + describe(output, outputSource);
    }

    public String describeStep() {
        return "↑" + describe(input, inputSource) + " ↓" + describe(output, outputSource);
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
