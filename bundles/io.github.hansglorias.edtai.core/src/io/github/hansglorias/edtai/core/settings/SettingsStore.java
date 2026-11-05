package io.github.hansglorias.edtai.core.settings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.osgi.service.prefs.BackingStoreException;

import io.github.hansglorias.edtai.core.chat.ChatModel;
import io.github.hansglorias.edtai.core.json.Json;

/**
 * Чтение и запись настроек. Обычные настройки лежат в preferences рабочей
 * области, токен — в Eclipse Secure Storage и больше нигде.
 */
public final class SettingsStore {

    public static final String QUALIFIER = "io.github.hansglorias.edtai.core";

    public static final String KEY_PROVIDERS = "apiProviders";
    public static final String KEY_ACTIVE_PROVIDER = "activeApiProvider";

    static final String KEY_PROTOCOL = "protocol";
    static final String KEY_BASE_URL = "baseUrl";
    static final String KEY_MODEL = "model";
    static final String KEY_MAX_TOKENS = "maxTokens";
    static final String KEY_SYSTEM_PROMPT = "systemPrompt";
    static final String KEY_MODEL_WINDOW = "modelWindow";
    static final String KEY_CONTEXT_LIMIT = "contextLimit";
    static final String KEY_TOOLS_ENABLED = "toolsEnabled";
    static final String KEY_USE_SYSTEM_TRUST_STORE = "useSystemTrustStore";
    static final String KEY_CA_CERTIFICATE_FILE = "caCertificateFile";
    static final String KEY_MCP_SERVERS = "mcpServers";
    static final String KEY_MCP_CHECK_MINUTES = "mcpCheckMinutes";

    static final String SECURE_NODE = "io.github.hansglorias.edtai";
    static final String SECURE_KEY_TOKEN = "token";

    private final IEclipsePreferences preferences;
    private final ISecurePreferences secureRoot;

    public SettingsStore(IEclipsePreferences preferences, ISecurePreferences secureRoot) {
        this.preferences = preferences;
        this.secureRoot = secureRoot;
    }

    /** Хранилище рабочей области и Secure Storage пользователя по умолчанию. */
    public static SettingsStore workspace() {
        return new SettingsStore(InstanceScope.INSTANCE.getNode(QUALIFIER), null);
    }

    public AiSettings load() {
        AiSettings shared = loadLegacy();
        if (preferences.get(KEY_PROVIDERS, "").isEmpty()) return shared;
        return provider(activeProviderId()).apply(shared);
    }

    private AiSettings loadLegacy() {
        List<McpServer> servers;
        try {
            servers = McpServersCodec.decode(preferences.get(KEY_MCP_SERVERS, ""));
        } catch (IllegalArgumentException e) {
            Platform.getLog(SettingsStore.class).error("Не удалось прочитать список MCP-серверов, он будет пустым", e);
            servers = List.of();
        }
        return new AiSettings(
                Protocol.parse(preferences.get(KEY_PROTOCOL, null)),
                preferences.get(KEY_BASE_URL, ""),
                preferences.get(KEY_MODEL, ""),
                preferences.getInt(KEY_MAX_TOKENS, 0),
                preferences.get(KEY_SYSTEM_PROMPT, AiSettings.DEFAULT_SYSTEM_PROMPT),
                preferences.getInt(KEY_CONTEXT_LIMIT, 0),
                preferences.getBoolean(KEY_TOOLS_ENABLED, AiSettings.DEFAULT_TOOLS_ENABLED),
                preferences.getBoolean(KEY_USE_SYSTEM_TRUST_STORE, AiSettings.DEFAULT_USE_SYSTEM_TRUST_STORE),
                preferences.get(KEY_CA_CERTIFICATE_FILE, ""),
                servers,
                preferences.getInt(KEY_MODEL_WINDOW, 0),
                preferences.getInt(KEY_MCP_CHECK_MINUTES, AiSettings.DEFAULT_MCP_CHECK_MINUTES));
    }

