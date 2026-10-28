package io.github.hansglorias.edtai.core.tls;

import java.nio.file.Path;
import java.util.Optional;

import io.github.hansglorias.edtai.core.settings.AiSettings;

/**
 * Каким сертификатам доверять при подключении к серверу модели. Корневые
 * сертификаты JDK используются всегда.
 *
 * @param useSystemTrustStore добавить корневые сертификаты ОС
 * @param caCertificateFile   дополнительный PEM-файл
 */
public record TrustSettings(boolean useSystemTrustStore, Optional<Path> caCertificateFile) {

    public static TrustSettings from(AiSettings settings) {
        String file = settings.caCertificateFile();
        return new TrustSettings(settings.useSystemTrustStore(),
                file.isEmpty() ? Optional.empty() : Optional.of(Path.of(file)));
    }
}
