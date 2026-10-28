package io.github.hansglorias.edtai.core.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Чтение сертификатов X.509 из PEM: один файл может содержать цепочку или
 * набор корневых сертификатов.
 */
public final class PemCertificates {

    private static final Pattern BLOCK = Pattern.compile(
            "-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+?)-----END CERTIFICATE-----");

    private PemCertificates() {
    }

    public static List<X509Certificate> read(Path file) throws IOException, CertificateException {
        return parse(Files.readString(file, StandardCharsets.US_ASCII));
    }

    /**
     * @throws CertificateException если в тексте нет ни одного сертификата или блок повреждён
     */
    public static List<X509Certificate> parse(String pem) throws CertificateException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> certificates = new ArrayList<>();
        Matcher matcher = BLOCK.matcher(pem);
        while (matcher.find()) {
            byte[] der;
            try {
                der = Base64.getMimeDecoder().decode(matcher.group(1));
            } catch (IllegalArgumentException e) {
                throw new CertificateException("Повреждён блок сертификата №" + (certificates.size() + 1), e);
            }
            try {
                certificates.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
            } catch (CertificateException e) {
                throw new CertificateException("Повреждён блок сертификата №" + (certificates.size() + 1), e);
            }
        }
        if (certificates.isEmpty()) {
            throw new CertificateException("В файле нет сертификатов в формате PEM (BEGIN CERTIFICATE)");
        }
        return certificates;
    }
}
