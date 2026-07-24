package com.laimory.server.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.laimory.server.config.AsyncConfig;
import com.laimory.server.push.service.PushRegistrationService;
import com.laimory.server.push.service.TimelineCompletionPushNotifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code app.push.mode} 배선 검증({@link ApplicationContextRunner}) — noop/firebase sender 선택과
 * unknown mode·credential 부재 fail-fast를 실제 Spring 컨텍스트 기동으로 확인한다
 * ({@code AiDispatcherWiringTest} 선례).
 *
 * <p>production consumer인 {@link TimelineCompletionPushNotifier}를 실제 빈으로 함께 등록하는 것이
 * 핵심이다 — 이게 있어야 매칭 sender 빈이 없을 때(오타·미배선) 생성자 주입이 실패해 컨텍스트가 실제로
 * 실패하고, {@code @Async} 프록시 배선 회귀도 같이 잡힌다. leaf dependency만 mock한다.
 *
 * <p>firebase 모드 선택 테스트는 {@link FirebaseMessaging}을 mock 빈으로 공급해 {@link FirebasePushConfig}의
 * ADC 경로와 분리한다 — 실제 Firebase credential은 테스트 리소스·CI에 두지 않는다.
 */
class FirebasePushConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(FirebaseMessaging.class, () -> Mockito.mock(FirebaseMessaging.class))
            .withBean(PushRegistrationService.class, () -> Mockito.mock(PushRegistrationService.class))
            .withBean(PushMetrics.class, () -> Mockito.mock(PushMetrics.class))
            .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-07-21T00:00:00Z"), ZoneId.of("Asia/Seoul")))
            .withUserConfiguration(
                    AsyncConfig.class,
                    NoOpPushMessageSender.class,
                    FirebasePushMessageSender.class,
                    TimelineCompletionPushNotifier.class);

    @Test
    void modeMissing_defaultsToNoOpSender() {
        // matchIfMissing=true — app.push.mode 미설정(prod·로컬·CI) 시 noop이 기본(외부 발송 없음).
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PushMessageSender.class);
            assertThat(context.getBean(PushMessageSender.class)).isInstanceOf(NoOpPushMessageSender.class);
        });
    }

    @Test
    void noopMode_wiresNoOpSender() {
        runner.withPropertyValues("app.push.mode=noop").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PushMessageSender.class);
            assertThat(context.getBean(PushMessageSender.class)).isInstanceOf(NoOpPushMessageSender.class);
        });
    }

    @Test
    void firebaseMode_wiresFirebaseSenderOnly_andNotifierIsAsyncProxied() {
        runner.withPropertyValues("app.push.mode=firebase").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PushMessageSender.class);
            assertThat(context.getBean(PushMessageSender.class))
                    .isInstanceOf(FirebasePushMessageSender.class);
            // @Async 배선 회귀 가드 — 프록시가 빠지면 FCM I/O가 콜백 요청 스레드에서 동기 실행된다.
            TimelineCompletionPushNotifier notifier = context.getBean(TimelineCompletionPushNotifier.class);
            assertThat(AopUtils.isAopProxy(notifier)).isTrue();
            assertThat(AopProxyUtils.ultimateTargetClass(notifier))
                    .isEqualTo(TimelineCompletionPushNotifier.class);
        });
    }

    @Test
    void unknownMode_failsContext() {
        // fail-fast 경로 고정: 오타 → noop/firebase 어느 조건도 매칭 안 됨 → sender 빈 부재로 notifier
        // 생성자 주입 실패가 root cause여야 한다.
        runner.withPropertyValues("app.push.mode=firebse")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(NoSuchBeanDefinitionException.class)
                        .hasMessageContaining("PushMessageSender"));
    }

    @Test
    void firebaseOptions_enforceFiniteHttpTimeouts() {
        // FirebaseOptions 기본 timeout은 0(무한) — FCM hang이 @Async thread를 영구 점유하지 않게 유한값을 고정한다.
        GoogleCredentials credentials = GoogleCredentials.create(
                new AccessToken("test-token", new Date(Instant.parse("2026-07-21T01:00:00Z").toEpochMilli())));

        FirebaseOptions options = FirebasePushConfig.firebaseOptions(credentials);

        assertThat(options.getConnectTimeout()).isEqualTo(FirebasePushConfig.CONNECT_TIMEOUT_MILLIS).isPositive();
        assertThat(options.getReadTimeout()).isEqualTo(FirebasePushConfig.READ_TIMEOUT_MILLIS).isPositive();
        assertThat(options.getWriteTimeout()).isEqualTo(FirebasePushConfig.WRITE_TIMEOUT_MILLIS).isPositive();
    }

    @Test
    void firebaseMode_withoutCredentials_failsFast() {
        // ADC가 존재하는 환경(GOOGLE_APPLICATION_CREDENTIALS·gcloud 로그인)에선 이 실패 경로를 재현할 수
        // 없으므로 건너뛴다 — CI·일반 개발 머신에서는 항상 실행된다.
        Assumptions.assumeTrue(System.getenv("GOOGLE_APPLICATION_CREDENTIALS") == null,
                "GOOGLE_APPLICATION_CREDENTIALS가 설정된 환경");
        Assumptions.assumeTrue(!Files.exists(Path.of(System.getProperty("user.home"),
                        ".config", "gcloud", "application_default_credentials.json")),
                "gcloud ADC가 설정된 환경");

        new ApplicationContextRunner()
                .withUserConfiguration(FirebasePushConfig.class)
                .withPropertyValues("app.push.mode=firebase")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // 실패 체인: BeanCreation → IllegalStateException(조치 안내 메시지) → IOException(ADC 부재).
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("IllegalStateException")
                            .hasStackTraceContaining("GOOGLE_APPLICATION_CREDENTIALS");
                });
    }
}
