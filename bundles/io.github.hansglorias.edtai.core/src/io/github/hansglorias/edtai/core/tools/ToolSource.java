package io.github.hansglorias.edtai.core.tools;

import java.util.List;
import java.util.function.Consumer;

import io.github.hansglorias.edtai.core.chat.ToolUse;

/**
 * Инструменты для очередного ответа. Вызывается в фоновом потоке ответа:
 * подключение к MCP-серверам может занять время.
 */
@FunctionalInterface
public interface ToolSource {

    ToolSource NONE = problems -> List.of();

    /**
     * @param problems получает строки для ленты о серверах, которые не подключились; ответ при этом продолжается
     */
    List<Tool> tools(Consumer<ToolUse> problems);

    /**
     * Сведения о проекте для системного промпта: см. {@link ProjectBrief}.
     * Вызывается перед каждым ответом, в UI-потоке; пустая строка — нет.
     */
    default String brief() {
        return "";
    }
}
