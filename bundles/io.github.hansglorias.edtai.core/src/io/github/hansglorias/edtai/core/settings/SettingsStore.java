package io.github.hansglorias.edtai.core.settings;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Чтение и запись настроек. Обычные настройки лежат в preferences рабочей
 * области, токен — в Eclipse Secure Storage и больше нигде.
 */
public final class SettingsStore {

    public static final String QUALIFIER = "io.github.hansglorias.edtai.core";

    static final String KEY_PROTOCOL = "protocol";
    static final String KEY_BASE_URL = "baseUrl";
    static final String KEY_MODEL = "model";
    static final String KEY_USE_SYSTEM_TRUST_STORE = "useSystemTrustStore";
    static final String KEY_CA_CERTIFICATE_FILE = "caCertificateFile";
    static final String KEY_MCP_SERVERS = "mcpServers";

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
                preferences.getBoolean(KEY_USE_SYSTEM_TRUST_STORE, AiSettings.DEFAULT_USE_SYSTEM_TRUST_STORE),
                preferences.get(KEY_CA_CERTIFICATE_FILE, ""),
                servers);
    }

    public void save(AiSettings settings) throws BackingStoreException {
        preferences.put(KEY_PROTOCOL, settings.protocol().name());
        put(KEY_BASE_URL, settings.baseUrl());
        put(KEY_MODEL, settings.model());
        preferences.putBoolean(KEY_USE_SYSTEM_TRUST_STORE, settings.useSystemTrustStore());
        put(KEY_CA_CERTIFICATE_FILE, settings.caCertificateFile());
        put(KEY_MCP_SERVERS, McpServersCodec.encode(settings.mcpServers()));
        preferences.flush();
    }

    /**
     * Есть ли сохранённый токен. Не расшифровывает Secure Storage, поэтому не
     * спрашивает мастер-пароль.
     */
    public boolean hasToken() {
        ISecurePreferences node = secureNode();
        return Arrays.asList(node.keys()).contains(SECURE_KEY_TOKEN);
    }

    /** @return токен или пустая строка */
    public String loadToken() throws StorageException {
        return secureNode().get(SECURE_KEY_TOKEN, "");
    }

    /** Сохраняет токен в зашифрованном виде; пустой токен удаляет сохранённый. */
    public void saveToken(String token) throws StorageException, IOException {
        ISecurePreferences node = secureNode();
        if (token == null || token.isEmpty()) {
            node.remove(SECURE_KEY_TOKEN);
        } else {
            node.put(SECURE_KEY_TOKEN, token, true);
        }
        node.flush();
    }

    private void put(String key, String value) {
        if (value.isEmpty()) {
            preferences.remove(key);
        } else {
            preferences.put(key, value);
        }
    }

    private ISecurePreferences secureNode() {
        ISecurePreferences root = secureRoot != null ? secureRoot : SecurePreferencesFactory.getDefault();
        if (root == null) {
            throw new IllegalStateException("Eclipse Secure Storage недоступно");
        }
        return root.node(SECURE_NODE);
    }
}
