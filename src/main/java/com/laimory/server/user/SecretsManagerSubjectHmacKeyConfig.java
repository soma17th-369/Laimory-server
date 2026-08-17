package com.laimory.server.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.user.service.SubjectMappingMetrics;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

/**
 * 배포 환경 subject HMAC key provider — {@code app.subject.mode=secretsmanager}에서만 활성화된다.
 *
 * <p>환경 분기는 저장소 관례대로 {@code @ConditionalOnProperty}(mode property) 하나가 유일한
 * 스위치다({@code @Profile} 게이팅 없음). "배포에서 fixture 금지" 계약(계획 §2.9)은 ① deploy
 * preflight가 {@code APP_SUBJECT_MODE=secretsmanager}를 값까지 고정하고 ②
 * {@code app.subject.fixture-key} 기본값이 docker properties에만 있어 배포 기본 프로필의 fixture
 * mode는 무기본값 property로 어차피 기동 실패한다는 이중 장치로 성립한다.
 *
 * <p><b>이 저장소에서 유일하게 context refresh 중 실 AWS 호출을 하는 빈이다.</b>
 * {@code PhotoStorageConfig}가 의도적으로 피해온 성질({@code S3Client}는 생성 시점 AWS 무호출)의
 * 의도적 예외로, 계획 §2.9의 fail-fast 요구 때문이다: secret을 읽지 못한 instance는 subject lookup을
 * 절대 수행할 수 없어야 하므로, 기동 시 {@code GetSecretValue}를 <b>정확히 1회</b> 호출해 검증된
 * immutable snapshot을 만들고 실패하면 context 기동 자체가 실패한다. 요청 경로에서는 Secrets Manager를
 * 재호출하지 않으며 rotation은 secret 갱신 + application restart로만 반영된다.
 *
 * <p>secret string 스키마: {@code {"currentVersion":n,"currentKey":"<base64 32-byte>"}}에 rotation
 * 기간 한정 {@code previousVersion}/{@code previousKey}가 추가된다. schema 오류·base64 아님·32바이트
 * 아님·version 오류는 전부 기동 실패다. <b>secret 값·key 바이트는 로그·예외 메시지에 절대 담지 않는다</b>
 * — 예외에는 어떤 항목이 잘못됐는지 이름만 남긴다(파싱 예외를 cause로 연결하지 않는 것도 같은 이유:
 * Jackson·Base64 예외 메시지는 원문 일부를 포함할 수 있다).
 */
@Configuration
@ConditionalOnProperty(name = "app.subject.mode", havingValue = "secretsmanager")
class SecretsManagerSubjectHmacKeyConfig {

    // 기동 1회 호출도 무기한 점유하지 않도록 요청 단위 timeout을 조인다(S3PhotoStorageService 선례).
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(3);

    @Bean
    SubjectHmacKeySnapshot subjectHmacKeySnapshot(
            @Value("${app.subject.secret-arn}") String secretArn,
            @Value("${aws.region:ap-northeast-2}") String region,
            SubjectMappingMetrics subjectMappingMetrics) {
        Timer.Sample sample = subjectMappingMetrics.start();
        if (secretArn == null || secretArn.isBlank()) {
            throw new IllegalStateException(
                    "APP_SUBJECT_SECRET_ARN is required when app.subject.mode=secretsmanager");
        }
        // 자격증명은 SDK 기본 체인(DefaultCredentialsProvider — EC2 인스턴스 프로파일/환경변수)으로 해석.
        // client는 기동 시 이 1회 호출 전용이므로 호출 후 즉시 닫는다.
        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(region))
                .overrideConfiguration(override -> override
                        .apiCallTimeout(CALL_TIMEOUT)
                        .apiCallAttemptTimeout(ATTEMPT_TIMEOUT))
                .build()) {
            SubjectHmacKeySnapshot snapshot = loadSnapshot(client, secretArn);
            // 성공 latency만 기록한다 — 실패 시 context가 기동하지 않아 Prometheus가 이 meter를
            // 수집할 수 없다(죽은 관측). 실패 관측은 기동 실패 로그와 deploy preflight가 담당한다.
            subjectMappingMetrics.recordSecretLoad(sample);
            return snapshot;
        }
    }

    SubjectHmacKeySnapshot loadSnapshot(SecretsManagerClient client, String secretArn) {
        GetSecretValueRequest request = GetSecretValueRequest.builder().secretId(secretArn).build();
        GetSecretValueResponse response = client.getSecretValue(request);
        return parse(response.secretString());
    }

    static SubjectHmacKeySnapshot parse(String secretString) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(secretString);
        } catch (Exception e) {
            throw new IllegalStateException("subject hmac secret is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("subject hmac secret must be a JSON object");
        }
        short currentVersion = requireVersion(root, "currentVersion");
        byte[] currentKey = requireKey(root, "currentKey");
        boolean hasPreviousVersion = root.hasNonNull("previousVersion");
        boolean hasPreviousKey = root.hasNonNull("previousKey");
        if (hasPreviousVersion != hasPreviousKey) {
            throw new IllegalStateException(
                    "subject hmac secret previousVersion and previousKey must be present together");
        }
        if (!hasPreviousVersion) {
            return new SubjectHmacKeySnapshot(currentVersion, currentKey);
        }
        return new SubjectHmacKeySnapshot(currentVersion, currentKey,
                requireVersion(root, "previousVersion"), requireKey(root, "previousKey"));
    }

    /** SMALLINT 범위의 양의 정수만 허용한다 — 타입·범위 위반은 항목 이름만으로 실패시킨다. */
    private static short requireVersion(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new IllegalStateException("subject hmac secret " + field + " must be an integer");
        }
        int version = node.intValue();
        if (version <= 0 || version > Short.MAX_VALUE) {
            throw new IllegalStateException(
                    "subject hmac secret " + field + " must be a positive SMALLINT");
        }
        return (short) version;
    }

    private static byte[] requireKey(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            throw new IllegalStateException("subject hmac secret " + field + " must be a string");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(node.textValue());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("subject hmac secret " + field + " must be valid base64");
        }
        if (key.length != SubjectHmacKeySnapshot.KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "subject hmac secret " + field + " must decode to exactly 32 bytes");
        }
        return key;
    }
}
