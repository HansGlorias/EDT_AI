package io.github.hansglorias.edtai.core.chat;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import io.github.hansglorias.edtai.core.json.Json;

/** Очистка известных секретов, адресов и полей авторизации. Не анонимизатор произвольного текста. */
public final class ExportRedactor {
    private static final String REMOVED = "[удалено]";
    private static final Pattern URL = Pattern.compile("(?i)[a-z][a-z0-9+.-]*://[^\\s<>\"'`]+|www\\.[^\\s<>\"'`]+");
    private static final Pattern AUTH = Pattern.compile(
            "(?im)[\"']?(?:authorization|proxy-authorization|x-api-key|api[_-]?key|access[_-]?token|token|password|cookie|set-cookie)[\"']?\\s*[:=]\\s*[^\\r\\n]+"
            + "|(?i)\\bBearer\\s+[a-z0-9._~+/=-]+");
    // Обычный объект, не record: секреты не попадают в автоматически созданный toString().
    private final List<String> literals;

    public ExportRedactor(List<String> secrets, List<String> endpoints) {
        List<String> values = new ArrayList<>();
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty()) {
                values.add(secret);
                values.add(URLEncoder.encode(secret, StandardCharsets.UTF_8));
                String json = Json.write(secret);
                values.add(json.substring(1, json.length() - 1));
            }
        }
        for (String endpoint : endpoints) {
            if (endpoint == null || endpoint.isBlank()) continue;
            values.add(endpoint);
            try {
                String host = URI.create(endpoint).getHost();
                if (host != null && !host.isEmpty()) values.add(host);
            } catch (IllegalArgumentException e) {
                // Даже некорректный адрес удаляется как известная строка.
            }
        }
        literals = values.stream().distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    public String clean(String value) {
        String text = value == null ? "" : value;
        // Сначала целые URL/заголовки: замена токена не должна оставить хвост адреса.
        text = URL.matcher(text).replaceAll(REMOVED);
        text = AUTH.matcher(text).replaceAll(REMOVED);
        for (String literal : literals) text = text.replace(literal, REMOVED);
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
