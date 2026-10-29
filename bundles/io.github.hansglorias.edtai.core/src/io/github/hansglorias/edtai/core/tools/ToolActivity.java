package io.github.hansglorias.edtai.core.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Когда идёт вызов инструмента, который может менять файлы мимо плагина
 * (инструменты MCP): изменения файлов в это время — правки модели, а в
 * остальное время ответа — пользователя или другого чата. Сразу после вызова
 * EDT ещё дописывает файлы — их тоже считаем.
 */
public final class ToolActivity {

    /** Сколько после вызова ещё ждать записи файлов. */
    public static final long TAIL_MS = 3_000;

    private final LongSupplier clock;
    private final AtomicInteger running = new AtomicInteger();
    private volatile long lastEnd = Long.MIN_VALUE;
    private volatile boolean used;

    public ToolActivity() {
        this(System::currentTimeMillis);
    }

    ToolActivity(LongSupplier clock) {
        this.clock = clock;
    }

    /** Инструменты, вызовы которых отслеживаются; остальные — как есть. */
    public List<Tool> track(List<Tool> tools, Predicate<Tool> tracked) {
        return tools.stream().map(tool -> tracked.test(tool) ? new Tracked(tool) : tool).toList();
    }

    /** Идёт отслеживаемый вызов или он только что закончился. */
    public boolean active() {
        return running.get() > 0 || lastEnd != Long.MIN_VALUE && clock.getAsLong() - lastEnd <= TAIL_MS;
    }

    /** С последнего {@link #reset()} был отслеживаемый вызов. */
    public boolean used() {
        return used;
    }

    /** Новый ответ. */
    public void reset() {
        used = false;
        lastEnd = Long.MIN_VALUE;
    }

    private final class Tracked implements Tool {

        private final Tool tool;

        Tracked(Tool tool) {
            this.tool = tool;
        }

        @Override
        public String name() {
            return tool.name();
        }

        @Override
        public String description() {
            return tool.description();
        }

        @Override
        public Map<String, Object> inputSchema() {
            return tool.inputSchema();
        }

        @Override
        public boolean needsApproval() {
            return tool.needsApproval();
        }

        @Override
        public boolean modifies() {
            return tool.modifies();
        }

        @Override
        public String summary(Map<String, Object> input) {
            return tool.summary(input);
        }

        @Override
        public Result call(Map<String, Object> input) throws ToolException {
            used = true;
            running.incrementAndGet();
            try {
                return tool.call(input);
            } finally {
                lastEnd = clock.getAsLong();
                running.decrementAndGet();
            }
        }
    }
}
