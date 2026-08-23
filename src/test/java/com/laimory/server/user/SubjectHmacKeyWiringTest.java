package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.user.service.SubjectMappingMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code app.subject.mode} 배선 검증({@link ApplicationContextRunner}) — fixture/secretsmanager
 * provider 선택과 unknown mode·설정 오류 fail-fast를 실제 Spring 컨텍스트 기동으로 확인한다
 * ({@code AiDispatcherWiringTest} 선례).
 *
 * <p>{@link SubjectLookupKeyDeriver}를 <b>required consumer</b>로 함께 등록하는 것이 핵심이다 —
 * production에서도 무조건 빈이라, 매칭 snapshot provider가 없을 때(오타·미설정) 주입 실패로 컨텍스트가
 * 실제로 실패한다({@code matchIfMissing} 없음 = 배포 환경이 fixture로 조용히 뜨지 않는다는 계약).
 *
 * <p>provider 선택 축은 {@code app.subject.mode} property 하나다({@code @Profile} 게이팅 없음) —
 * 배포의 fixture 금지는 deploy preflight의 mode 값 고정과, fixture-key 기본값이 docker properties에만
 * 있어 배포 기본 프로필의 fixture 기동이 실패한다는 사실이 담당한다.
 */
class SubjectHmacKeyWiringTest {

    /** docker 프로필 fixture 기본값과 같은 형태의 결정적 non-production key(base64 32바이트). */
    private static final String FIXTURE_KEY_BASE64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    FixtureSubjectHmacKeyConfig.class,
                    SecretsManagerSubjectHmacKeyConfig.class,
                    SubjectMappingMetrics.class,
                    SubjectLookupKeyDeriver.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void fixtureMode_buildsSnapshotFromFixtureKey() {
        runner.withPropertyValues(
                        "app.subject.mode=fixture",
                        "app.subject.fixture-key=" + FIXTURE_KEY_BASE64)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SubjectHmacKeySnapshot.class)
                            .hasSingleBean(SubjectLookupKeyDeriver.class);
                    SubjectHmacKeySnapshot snapshot = context.getBean(SubjectHmacKeySnapshot.class);
                    assertThat(snapshot.currentVersion())
                            .isEqualTo(FixtureSubjectHmacKeyConfig.FIXTURE_KEY_VERSION);
                    assertThat(snapshot.hasPreviousKey()).isFalse();
                    assertThat(snapshot.currentKey()).isEqualTo(
                            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
                });
    }

    @Test
    void unknownMode_failsContext() {
        // fail-fast 경로 고정: 오타 → fixture/secretsmanager 어느 조건도 매칭 안 됨 → snapshot 빈 부재로
        // deriver 주입 실패가 root cause여야 한다.
        runner.withPropertyValues("app.subject.mode=fixtrue")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(NoSuchBeanDefinitionException.class)
                        .hasMessageContaining("SubjectHmacKeySnapshot"));
    }

    @Test
    void modeMissing_failsContext() {
        // matchIfMissing 없음 — 미설정도 기동 실패(배포 기본 프로필은 APP_SUBJECT_MODE 필수라 이 경로다).
        runner.run(context -> assertThat(context).getFailure()
                .rootCause()
                .isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining("SubjectHmacKeySnapshot"));
    }

    @Test
    void fixtureMode_withoutFixtureKeyProperty_failsContext() {
        // 배포 기본 프로필에는 fixture 기본값이 없다 — key 없는 fixture 기동은 실패해야 한다
        // (@Profile 게이팅 제거 후 "배포에서 fixture 금지"를 코드가 담당하는 절반이 이 경로다).
        runner.withPropertyValues("app.subject.mode=fixture")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("app.subject.fixture-key"));
    }

    @Test
    void fixtureMode_invalidBase64_failsContext() {
        runner.withPropertyValues(
                        "app.subject.mode=fixture",
                        "app.subject.fixture-key=not-base64!!")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("base64"));
    }

    @Test
    void fixtureMode_keyNotThirtyTwoBytes_failsContext() {
        runner.withPropertyValues(
                        "app.subject.mode=fixture",
                        "app.subject.fixture-key=c2hvcnQta2V5") // "short-key" — 9바이트
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("32 bytes"));
    }

    @Test
    void secretsManagerMode_blankArn_failsContext() {
        // production 기본값(app.subject.secret-arn=${APP_SUBJECT_SECRET_ARN:})과 같은 빈 값 경로.
        runner.withPropertyValues(
                        "app.subject.mode=secretsmanager",
                        "app.subject.secret-arn=")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("APP_SUBJECT_SECRET_ARN"));
    }

    @Test
    void secretsManagerMode_withoutCredentials_failsFast() {
        // AWS 자격증명이 있는 환경(로컬 SSO·EC2 프로파일)에선 이 실패 경로를 재현할 수 없고 실 AWS 호출이
        // 나가면 안 되므로 건너뛴다 — 자격증명 없는 CI에서는 항상 실행된다(FirebasePushConfigTest 선례).
        Assumptions.assumeTrue(System.getenv("AWS_ACCESS_KEY_ID") == null, "AWS_ACCESS_KEY_ID가 설정된 환경");
        Assumptions.assumeTrue(System.getenv("AWS_PROFILE") == null, "AWS_PROFILE이 설정된 환경");
        Assumptions.assumeTrue(System.getenv("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI") == null,
                "컨테이너 자격증명이 설정된 환경");
        File awsDir = new File(System.getProperty("user.home"), ".aws");
        Assumptions.assumeTrue(!new File(awsDir, "credentials").exists(), "~/.aws/credentials가 있는 환경");
        Assumptions.assumeTrue(!new File(awsDir, "config").exists(), "~/.aws/config가 있는 환경");

        runner.withPropertyValues(
                        "app.subject.mode=secretsmanager",
                        "app.subject.secret-arn="
                                + "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:laimory-test")
                .run(context -> assertThat(context).hasFailed());
    }
}
