package io.github.hansglorias.edtai.core.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.hansglorias.edtai.core.settings.AiSettings;
import io.github.hansglorias.edtai.core.settings.Protocol;

class TlsContextFactoryTest {

    @TempDir
    Path temp;

    @Test
    void readsSingleCertificate() throws Exception {
        List<X509Certificate> certificates = PemCertificates.parse(resource("test-ca.pem"));

        assertEquals(1, certificates.size());
        assertTrue(certificates.get(0).getSubjectX500Principal().getName().contains("CN=EDT AI Test CA"));
    }

    @Test
    void readsBundleWithSeveralCertificates() throws Exception {
        List<X509Certificate> certificates = PemCertificates.read(copy("two-cas.pem"));

        assertEquals(2, certificates.size());
        assertTrue(certificates.get(1).getSubjectX500Principal().getName().contains("CN=EDT AI Second Test CA"));
    }

    @Test
    void readsCertificateWithWindowsLineEndings() throws Exception {
        String pem = resource("test-ca.pem").replace("\n", "\r\n");

        assertEquals(1, PemCertificates.parse(pem).size());
    }

    @Test
    void rejectsFileWithoutCertificates() {
        assertThrows(CertificateException.class, () -> PemCertificates.parse("not a certificate"));
        assertThrows(CertificateException.class, () -> PemCertificates.parse(
                "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"));
    }

    @Test
    void trustsJdkRootsByDefault() throws Exception {
        X509TrustManager manager = TlsContextFactory.createTrustManager(new TrustSettings(false, Optional.empty()));

        assertTrue(manager.getAcceptedIssuers().length > 0);
        assertTrue(Arrays.stream(manager.getAcceptedIssuers()).noneMatch(TlsContextFactoryTest::isTestCa));
    }

    @Test
    void trustsExtraCertificateFile() throws Exception {
        X509TrustManager manager = TlsContextFactory.createTrustManager(new TrustSettings(false, Optional.of(copy("test-ca.pem"))));
        X509Certificate testCa = PemCertificates.parse(resource("test-ca.pem")).get(0);

        assertTrue(Arrays.stream(manager.getAcceptedIssuers()).anyMatch(TlsContextFactoryTest::isTestCa));
        manager.checkServerTrusted(new X509Certificate[] { testCa }, "RSA");
    }

    @Test
    void rejectsUnknownCertificate() throws Exception {
        X509TrustManager manager = TlsContextFactory.createTrustManager(new TrustSettings(false, Optional.empty()));
        X509Certificate testCa = PemCertificates.parse(resource("test-ca.pem")).get(0);

        assertThrows(CertificateException.class, () -> manager.checkServerTrusted(new X509Certificate[] { testCa }, "RSA"));
    }

    @Test
    void buildsContextFromSettings() throws Exception {
        AiSettings settings = new AiSettings(Protocol.OPENAI_COMPATIBLE, "https://llm.example.invalid", "m", 0, "", 0, true, true,
                copy("two-cas.pem").toString(), List.of());

        SSLContext context = TlsContextFactory.create(TrustSettings.from(settings));

        assertNotNull(context.getSocketFactory());
    }

    @Test
    void systemTrustStoreIsFoundOnCurrentOs() throws Exception {
        // На Windows — Windows-ROOT, на Linux — ca-certificates. В CI оба есть.
        assertNotNull(TlsContextFactory.systemTrustStore());
    }

    private static boolean isTestCa(X509Certificate certificate) {
        return certificate.getSubjectX500Principal().getName().contains("CN=EDT AI Test CA");
    }

    private Path copy(String name) throws IOException {
        return Files.writeString(temp.resolve(name), resource(name), StandardCharsets.US_ASCII);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = TlsContextFactoryTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }
}
