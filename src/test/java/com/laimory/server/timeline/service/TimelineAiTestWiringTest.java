package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.common.privacy.PrivacyRedactor;
import com.laimory.server.timeline.controller.TimelineAiTestController;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * dev 전용 AI 동기 테스트 endpoint 배선 검증({@link ApplicationContextRunner}) — 노출 스위치와 기동 검증을
 * 실제 컨텍스트 기동으로 확인한다({@code AiDispatcherWiringTest} 선례).
 *
 * <p><b>runner에 DataSource·Redis 빈을 하나도 등록하지 않는 것이 핵심이다.</b> 이 경로가 MySQL이나 Redis를
 * 건드렸다면 주입이 실패해 컨텍스트가 뜨지 못한다 — "DB 비의존"을 주석이 아니라 배선으로 증명한다.
 *
 * <p>스위치는 양방향으로 확인한다: 미설정·false면 controller·service·client 빈이 <b>전혀 없어야</b> 하고
 * (경로 자체가 없으므로 404), true면 각각 정확히 하나여야 한다. 켠 상태에서 설정이 불완전하면 첫 호출이
 * 아니라 <b>기동</b>이 실패해야 한다.
 */
class TimelineAiTestWiringTest {

    private static final String VALID_URL = "https://ai.internal.example/v1/timeline/test";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
                    JacksonAutoConfiguration.class))
            // @Value의 "3s" → Duration, "1MB" → DataSize 변환기(production은 SpringApplication이 등록).
            .withBean(ConfigurableApplicationContext.CONVERSION_SERVICE_BEAN_NAME,
                    ApplicationConversionService.class, ApplicationConversionService::new)
            .withBean(PrivacyRedactor.class, PrivacyRedactor::new)
            .withUserConfiguration(TimelineAiTestConfig.class, TimelineAiTestClient.class,
                    TimelineAiTestService.class, TimelineAiTestController.class);

    @Test
    void registersNothingWhenDisabledOrUnset() {
        runner.run(context -> assertThat(context)
                .doesNotHaveBean(TimelineAiTestProperties.class)
                .doesNotHaveBean(TimelineAiTestClient.class)
                .doesNotHaveBean(TimelineAiTestService.class)
                .doesNotHaveBean(TimelineAiTestController.class));

        runner.withPropertyValues("app.ai.timeline-test.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(TimelineAiTestService.class));
    }

    @Test
    void registersExactlyOneOfEachWhenEnabledWithoutDatabaseOrRedis() {
        enabled().run(context -> {
            assertThat(context).hasSingleBean(TimelineAiTestProperties.class);
            assertThat(context).hasSingleBean(TimelineAiTestClient.class);
            assertThat(context).hasSingleBean(TimelineAiTestService.class);
            assertThat(context).hasSingleBean(TimelineAiTestController.class);
            // 이 경로는 저장소를 쓰지 않는다 — 없어도 기동한다는 사실 자체가 계약이다.
            assertThat(context).doesNotHaveBean(DataSource.class);
            assertThat(context).doesNotHaveBean(RedisTemplate.class);
        });
    }

    @Test
    void doesNotLeakAiCredentialThroughToString() {
        enabled().withPropertyValues("app.ai.timeline-test.ai-auth-token=ai-secret-token").run(context -> {
            TimelineAiTestProperties properties = context.getBean(TimelineAiTestProperties.class);
            assertThat(properties.aiAuthToken()).isEqualTo("ai-secret-token");
            // toString이 비밀을 흘리면 로그·예외로 새어 나간다.
            assertThat(properties.toString()).doesNotContain("ai-secret-token");
        });
    }

    @Test
    void failsFastWhenEnabledWithIncompleteConfiguration() {
        assertStartupFails("app.ai.timeline-test.url=");
        assertStartupFails("app.ai.timeline-test.url=/relative/path");
        assertStartupFails("app.ai.timeline-test.url=ftp://ai.internal.example/v1");
        assertStartupFails("app.ai.timeline-test.connect-timeout=0s");
        assertStartupFails("app.ai.timeline-test.max-response-bytes=0B");
    }

    @Test
    void failsFastWhenReadTimeoutWouldCutOffTheAiPipeline() {
        // AI는 PIPELINE_TIMEOUT_SEC(120s)이 끝나면 X-Timeline-Timed-Out과 함께 정상 200을 준다 —
        // read timeout이 그보다 짧거나 같으면 성공 응답을 받기 직전에 끊어 502로 만든다.
        assertStartupFails("app.ai.timeline-test.read-timeout=120s");
        assertStartupFails("app.ai.timeline-test.read-timeout=30s");

        enabled().withPropertyValues("app.ai.timeline-test.read-timeout=121s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    private void assertStartupFails(String override) {
        enabled().withPropertyValues(override).run(context -> assertThat(context)
                .hasFailed()
                .getFailure().hasRootCauseInstanceOf(IllegalStateException.class));
    }

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues(
                "app.ai.timeline-test.enabled=true",
                "app.ai.timeline-test.url=" + VALID_URL);
    }
}
