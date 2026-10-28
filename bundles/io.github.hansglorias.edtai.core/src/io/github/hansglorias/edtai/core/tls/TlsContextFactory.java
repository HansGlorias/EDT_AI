package io.github.hansglorias.edtai.core.tls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Собирает {@link SSLContext}, который доверяет корневым сертификатам JDK,
 * при необходимости — сертификатам ОС и дополнительному PEM-файлу
 * (корпоративный CA). Отключить проверку сертификатов нельзя.
 */
public final class TlsContextFactory {

    /** Где лежат корневые сертификаты в Debian/Astra, RHEL и других Linux. */
    static final List<Path> LINUX_CA_BUNDLES = List.of(
            Path.of("/etc/ssl/certs/ca-certificates.crt"),
            Path.of("/etc/pki/tls/certs/ca-bundle.crt"),
            Path.of("/etc/ssl/ca-bundle.pem"));

    private TlsContextFactory() {
    }

    public static SSLContext create(TrustSettings settings) throws GeneralSecurityException, IOException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] { createTrustManager(settings) }, null);
        return context;
    }

    public static X509TrustManager createTrustManager(TrustSettings settings) throws GeneralSecurityException, IOException {
        List<X509TrustManager> managers = new ArrayList<>();
        managers.add(trustManager(null));
        if (settings.useSystemTrustStore()) {
            KeyStore system = systemTrustStore();
            if (system != null) {
                managers.add(trustManager(system));
            }
        }
        if (settings.caCertificateFile().isPresent()) {
            managers.add(trustManager(keyStoreOf(PemCertificates.read(settings.caCertificateFile().get()))));
        }
        return new CompositeTrustManager(managers);
    }

    /** Хранилище корневых сертификатов ОС или {@code null}, если найти его не удалось. */
    static KeyStore systemTrustStore() throws GeneralSecurityException, IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            KeyStore store = KeyStore.getInstance("Windows-ROOT");
            store.load(null, null);
            return store;
        }
        for (Path bundle : LINUX_CA_BUNDLES) {
            if (Files.isReadable(bundle)) {
                try {
                    return keyStoreOf(PemCertificates.read(bundle));
                } catch (CertificateException e) {
                    // Повреждённый системный файл — пробуем следующий.
                }
            }
        }
        return null;
    }

    static KeyStore keyStoreOf(List<X509Certificate> certificates) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        for (int i = 0; i < certificates.size(); i++) {
            store.setCertificateEntry("ca-" + i, certificates.get(i));
        }
        return store;
    }

    private static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        return Arrays.stream(factory.getTrustManagers())
                .filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast)
                .findFirst()
                .orElseThrow(() -> new GeneralSecurityException("Нет X509TrustManager"));
    }

    /** Доверяет цепочке, если ей доверяет хотя бы один из менеджеров. */
    private static final class CompositeTrustManager implements X509TrustManager {

        private final List<X509TrustManager> managers;

        CompositeTrustManager(List<X509TrustManager> managers) {
            this.managers = List.copyOf(managers);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(manager -> manager.checkClientTrusted(chain, authType));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(manager -> manager.checkServerTrusted(chain, authType));
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return managers.stream()
                    .flatMap(manager -> Arrays.stream(manager.getAcceptedIssuers()))
                    .toArray(X509Certificate[]::new);
        }

        private void check(Check check) throws CertificateException {
            CertificateException first = null;
            for (X509TrustManager manager : managers) {
                try {
                    check.run(manager);
                    return;
                } catch (CertificateException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            throw first;
        }

        private interface Check {
            void run(X509TrustManager manager) throws CertificateException;
        }
    }
}
