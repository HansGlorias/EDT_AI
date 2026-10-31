package io.github.hansglorias.edtai.core.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.hansglorias.edtai.core.json.Json;

/**
 * Находка ревью: где (проект, файл, строка), насколько серьёзно, что не так
 * и как исправить. Модель передаёт находки инструментом {@code report_review};
 * в ленте они — карточкой, в редакторе — маркерами.
 *
 * @param module имя модуля для подписи: «ОбщийМодуль.Цены»
 * @param line   строка, с 1
 * @param fix    как исправить, может быть пустым
 */
public record Finding(String project, String path, String module, int line, Severity severity, String title, String fix) {

    public enum Severity {
        ERROR("ошибка", "ошибки", "ошибок"),
        WARNING("замечание", "замечания", "замечаний"),
        INFO("совет", "совета", "советов");

        private final String one;
        private final String few;
        private final String many;

        Severity(String one, String few, String many) {
            this.one = one;
            this.few = few;
            this.many = many;
        }

        /** «2 ошибки», «5 замечаний», «1 совет». */
        public String count(long n) {
            long tens = n % 100;
            String word = tens >= 11 && tens <= 14 ? many : n % 10 == 1 ? one : n % 10 >= 2 && n % 10 <= 4 ? few : many;
            return n + " " + word;
        }

        /** Из ответа модели: error, warning, info; неизвестное — замечание. */
        public static Severity of(String value) {
            return switch (value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "error", "critical", "major", "ошибка" -> ERROR;
                case "info", "minor", "hint", "совет" -> INFO;
                default -> WARNING;
            };
        }
    }

    public Finding {
        project = Objects.requireNonNullElse(project, "");
        path = Objects.requireNonNullElse(path, "");
        module = Objects.requireNonNullElse(module, path);
        line = Math.max(1, line);
        severity = Objects.requireNonNullElse(severity, Severity.WARNING);
        title = Objects.requireNonNullElse(title, "").strip();
        fix = Objects.requireNonNullElse(fix, "").strip();
    }

    /** Итог: «2 ошибки · 3 замечания · 1 совет»; без находок — «замечаний нет». */
    public static String summary(List<Finding> findings) {
        List<String> parts = new ArrayList<>();
        for (Severity severity : Severity.values()) {
            long n = findings.stream().filter(finding -> finding.severity() == severity).count();
            if (n > 0) {
                parts.add(severity.count(n));
            }
        }
        return parts.isEmpty() ? "замечаний нет" : String.join(" · ", parts);
    }

    /** Находки по серьёзности (сначала ошибки), внутри — по модулю и строке. */
    public static List<Finding> sorted(List<Finding> findings) {
        return findings.stream().sorted(java.util.Comparator.comparing(Finding::severity).thenComparing(Finding::module)
                .thenComparingInt(Finding::line)).toList();
    }

    /** Для хранения в истории чата. */
    public static String toJson(List<Finding> findings) {
        List<Object> items = new ArrayList<>();
        for (Finding finding : findings) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("project", finding.project());
            item.put("path", finding.path());
            item.put("module", finding.module());
            item.put("line", finding.line());
            item.put("severity", finding.severity().name());
            item.put("title", finding.title());
            item.put("fix", finding.fix());
            items.add(item);
        }
        return Json.write(items);
    }

    public static List<Finding> fromJson(String json) {
        List<Finding> findings = new ArrayList<>();
        for (Object item : Json.asList(Json.parse(json))) {
            Severity severity;
            try {
                severity = Severity.valueOf(Json.stringAt(item, "severity"));
            } catch (RuntimeException e) {
                severity = Severity.WARNING;
            }
            findings.add(new Finding(Json.stringAt(item, "project"), Json.stringAt(item, "path"), Json.stringAt(item, "module"),
                    Json.at(item, "line") instanceof Number line ? line.intValue() : 1, severity, Json.stringAt(item, "title"),
                    Json.stringAt(item, "fix")));
        }
        return findings;
    }
}