    public void save(AiSettings settings) throws BackingStoreException {
        if (!settings.providerId().isEmpty()) {
            List<ApiProvider> providers = new ArrayList<>(providers());
            ApiProvider before = provider(settings.providerId());
            providers.set(providers.indexOf(before), ApiProvider.of(before.id(), before.name(), settings));
            saveProviders(providers, before.id());
        }
        preferences.put(KEY_PROTOCOL, settings.protocol().name());
        put(KEY_BASE_URL, settings.baseUrl());
        put(KEY_MODEL, settings.model());
        if (settings.maxTokens() > 0) {
            preferences.putInt(KEY_MAX_TOKENS, settings.maxTokens());
        } else {
            preferences.remove(KEY_MAX_TOKENS);
        }
        // Промпт по умолчанию не сохраняем, чтобы его улучшения доходили до пользователя;
        // пустой сохраняем явно — это «без системного промпта».
        if (settings.systemPrompt().equals(AiSettings.DEFAULT_SYSTEM_PROMPT)) {
            preferences.remove(KEY_SYSTEM_PROMPT);
        } else {
            preferences.put(KEY_SYSTEM_PROMPT, settings.systemPrompt());
        }
        if (settings.contextLimit() == AiSettings.DEFAULT_CONTEXT_LIMIT) {
            preferences.remove(KEY_CONTEXT_LIMIT);
        } else {
            preferences.putInt(KEY_CONTEXT_LIMIT, settings.contextLimit());
        }
        if (settings.toolsEnabled() == AiSettings.DEFAULT_TOOLS_ENABLED) {
            preferences.remove(KEY_TOOLS_ENABLED);
        } else {
            preferences.putBoolean(KEY_TOOLS_ENABLED, settings.toolsEnabled());
        }
        if (settings.modelWindow() == AiSettings.AUTO_MODEL_WINDOW) {
            preferences.remove(KEY_MODEL_WINDOW);
        } else {
            preferences.putInt(KEY_MODEL_WINDOW, settings.modelWindow());
        }
        preferences.putBoolean(KEY_USE_SYSTEM_TRUST_STORE, settings.useSystemTrustStore());
        put(KEY_CA_CERTIFICATE_FILE, settings.caCertificateFile());
        put(KEY_MCP_SERVERS, McpServersCodec.encode(settings.mcpServers()));
        if (settings.mcpCheckMinutes() == AiSettings.DEFAULT_MCP_CHECK_MINUTES) preferences.remove(KEY_MCP_CHECK_MINUTES);
        else preferences.putInt(KEY_MCP_CHECK_MINUTES, settings.mcpCheckMinutes());
        preferences.flush();
    }

    /**
     * Есть ли сохранённый токен. Не расшифровывает Secure Storage, поэтому не
     * спрашивает мастер-пароль.
     */
    public boolean hasToken() {
        return hasToken(load().providerId());
    }

    public boolean hasToken(String providerId) {
        ISecurePreferences node = secureNode(providerId);
        return Arrays.asList(node.keys()).contains(SECURE_KEY_TOKEN);
    }

    /** @return токен или пустая строка */
    public String loadToken() throws StorageException {
        return loadToken(load().providerId());
    }

    public String loadToken(String providerId) throws StorageException {
        return secureNode(providerId).get(SECURE_KEY_TOKEN, "");
    }

    /** Сохраняет токен в зашифрованном виде; пустой токен удаляет сохранённый. */
    public void saveToken(String token) throws StorageException, IOException {
        saveToken(load().providerId(), token);
    }

    public void saveToken(String providerId, String token) throws StorageException, IOException {
        ISecurePreferences node = secureNode(providerId);
        if (token == null || token.isEmpty()) {
            node.remove(SECURE_KEY_TOKEN);
        } else {
            node.put(SECURE_KEY_TOKEN, token, true);
        }
        node.flush();
    }

    public String activeProviderId() {
        return preferences.get(KEY_ACTIVE_PROVIDER, ApiProvider.DEFAULT_ID);
    }

