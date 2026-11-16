package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.secrets.SecretsClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OCI Vault secret bundle → snapshot 계약을 실제 OCI SDK client와 loopback {@link MockWebServer}로 검증한다.
 *
 * <p>SDK를 mock하지 않는 이유: 이 저장소의 Jackson·resilience4j 버전에서 SDK의 HTTP·역직렬화 경로가 실제로
 * 동작하는지가 검증 대상이다. 인증은 instance principal 대신 테스트용 RSA key의 서명 provider를 쓴다.
 */
class OciVaultSubjectHmacKeyConfigTest {

    private static final byte[] CURRENT_KEY =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final String SECRET_ID = "ocid1.vaultsecret.oc1.test.fixture";

    private MockWebServer server;
    private SecretsClient client;

    @BeforeEach
    void setUp() throws IOException, NoSuchAlgorithmException {
        server = new MockWebServer();
        server.start();
        client = SecretsClient.builder()
                .endpoint(server.url("/").toString())
                .build(testAuthProvider());
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        server.shutdown();
    }

    @Test
    void loadSnapshotReadsCurrentStageBundleAndParsesSecretSchema() throws InterruptedException {
        String secretJson = """
                {"currentVersion":1,"currentKey":"%s"}
                """.formatted(Base64.getEncoder().encodeToString(CURRENT_KEY));
        server.enqueue(bundleResponse(Base64.getEncoder().encodeToString(
                secretJson.getBytes(StandardCharsets.UTF_8))));

        SubjectHmacKeySnapshot snapshot =
                new OciVaultSubjectHmacKeyConfig().loadSnapshot(client, SECRET_ID);

        assertThat(snapshot.currentVersion()).isEqualTo((short) 1);
        assertThat(snapshot.currentKey()).isEqualTo(CURRENT_KEY);
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/20190301/secretbundles/" + SECRET_ID + "?stage=CURRENT");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void loadSnapshotRejectsNonBase64ContentWithoutLeakingIt() {
        String sensitive = "sensitive-secret-value!!";
        server.enqueue(bundleResponse(sensitive));

        assertThatThrownBy(() -> new OciVaultSubjectHmacKeyConfig().loadSnapshot(client, SECRET_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64")
                .hasMessageNotContaining(sensitive)
                .hasNoCause();
    }

    private static MockResponse bundleResponse(String content) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"secretId":"%s","versionNumber":1,"stages":["CURRENT"],
                         "secretBundleContent":{"contentType":"BASE64","content":"%s"}}
                        """.formatted(SECRET_ID, content));
    }

    private static SimpleAuthenticationDetailsProvider testAuthProvider() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        return SimpleAuthenticationDetailsProvider.builder()
                .tenantId("ocid1.tenancy.oc1..test")
                .userId("ocid1.user.oc1..test")
                .fingerprint("00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00")
                .privateKeySupplier(() -> new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))
                .build();
    }
}
