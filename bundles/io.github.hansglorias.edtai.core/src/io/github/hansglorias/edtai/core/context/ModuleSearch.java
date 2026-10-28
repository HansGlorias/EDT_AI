package io.github.hansglorias.edtai.core.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Поиск модуля по строке из диалога «Прикрепить модуль…»: по имени объекта
 * и по пути. Каждое слово запроса (через пробел или точку) должно найтись
 * подстрокой или сокращением по началам слов: «ОбщМод» находит
 * «ОбщийМодуль», «спр ном» — «Справочник.Номенклатура». Регистр и «ё» не
 * важны.
 */
public final class ModuleSearch {

    private final List<String> tokens;

    public ModuleSearch(String query) {
        tokens = new ArrayList<>();
        for (String token : query.strip().split("[\\s.]+")) {
            if (!token.isEmpty() && !token.equals("*")) {
                tokens.add(token);
            }
        }
    }

    /** Пустой запрос подходит ко всему. */
    public boolean isEmpty() {
        return tokens.isEmpty();
    }

    /**
     * @param name имя модуля в нотации 1С, может быть пустым
     * @param path путь файла
     */
    public boolean matches(String name, String path) {
        String haystack = normalize(name + " " + path);
        List<String> words = words(name + "." + path.replace('/', '.'));
        for (String token : tokens) {
            if (!haystack.contains(normalize(token)) && !abbreviation(token, words)) {
                return false;
            }
        }
        return true;
    }

    /**
     * «ОбщМод»: части с заглавной буквы — начала подряд идущих слов
     * («Общий», «Модуль») в одном из имён.
     */
    static boolean abbreviation(String token, List<String> words) {
        List<String> parts = camelParts(token);
        if (parts.size() < 2) {
            return false;
        }
        for (int start = 0; start + parts.size() <= words.size(); start++) {
            boolean all = true;
            for (int i = 0; i < parts.size() && all; i++) {
                all = normalize(words.get(start + i)).startsWith(normalize(parts.get(i)));
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /** Слова имени: части через точку, внутри — по заглавным буквам. */
    static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        for (String part : name.split("[.\\s_/]+")) {
            words.addAll(camelParts(part));
        }
        return words;
    }

    private static List<String> camelParts(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 1; i < text.length(); i++) {
            if (Character.isUpperCase(text.charAt(i)) && !Character.isUpperCase(text.charAt(i - 1))) {
                parts.add(text.substring(start, i));
                start = i;
            }
        }
        if (start < text.length()) {
            parts.add(text.substring(start));
        }
        return parts;
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }
}
