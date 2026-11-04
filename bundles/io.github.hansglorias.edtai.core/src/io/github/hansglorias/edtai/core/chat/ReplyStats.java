package io.github.hansglorias.edtai.core.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Сколько шёл ответ и на что ушло время: ожидание модели, рассуждение, текст, инструменты; токены
 * всего ответа (со специалистами) и длительность каждого вызова по его id.
 *
 * @param started   когда ответ начался — отправка вопроса
 * @param finished  когда закончился; {@code null} — ещё идёт
 * @param waiting   мс ожидания модели без видимого вывода (подготовка запроса и скрытое рассуждение)
 * @param reasoning мс, пока модель присылала рассуждение
 * @param writing   мс, пока модель писала текст ответа
 * @param tools     мс выполнения инструментов и ожидания решений пользователя
 * @param tokens    токены всего ответа; {@code null} — не известно
 * @param calls     длительность завершённых вызовов инструментов, мс, по id вызова
 * @param active    когда начались ещё идущие вызовы, по id; в историю не пишется
 */
public record ReplyStats(Instant started, Instant finished, long waiting, long reasoning, long writing, long tools,
        TokenUsage tokens, Map<String, Long> calls, Map<String, Instant> active) {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public ReplyStats {
        Objects.requireNonNull(started, "started");
        if (waiting < 0 || reasoning < 0 || writing < 0 || tools < 0) {
            throw new IllegalArgumentException("Отрицательная длительность");
        }
        calls = Map.copyOf(Objects.requireNonNullElse(calls, Map.of()));
        active = Map.copyOf(Objects.requireNonNullElse(active, Map.of()));
    }

    public ReplyStats(Instant started, Instant finished, long waiting, long reasoning, long writing, long tools,
            TokenUsage tokens, Map<String, Long> calls) {
        this(started, finished, waiting, reasoning, writing, tools, tokens, calls, Map.of());
    }

    /** Ответ закончился (готов, остановлен или с ошибкой). */
    public boolean finishedReply() {
        return finished != null;
    }

    /** Весь ответ, мс; пока идёт — до {@code now}. */
    public long total(Instant now) {
        Instant end = finished != null ? finished : now;
        return Math.max(0, Duration.between(started, end).toMillis());
    }

    /** Модель думала: ожидание без вывода и рассуждение. */
    public long thinking() {
        return waiting + reasoning;
    }

    /** Длительность вызова инструмента по id: завершённого — итог, идущего — до {@code now}; -1 — не известна. */
    public long call(String id, Instant now) {
        Long millis = calls.get(id);
        if (millis != null) {
            return millis;
        }
        Instant start = active.get(id);
        return start == null ? -1 : Math.max(0, Duration.between(start, now).toMillis());
    }

    /** Коротко под ответом: «4 мин 12 с · думала 3 мин · ↑45k ↓2,1k». */
    public String summary() {
        List<String> parts = new ArrayList<>();
        long total = total(Instant.now());
        parts.add(duration(total));
        if (thinking() >= 1000 && thinking() < total) {
            parts.add("думала " + duration(thinking()));
        }
        if (tokens != null) {
            parts.add(tokens.describeStep());
        }
        return String.join(" · ", parts);
    }

    /** Строка с долей фазы: подпись, длительность и доля от всего ответа. */
    public record Line(String label, long millis, double share, String note) {
    }

    /** Фазы для карточки ответа: только ненулевые, по порядку хода ответа. */
    public List<Line> phases(int calls) {
        long total = Math.max(1, total(Instant.now()));
        List<Line> lines = new ArrayList<>();
        add(lines, "Ожидание модели", waiting, total, "");
        add(lines, "Рассуждение", reasoning, total, "");
        add(lines, "Текст ответа", writing, total, "");
        add(lines, "Инструменты", tools, total, calls > 0 ? "вызовов: " + calls : "");
        return lines;
    }

    private static void add(List<Line> lines, String label, long millis, long total, String note) {
        if (millis > 0) {
            lines.add(new Line(label, millis, Math.min(1.0, (double) millis / total), note));
        }
    }

    /** Полоса доли: «█████░░░░░» шириной {@code width}; ненулевая доля — хотя бы одно деление. */
    public static String bar(double share, int width) {
        int filled = (int) Math.round(Math.max(0, Math.min(1, share)) * width);
        if (share > 0 && filled == 0) {
            filled = 1;
        }
        return "█".repeat(filled) + "░".repeat(width - filled);
    }

    /** «25%»; меньше процента — «<1%». */
    public static String percent(double share) {
        long value = Math.round(share * 100);
        return share > 0 && value == 0 ? "<1%" : value + "%";
    }

    /** Подсказка над итогом ответа: начало и конец, фазы, токены. */
    public String hint(int calls) {
        StringBuilder text = new StringBuilder("Ответ " + span() + " — " + duration(total(Instant.now())));
        for (Line line : phases(calls)) {
            text.append('\n').append(line.label()).append(": ").append(duration(line.millis()))
                    .append(line.note().isEmpty() ? "" : " (" + line.note() + ")");
        }
        if (tokens != null) {
            text.append("\nТокены: ").append(tokens.describeStep());
        }
        return text.toString();
    }

    /** «14:02:11 → 14:06:23». */
    public String span() {
        return CLOCK.format(started) + (finished == null ? " → …" : " → " + CLOCK.format(finished));
    }

    /** «950 мс», «12 с», «4 мин 12 с», «1 ч 5 мин». */
    public static String duration(long millis) {
        if (millis < 0) {
            return "";
        }
        if (millis < 1000) {
            return millis + " мс";
        }
        long seconds = millis / 1000;
        if (seconds < 10 && millis % 1000 >= 100) {
            return seconds + "," + (millis % 1000) / 100 + " с";
        }
        if (seconds < 60) {
            return seconds + " с";
        }
        if (seconds < 3600) {
            return seconds / 60 + " мин" + (seconds % 60 == 0 ? "" : " " + seconds % 60 + " с");
        }
        return seconds / 3600 + " ч" + (seconds % 3600 / 60 == 0 ? "" : " " + seconds % 3600 / 60 + " мин");
    }

    /**
     * Часы ответа: копит длительность фаз {@link ReplyActivity.Phase} и вызовов. Не потокобезопасны —
     * вызывающий держит свою блокировку.
     */
    public static final class Clock {
        private final Instant started;
        private ReplyActivity.Phase phase;
        private Instant since;
        private long waiting;
        private long reasoning;
        private long writing;
        private long tools;
        private final Map<String, Instant> running = new LinkedHashMap<>();
        private final Map<String, Long> calls = new LinkedHashMap<>();
        private TokenUsage tokens;
        private Instant finished;

        public Clock(Instant started) {
            this.started = started;
            this.phase = ReplyActivity.Phase.PREPARING;
            this.since = started;
        }

        /** Началась фаза; время прежней прибавляется к её счётчику. */
        public void enter(ReplyActivity.Phase next, Instant now) {
            close(now);
            phase = next;
        }

        private void close(Instant now) {
            long millis = Math.max(0, Duration.between(since, now).toMillis());
            since = now;
            switch (phase) {
                case PREPARING, THINKING -> waiting += millis;
                case REASONING -> reasoning += millis;
                case WRITING -> writing += millis;
                case TOOL -> tools += millis;
            }
        }

        /** Вызов сменил состояние: выполняется — запоминаем начало, завершён — длительность. */
        public void call(ToolUse use, Instant now) {
            if (use.id().isEmpty()) {
                return;
            }
            switch (use.status()) {
                case RUNNING -> running.putIfAbsent(use.id(), now);
                case AWAITING -> running.remove(use.id());
                case DONE, FAILED -> {
                    Instant start = running.remove(use.id());
                    if (start != null) {
                        calls.put(use.id(), Math.max(0, Duration.between(start, now).toMillis()));
                    }
                }
            }
        }

        public void tokens(TokenUsage value) {
            if (value != null) {
                tokens = value;
            }
        }

        public void finish(Instant now) {
            if (finished == null) {
                close(now);
                running.clear();
                finished = now;
            }
        }

        public ReplyStats snapshot() {
            return new ReplyStats(started, finished, waiting, reasoning, writing, tools, tokens, calls, finished == null ? running : Map.of());
        }
    }
}
