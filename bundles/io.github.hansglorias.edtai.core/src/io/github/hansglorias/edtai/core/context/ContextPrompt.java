package io.github.hansglorias.edtai.core.context;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Текст сообщения пользователя для модели: прикреплённые фрагменты кода с
 * заголовками, затем вопрос. В ленте показывается только вопрос и подписи
 * фрагментов.
 */
public final class ContextPrompt {

    private static final Pattern BACKTICKS = Pattern.compile("`{3,}");

    private ContextPrompt() {
    }

    /** Вопрос с фрагментами перед ним; без фрагментов — вопрос как есть. */
    public static String compose(String question, List<ContextItem> items) {
        if (items.isEmpty()) {
            return question;
        }
        StringBuilder text = new StringBuilder();
        for (ContextItem item : items) {
            text.append(header(item)).append(":\n");
            if (item.lastLine() >= item.firstLine()) {
                String fence = fence(item.text());
                text.append(fence).append(item.language()).append('\n').append(item.text()).append('\n').append(fence).append('\n');
            }
            if (item.truncated()) {
                text.append(item.lastLine() < item.firstLine()
                        ? "(не поместилось в лимит контекста: строки " + ContextItem.lines(item.firstLine(), item.requestedLastLine())
                                + " из " + item.totalLines() + ")\n"
                        : "(обрезано по лимиту контекста: показаны строки " + ContextItem.lines(item.firstLine(), item.lastLine())
                                + ", не показаны " + ContextItem.lines(item.lastLine() + 1, item.requestedLastLine()) + " из "
                                + item.totalLines() + ")\n");
            }
            text.append('\n');
        }
        return text.append(question).toString();
    }

    /**
     * «Справочник.Номенклатура.МодульОбъекта (проект Демо, src/Catalogs/Номенклатура/ObjectModule.bsl), строки 10–25».
     */
    static String header(ContextItem item) {
        String where = "проект " + item.project() + ", " + item.path();
        String head = item.objectName().isEmpty() ? item.path() + " (проект " + item.project() + ")"
                : item.objectName() + " (" + where + ")";
        return head + (item.whole() ? ", весь модуль" : ", строки " + ContextItem.lines(item.firstLine(), item.requestedLastLine()));
    }

    /** Ограждение блока кода: на одну обратную кавычку длиннее самой длинной серии внутри кода, не короче трёх. */
    static String fence(String code) {
        int longest = 2;
        Matcher matcher = BACKTICKS.matcher(code);
        while (matcher.find()) {
            longest = Math.max(longest, matcher.group().length());
        }
        return "`".repeat(longest + 1);
    }

    /**
     * Обрезает фрагменты так, чтобы их текст вместе занимал не больше
     * {@code limit} символов: по порядку, целыми строками. Фрагмент, для
     * которого места не осталось, остаётся без строк — модель узнает, что он
     * был.
     */
    public static List<ContextItem> fit(List<ContextItem> items, int limit) {
        List<ContextItem> result = new ArrayList<>(items.size());
        int budget = Math.max(0, limit);
        for (ContextItem item : items) {
            ContextItem fitted = item.truncate(budget);
            budget = Math.max(0, budget - fitted.text().length() - 1);
            result.add(fitted);
        }
        return result;
    }
}