    /** Пока каталог не сохранён, старое подключение представляется первым профилем. */
    public List<ApiProvider> providers() {
        String value = preferences.get(KEY_PROVIDERS, "");
        if (value.isEmpty()) return List.of(ApiProvider.of(ApiProvider.DEFAULT_ID, "Основной", loadLegacy()));
        Object entries = Json.parse(value);
        if (!(entries instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("Повреждён список API-провайдеров");
        }
        List<ApiProvider> result = new ArrayList<>();
        for (Object entry : list) {
            result.add(new ApiProvider(Json.stringAt(entry, "id"), Json.stringAt(entry, "name"),
                    Protocol.parse(Json.stringAt(entry, "protocol")), Json.stringAt(entry, "baseUrl"),
                    Json.stringAt(entry, "model"), integer(entry, "maxTokens"),
                    integer(entry, "modelWindow"), !Boolean.FALSE.equals(Json.at(entry, "useSystemTrustStore")),
                    Json.stringAt(entry, "caCertificateFile")));
        }
        return List.copyOf(result);
    }

    private static int integer(Object entry, String key) {
        return Json.at(entry, key) instanceof Number number ? number.intValue() : 0;
    }

    public ApiProvider provider(String id) {
        String key = id == null || id.isEmpty() ? activeProviderId() : id;
        return providers().stream().filter(each -> each.id().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Провайдер удалён. Выберите подключение в меню модели."));
    }

    public AiSettings forChat(ChatModel model) {
        AiSettings shared = load();
        if (model.providerId().isEmpty()) {
            if (model.isEmpty() || shared.providerId().isEmpty()) return model.apply(shared);
            ApiProvider original = providers().stream().filter(each -> each.id().equals(ApiProvider.DEFAULT_ID)).findFirst()
                    .orElseGet(() -> ApiProvider.of(ApiProvider.DEFAULT_ID, "Удалённый провайдер", AiSettings.defaults()));
            return original.baseUrl().isEmpty() ? original.apply(shared) : model.apply(original.apply(shared));
        }
        ApiProvider selected = providers().stream().filter(each -> each.id().equals(model.providerId())).findFirst()
                .orElseGet(() -> ApiProvider.of(model.providerId(), "Удалённый провайдер", AiSettings.defaults()));
        // Удалённое подключение не заменяется другим: история не должна незаметно уйти на другой сервер.
        if (selected.baseUrl().isEmpty()) return selected.apply(shared);
        return model.apply(selected.apply(shared));
    }

    /** Один JSON каталога, без токенов. Чтение секретов при сохранении не требуется. */
    public void saveProviders(List<ApiProvider> providers, String activeId) throws BackingStoreException {
        if (providers.isEmpty() || providers.stream().map(ApiProvider::id).distinct().count() != providers.size()
                || providers.stream().noneMatch(each -> each.id().equals(activeId))) {
            throw new IllegalArgumentException("Нужен непустой список уникальных провайдеров и выбранный профиль");
        }
        String value = Json.write(providers.stream().map(each -> Json.object(
                "id", each.id(), "name", each.name(), "protocol", each.protocol().name(), "baseUrl", each.baseUrl(),
                "model", each.model(), "maxTokens", each.maxTokens(), "modelWindow", each.modelWindow(),
                "useSystemTrustStore", each.useSystemTrustStore(), "caCertificateFile", each.caCertificateFile())).toList());
        preferences.put(KEY_PROVIDERS, value);
        preferences.put(KEY_ACTIVE_PROVIDER, activeId);
        preferences.flush();
    }

    private void put(String key, String value) {
        if (value.isEmpty()) {
            preferences.remove(key);
        } else {
            preferences.put(key, value);
        }
    }

    private ISecurePreferences secureNode(String providerId) {
        ISecurePreferences root = secureRoot != null ? secureRoot : SecurePreferencesFactory.getDefault();
        if (root == null) {
            throw new IllegalStateException("Eclipse Secure Storage недоступно");
        }
        if (providerId == null || providerId.isEmpty() || providerId.equals(ApiProvider.DEFAULT_ID)) {
            // Прежний токен уже зашифрован здесь: не переносим и не расшифровываем при миграции.
            return root.node(SECURE_NODE);
        }
        if (!providerId.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("Некорректный идентификатор провайдера");
        return root.node(SECURE_NODE + "/providers/" + providerId);
    }
}
