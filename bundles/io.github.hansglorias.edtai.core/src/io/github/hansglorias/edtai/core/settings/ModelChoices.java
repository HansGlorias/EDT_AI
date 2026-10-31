package io.github.hansglorias.edtai.core.settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Модели для выбора в поле ввода: что отдаёт сервер ({@code /models}), что
 * выбирали недавно (сервер может не отдавать список) и текущая.
 */
public final class ModelChoices {

    /** Сколько недавних моделей помнить. */
    public static final int RECENT = 8;

    private ModelChoices() {
    }

    /**
     * Текущая, недавние, затем остальные с сервера по алфавиту; без повторов
     * и пустых. Если сервер отдал список, недавних, которых в нём нет (с
     * другого сервера), не показываем.
     */
    public static List<String> merge(String current, List<String> recent, List<String> server) {
        Set<String> result = new LinkedHashSet<>();
        add(result, current);
        recent.stream().filter(name -> server.isEmpty() || server.contains(name)).forEach(name -> add(result, name));
        List<String> rest = new ArrayList<>(server);
        rest.sort(String.CASE_INSENSITIVE_ORDER);
        rest.forEach(name -> add(result, name));
        return List.copyOf(result);
    }

    /** Недавние после выбора {@code model}: она первая, старые вытесняются. */
    public static List<String> remember(List<String> recent, String model) {
        Set<String> result = new LinkedHashSet<>();
        add(result, model);
        recent.forEach(name -> add(result, name));
        return result.stream().limit(RECENT).toList();
    }

    /** Недавние: строки через перевод строки, как хранятся в настройках. */
    public static List<String> parse(String stored) {
        Set<String> result = new LinkedHashSet<>();
        for (String line : (stored == null ? "" : stored).split("\n")) {
            add(result, line);
        }
        return result.stream().limit(RECENT).toList();
    }

    /**
     * Ключ настроек недавних моделей сервера {@code baseUrl}: у каждого
     * сервера свои. Без «/» — это разделитель узлов настроек Eclipse.
     */
    public static String recentKey(String prefix, String baseUrl) {
        String server = (baseUrl == null ? "" : baseUrl).strip().replaceFirst("^(?i)https?://", "").replaceAll("/+$", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.:_-]", "_");
        return server.isEmpty() ? prefix : prefix + "@" + server;
    }

    public static String format(List<String> recent) {
        return String.join("\n", recent);
    }

    /**
     * Модель по тому, что набрано после {@code /model}: точное имя, иначе
     * единственная, в имени которой есть набранное (без учёта регистра).
     */
    public static Optional<String> find(String typed, List<String> choices) {
        String wanted = typed.strip();
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        for (String name : choices) {
            if (name.equals(wanted)) {
                return Optional.of(name);
            }
        }
        String lower = wanted.toLowerCase(Locale.ROOT);
        List<String> found = choices.stream().filter(name -> name.toLowerCase(Locale.ROOT).contains(lower)).toList();
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /** Подпись на кнопке: без префикса поставщика ({@code openai/}), длинное — с многоточием. */
    public static String label(String model, int max) {
        String name = model.strip();
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) {
            name = name.substring(slash + 1);
        }
        return name.length() <= max ? name : name.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static void add(Set<String> result, String name) {
        if (name != null && !name.isBlank()) {
            result.add(name.strip());
        }
    }
}
