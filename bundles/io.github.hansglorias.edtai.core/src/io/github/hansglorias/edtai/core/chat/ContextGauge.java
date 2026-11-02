package io.github.hansglorias.edtai.core.chat;

/**
 * Сколько окна модели занято: для кольца у кнопки модели.
 *
 * @param used      занято, токенов
 * @param window    окно модели, токенов; больше нуля
 * @param estimated число — оценка, а не данные сервера
 */
public record ContextGauge(long used, long window, boolean estimated) {

    /** Уровень заполнения: от него цвет кольца. */
    public enum Level { LOW, MEDIUM, HIGH }

    /** С этой доли — жёлтый: история скоро начнёт сжиматься. */
    public static final double MEDIUM_FROM = 0.5;
    /** С этой доли — красный: старые шаги и сообщения уже могут не уходить модели. */
    public static final double HIGH_FROM = 0.8;

    public ContextGauge {
        if (window <= 0) {
            throw new IllegalArgumentException("Окно модели неизвестно");
        }
        used = Math.max(0, used);
    }

    /** Доля от 0 до 1; больше окна — 1. */
    public double ratio() {
        return Math.min(1.0, (double) used / window);
    }

    public int percent() {
        return (int) Math.round(ratio() * 100);
    }

    public Level level() {
        double ratio = ratio();
        return ratio >= HIGH_FROM ? Level.HIGH : ratio >= MEDIUM_FROM ? Level.MEDIUM : Level.LOW;
    }

    /** «Контекст ≈45k из 128k (35%)». */
    public String describe() {
        return "Контекст " + (estimated ? "≈" : "") + TokenUsage.format(used) + " из " + TokenUsage.format(window) + " (" + percent()
                + "%)";
    }
}
