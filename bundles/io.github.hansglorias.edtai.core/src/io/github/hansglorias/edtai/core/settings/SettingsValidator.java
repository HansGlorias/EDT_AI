package io.github.hansglorias.edtai.core.settings;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Проверка настроек перед сохранением. Возвращает список ошибок на русском,
 * пустой список — настройки корректны. Пустые адрес и модель допустимы:
 * плагин просто ещё не настроен.
 */
public final class SettingsValidator {

    private SettingsValidator() {
    }

    public static List<String> validate(AiSettings settings) {
        List<String> errors = new ArrayList<>();
        if (!settings.baseUrl().isEmpty()) {
            String error = checkHttpUrl(settings.baseUrl());
            if (error != null) {
                errors.add("Адрес сервера: " + error);
            }
        }
        if (!settings.caCertificateFile().isEmpty()) {
            String error = checkReadableFile(settings.caCertificateFile());
            if (error != null) {
                errors.add("Файл сертификатов: " + error);
            }
        }
        errors.addAll(validateMcpServers(settings.mcpServers()));
        return errors;
    }

    public static List<String> validateMcpServers(List<McpServer> servers) {
        List<String> errors = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (McpServer server : servers) {
            errors.addAll(validateMcpServer(server));
            if (!server.name().isEmpty() && !names.add(server.name().toLowerCase(Locale.ROOT))) {
                errors.add("MCP-сервер «" + server.name() + "» указан дважды");
            }
        }
        return errors;
    }

    public static List<String> validateMcpServer(McpServer server) {
        List<String> errors = new ArrayList<>();
        String title = server.name().isEmpty() ? "MCP-сервер без имени" : "MCP-сервер «" + server.name() + "»";
        if (server.name().isEmpty()) {
            errors.add(title + ": не задано имя");
        }
        if (server.target().isEmpty()) {
            errors.add(title + ": " + (server.transport() == McpTransport.STDIO ? "не задана команда" : "не задан URL"));
        } else if (server.transport() == McpTransport.HTTP) {
            String error = checkHttpUrl(server.target());
            if (error != null) {
                errors.add(title + ": " + error);
            }
        }
        return errors;
    }

    /** @return текст ошибки или {@code null}, если адрес корректен */
    public static String checkHttpUrl(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return "некорректный URL";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "нужен адрес вида http://… или https://…";
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            return "в адресе нет имени хоста";
        }
        return null;
    }

    private static String checkReadableFile(String value) {
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException e) {
            return "некорректный путь";
        }
        if (!Files.isRegularFile(path)) {
            return "файл не найден";
        }
        if (!Files.isReadable(path)) {
            return "нет прав на чтение";
        }
        return null;
    }
}
