package com.laimory.server.user;

import com.laimory.server.user.service.SubjectMappingMetrics;
import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.retrier.RetryConfiguration;
import com.oracle.bmc.secrets.Secrets;
import com.oracle.bmc.secrets.SecretsClient;
import com.oracle.bmc.secrets.model.Base64SecretBundleContentDetails;
import com.oracle.bmc.secrets.model.SecretBundleContentDetails;
import com.oracle.bmc.secrets.requests.GetSecretBundleRequest;
import com.oracle.bmc.util.CircuitBreakerUtils;
import com.oracle.bmc.waiter.MaxTimeTerminationStrategy;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OCI 배포 환경 subject HMAC key provider — {@code app.subject.mode=ocivault}에서만 활성화된다(#644).
 *
 * <p>OCI 인스턴스는 AWS 자격증명을 받을 수 없어 {@link SecretsManagerSubjectHmacKeyConfig}를 쓸 수 없다.
 * 같은 key 값을 OCI Vault secret에 두고, instance principal(인스턴스 메타데이터)로 인증해 기동 시
 * {@code GetSecretBundle}(CURRENT)을 <b>정확히 1회</b> 호출한다. secret 내용은 Secrets Manager와 같은 JSON
 * 스키마라 검증은 {@link SecretsManagerSubjectHmacKeyConfig#parse(String)}를 그대로 쓴다. 조회·검증 실패는
 * 기동 실패이고, 요청 경로에서는 Vault를 재호출하지 않는다(rotation은 secret 갱신 + 재시작).
 *
 * <p>AWS 배포는 deploy preflight가 {@code APP_SUBJECT_MODE=secretsmanager}를 값까지 고정하므로 이 모드를
 * 쓸 수 없다. <b>secret 값·key 바이트는 로그·예외 메시지에 담지 않는다</b> — 예외에는 항목 이름만 남긴다.
 */
@Configuration
@ConditionalOnProperty(name = "app.subject.mode", havingValue = "ocivault")
class OciVaultSubjectHmacKeyConfig {

    // 기동 1회 호출이 무기한 점유하지 않도록 조인다(secretsmanager 모드의 10초 상한과 맞춤).
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration TOTAL_RETRY_TIME = Duration.ofSeconds(10);

    @Bean
    SubjectHmacKeySnapshot subjectHmacKeySnapshot(
            @Value("${app.subject.oci-secret-id:}") String secretId,
            SubjectMappingMetrics subjectMappingMetrics) {
        Timer.Sample sample = subjectMappingMetrics.start();
        if (secretId == null || secretId.isBlank()) {
            throw new IllegalStateException(
                    "APP_SUBJECT_OCI_SECRET_ID is required when app.subject.mode=ocivault");
        }
        // region은 instance principal provider가 인스턴스 메타데이터에서 정한다.
        InstancePrincipalsAuthenticationDetailsProvider provider =
                InstancePrincipalsAuthenticationDetailsProvider.builder().build();
        // 기동 1회 호출뿐이라 circuit breaker는 끈다. client는 호출 후 즉시 닫는다.
        ClientConfiguration configuration = ClientConfiguration.builder()
                .connectionTimeoutMillis((int) CONNECT_TIMEOUT.toMillis())
                .readTimeoutMillis((int) READ_TIMEOUT.toMillis())
                .retryConfiguration(RetryConfiguration.builder()
                        .terminationStrategy(new MaxTimeTerminationStrategy(TOTAL_RETRY_TIME.toMillis()))
                        .build())
                .circuitBreakerConfiguration(CircuitBreakerUtils.getNoCircuitBreakerConfiguration())
                .build();
        try (SecretsClient client = SecretsClient.builder().configuration(configuration).build(provider)) {
            SubjectHmacKeySnapshot snapshot = loadSnapshot(client, secretId);
            // 성공 latency만 기록한다 — 실패 시 context가 기동하지 않아 수집되지 않는다(secretsmanager와 동일).
            subjectMappingMetrics.recordSecretLoad(sample);
            return snapshot;
        }
    }

    SubjectHmacKeySnapshot loadSnapshot(Secrets client, String secretId) {
        GetSecretBundleRequest request = GetSecretBundleRequest.builder()
                .secretId(secretId)
                .stage(GetSecretBundleRequest.Stage.Current)
                .build();
        SecretBundleContentDetails content =
                client.getSecretBundle(request).getSecretBundle().getSecretBundleContent();
        if (!(content instanceof Base64SecretBundleContentDetails base64Content)
                || base64Content.getContent() == null) {
            throw new IllegalStateException("subject hmac secret bundle must have BASE64 content");
        }
        String secretString;
        try {
            secretString = new String(Base64.getDecoder().decode(base64Content.getContent()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 원문 일부가 메시지에 섞일 수 있어 cause를 연결하지 않는다.
            throw new IllegalStateException("subject hmac secret bundle content must be valid base64");
        }
        return SecretsManagerSubjectHmacKeyConfig.parse(secretString);
    }
}
