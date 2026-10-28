package io.github.hansglorias.edtai.core.tools;

import java.util.Map;

/**
 * Инструмент, который модель может вызвать: описание и схема аргументов
 * уходят модели, вызов выполняется у нас.
 */
public interface Tool {

    /** Имя для модели: латиница и подчёркивания. */
    String name();

    /** Что делает инструмент и когда его вызывать — для модели. */
    String description();

    /** JSON Schema аргументов ({@code {"type":"object","properties":{…}}}). */
    Map<String, Object> inputSchema();

    /**
     * Спрашивать ли пользователя перед вызовом. Встроенные инструменты только
     * читают проект — без подтверждения; инструменты MCP-серверов могут
     * менять проект — с подтверждением.
     */
    default boolean needsApproval() {
        return false;
    }

    /**
     * Меняет проект (модули, метаданные, файлы): в режиме «только вопросы»
     * такие инструменты модели не передаются.
     */
    default boolean modifies() {
        return false;
    }

    /** Что делается — для ленты, по-русски: «Читает модуль ОбщийМодуль.Х.Модуль». */
    String summary(Map<String, Object> input);

    /**
     * Выполняет вызов. Вызывается из фонового потока; прерывание потока
     * означает, что ответ больше не нужен.
     *
     * @throws ToolException с текстом для модели и пользователя
     */
    Result call(Map<String, Object> input) throws ToolException;

    /**
     * @param text    результат для модели
     * @param outcome итог коротко для ленты: «строки 1–120 из 120»
     */
    record Result(String text, String outcome) {
    }
}
