package io.github.hansglorias.edtai.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ToolActivityTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final ToolActivity activity = new ToolActivity(now::get);

    @Test
    void activeOnlyDuringTrackedCallAndShortlyAfter() throws Exception {
        List<Boolean> during = new ArrayList<>();
        Tool mcp = tool("mcp_edit", true, () -> during.add(activity.active()));
        Tool read = tool("read_module", false, () -> during.add(activity.active()));
        List<Tool> tools = activity.track(List.of(mcp, read), Tool::modifies);

        assertSame(read, tools.get(1), "читающий — как есть");
        assertFalse(activity.active(), "до вызова изменения — не модели");
        tools.get(1).call(Map.of());
        assertFalse(activity.used());

        Tool tracked = tools.get(0);
        assertEquals("mcp_edit", tracked.name());
        assertTrue(tracked.modifies());
        tracked.call(Map.of());
        assertEquals(List.of(false, true), during);
        assertTrue(activity.used());

        now.addAndGet(ToolActivity.TAIL_MS);
        assertTrue(activity.active(), "EDT дописывает файлы сразу после вызова");
        now.addAndGet(1);
        assertFalse(activity.active());

        activity.reset();
        assertFalse(activity.used());
    }

    @Test
    void failedCallEndsToo() {
        Tool failing = tool("mcp_edit", true, () -> {
            throw new ToolException("нет");
        });
        Tool tracked = activity.track(List.of(failing), tool -> true).get(0);

        assertThrows(ToolException.class, () -> tracked.call(Map.of()));
        now.addAndGet(ToolActivity.TAIL_MS + 1);
        assertFalse(activity.active());
        assertTrue(activity.used());
    }

    private interface Body {
        void run() throws ToolException;
    }

    private static Tool tool(String name, boolean modifies, Body body) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public boolean modifies() {
                return modifies;
            }

            @Override
            public String summary(Map<String, Object> input) {
                return name;
            }

            @Override
            public Result call(Map<String, Object> input) throws ToolException {
                body.run();
                return new Result("", "");
            }
        };
    }
}
