package io.github.hansglorias.edtai.core.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Границы процедур и функций в тексте модуля BSL: чтобы без выделения
 * прикрепить к вопросу метод под курсором. Ключевые слова внутри строк и
 * комментариев не считаются.
 */
public final class BslMethods {

    /**
     * Метод модуля.
     *
     * @param name      имя процедуры или функции
     * @param firstLine первая строка, с номера 0: комментарий над методом или аннотация, если есть
     * @param lastLine  последняя строка: {@code КонецПроцедуры} / {@code КонецФункции};
     *                  у незакрытого метода — строка перед следующим методом или последняя строка текста
     */
    public record Method(String name, int firstLine, int lastLine) {
    }

    private static final Pattern START = Pattern.compile(
            "^\\s*(?:(?:Асинх|Async)\\s+)?(?:Процедура|Функция|Procedure|Function)\\s+([\\p{L}_][\\p{L}\\p{N}_]*)\\s*\\(",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern END = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])(?:КонецПроцедуры|КонецФункции|EndProcedure|EndFunction)(?![\\p{L}\\p{N}_])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private BslMethods() {
    }

    /** Все методы модуля по порядку. */
    public static List<Method> methods(String text) {
        List<String> lines = lines(text);
        List<String> code = code(lines);
        List<Method> methods = new ArrayList<>();
        int i = 0;
        while (i < code.size()) {
            Matcher start = START.matcher(code.get(i));
            if (!start.find()) {
                i++;
                continue;
            }
            int end = i;
            int next = -1;
            while (end < code.size() && !END.matcher(end == i ? code.get(end).substring(start.end()) : code.get(end)).find()) {
                if (end > i && START.matcher(code.get(end)).find()) {
                    next = end;
                    break;
                }
                end++;
            }
            int last;
            if (next >= 0) {
                // Аннотации и комментарий следующего метода — уже его.
                last = previousNonBlank(lines, header(lines, code, next) - 1, i);
            } else if (end >= code.size()) {
                last = previousNonBlank(lines, code.size() - 1, i);
            } else {
                last = end;
            }
            methods.add(new Method(start.group(1), header(lines, code, i), last));
            i = next >= 0 ? next : last + 1;
        }
        return methods;
    }

    /**
     * @param line номер строки с 0
     * @return метод, которому принадлежит строка (в том числе комментарий над ним); пусто — строка вне методов
     */
    public static Optional<Method> enclosing(String text, int line) {
        return methods(text).stream().filter(method -> line >= method.firstLine() && line <= method.lastLine()).findFirst();
    }

    /** Аннотации и комментарий непосредственно над методом — часть метода. */
    private static int header(List<String> lines, List<String> code, int start) {
        int first = start;
        while (first > 0) {
            String above = lines.get(first - 1).strip();
            String aboveCode = code.get(first - 1).strip();
            boolean annotation = aboveCode.startsWith("&");
            boolean comment = aboveCode.isEmpty() && above.startsWith("//");
            if (!annotation && !comment) {
                break;
            }
            first--;
        }
        return first;
    }

    private static int previousNonBlank(List<String> lines, int from, int min) {
        int line = from;
        while (line > min && lines.get(line).isBlank()) {
            line--;
        }
        return line;
    }

    static List<String> lines(String text) {
        return List.of(text.split("\r\n|\r|\n", -1));
    }

    /**
     * Строки без комментариев и содержимого строковых литералов: литерал
     * заменяется пробелами той же длины. Литерал может продолжаться на
     * следующих строках (строка продолжения начинается с {@code |}).
     */
    static List<String> code(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        boolean inString = false;
        for (String line : lines) {
            StringBuilder out = new StringBuilder(line.length());
            int i = 0;
            if (inString) {
                // Продолжение многострочного литерала: |текст"
                int bar = firstNonSpace(line);
                if (bar < 0 || line.charAt(bar) != '|') {
                    inString = false;
                } else {
                    out.append(" ".repeat(bar + 1));
                    i = bar + 1;
                }
            }
            while (i < line.length()) {
                char c = line.charAt(i);
                if (inString) {
                    if (c == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                            out.append("  ");
                            i += 2;
                            continue;
                        }
                        inString = false;
                        out.append('"');
                    } else {
                        out.append(' ');
                    }
                    i++;
                } else if (c == '"') {
                    inString = true;
                    out.append('"');
                    i++;
                } else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    break;
                } else if (c == '\'') {
                    int close = line.indexOf('\'', i + 1);
                    int to = close < 0 ? line.length() : close + 1;
                    out.append(" ".repeat(to - i));
                    i = to;
                } else {
                    out.append(c);
                    i++;
                }
            }
            result.add(out.toString());
        }
        return result;
    }

    private static int firstNonSpace(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (!Character.isWhitespace(line.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
