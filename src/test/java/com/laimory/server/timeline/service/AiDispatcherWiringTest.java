package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.config.AsyncConfig;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code app.ai.mode} 배선 검증({@link ApplicationContextRunner}) — noop/fake/http/agentcore dispatcher
 * 선택과 unknown mode fail-fast를 실제 Spring 컨텍스트 기동으로 확인한다({@link com.laimory.server.geo.GeoWiringTest}
 * 선례).
 *
 * <p>{@link DispatcherConsumer}·{@link UserMemoryDispatcherConsumer}를 <b>required consumer</b>로 함께
 * 등록하는 것이 핵심이다 — 이게 있어야 매칭 dispatcher 빈이 없을 때(오타·미배선) 주입이 실패해 컨텍스트가
 * 실제로 실패한다. consumer 없이 dispatcher 빈만 등록하면 매칭 빈 0개여도 빈 컨텍스트가 정상 기동해
 * "오타→실패" 단언이 거짓이 된다. 두 인터페이스를 함께 요구해 <b>mode마다 두 dispatcher가 각각 정확히
 * 하나</b>인 계약(#338)도 같은 기동에서 확인한다.
 *
 * <p>fake/http dispatcher 생성자의 {@code RestClient.Builder}는 수동 빈이 아니라 production과 같은 Boot
 * 자동설정으로 제공한다 — 자동설정이 깨져 dev fake context가 기동 실패하는 회귀를 수동 빈이 가리는 것을
 * 막는다. agentcore dispatcher가 쓰는 {@code ObjectMapper}도 같은 이유로 Boot 자동설정이 제공한다
 * (직접 만든 mapper는 접수 body의 시각 포맷이 계약과 달라진다). append 서비스는 실제 빈으로 등록해
 * {@code app.ai.mode=fake} 조건을 함께 검증하고, leaf dependency만 mock한다. dispatcher는 호출하지
 * 않으므로 HTTP·sleep·DB·Redis·AWS 호출이 없다({@code BedrockAgentCoreClient}는 생성 시 AWS 무호출).
 */
class AiDispatcherWiringTest {

    private static final String VALID_RUNTIME_ARN =
            "arn:aws:bedrock-agentcore:ap-northeast-2:123456789012:runtime/laimory_ai-AbCdEf";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class,
                    JacksonAutoConfiguration.class))
            // dispatcher 생성자의 @Value "2s" → Duration 변환은 production에선 SpringApplication이
            // beanFactory에 설정하는 ApplicationConversionService가 담당한다. plain runner에는 없어
            // ConversionNotSupportedException으로 실패하므로 같은 변환기를 conversionService 빈으로 제공한다.
            .withBean(ConfigurableApplicationContext.CONVERSION_SERVICE_BEAN_NAME,
                    ApplicationConversionService.class, ApplicationConversionService::new)
            // fake User Memory dispatcher가 요구하는 Clock(production은 SchedulingConfig가 제공).
            .withBean(Clock.class, Clock::systemDefaultZone)
            .withBean(DailyRecordService.class, () -> Mockito.mock(DailyRecordService.class))
            .withBean(TimelineDraftSourceItemService.class,
                    () -> Mockito.mock(TimelineDraftSourceItemService.class))
            .withBean(TimelineEventService.class, () -> Mockito.mock(TimelineEventService.class))
            .withBean(TimelineEventItemService.class, () -> Mockito.mock(TimelineEventItemService.class))
            .withBean(TimelineItemService.class, () -> Mockito.mock(TimelineItemService.class))
            .withUserConfiguration(
                    AsyncConfig.class,
                    NoOpTimelineAiDispatcher.class,
                    FakeTimelineAiDispatcher.class,
                    HttpTimelineAiDispatcher.class,
                    AgentCoreTimelineAiDispatcher.class,
                    NoOpUserMemoryUpdateDispatcher.class,
                    FakeUserMemoryUpdateDispatcher.class,
                    HttpUserMemoryUpdateDispatcher.class,
                    AgentCoreUserMemoryUpdateDispatcher.class,
                    AgentCoreClientConfig.class,
                    AgentCoreDispatchClient.class,
                    DispatcherConsumer.class,
                    UserMemoryDispatcherConsumer.class);

    /** dispatcher 인터페이스를 생성자에서 요구하는 test-only consumer(production {@code TimelineDraftTaskService} 대역). */
    static class DispatcherConsumer {
        DispatcherConsumer(TimelineAiDispatcher dispatcher) {
        }
    }

    /** User Memory dispatcher를 요구하는 test-only consumer(production {@code UserMemoryUpdateWorker} 대역). */
    static class UserMemoryDispatcherConsumer {
        UserMemoryDispatcherConsumer(UserMemoryUpdateDispatcher dispatcher) {
        }
    }

    @Test
    void modeMissing_defaultsToNoOpDispatcher() {
        // matchIfMissing=true — app.ai.mode 미설정(prod 포함) 시 NoOp이 기본.
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TimelineAiDispatcher.class);
            assertThat(context.getBean(TimelineAiDispatcher.class))
                    .isInstanceOf(NoOpTimelineAiDispatcher.class);
            assertThat(context).hasSingleBean(UserMemoryUpdateDispatcher.class);
            assertThat(context.getBean(UserMemoryUpdateDispatcher.class))
                    .isInstanceOf(NoOpUserMemoryUpdateDispatcher.class);
        });
    }

    @Test
    void noopMode_wiresNoOpDispatcher() {
        runner.withPropertyValues("app.ai.mode=noop").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TimelineAiDispatcher.class);
            assertThat(context.getBean(TimelineAiDispatcher.class))
                    .isInstanceOf(NoOpTimelineAiDispatcher.class);
            assertThat(context).hasSingleBean(UserMemoryUpdateDispatcher.class);
            assertThat(context.getBean(UserMemoryUpdateDispatcher.class))
                    .isInstanceOf(NoOpUserMemoryUpdateDispatcher.class);
        });
    }

    @Test
    void fakeMode_wiresFakeDispatcherWithAsyncProxy() {
        // production property 이름(spring.http.client.*)이 자동설정을 거쳐 builder에 적용되는 경로 그대로
        // fake dispatcher 생성자가 충족되어야 한다.
        runner.withPropertyValues(
                        "app.ai.mode=fake",
                        "spring.http.client.connect-timeout=2s",
                        "spring.http.client.read-timeout=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(TimelineAiDispatcher.class);
                    TimelineAiDispatcher dispatcher = context.getBean(TimelineAiDispatcher.class);
                    // @Async 배선 회귀 가드 — proxy가 빠지면 dev POST가 callback delay만큼 블로킹된다.
                    // 실제 thread 전환·delay timing은 검증하지 않는다.
                    assertThat(AopUtils.isAopProxy(dispatcher)).isTrue();
                    assertThat(AopProxyUtils.ultimateTargetClass(dispatcher))
                            .isEqualTo(FakeTimelineAiDispatcher.class);
                    assertThat(context).hasSingleBean(UserMemoryUpdateDispatcher.class);
                    assertThat(AopProxyUtils.ultimateTargetClass(
                            context.getBean(UserMemoryUpdateDispatcher.class)))
                            .isEqualTo(FakeUserMemoryUpdateDispatcher.class);
                });
    }

    @Test
    void httpMode_wiresHttpDispatcher() {
        runner.withPropertyValues(
                        "app.ai.mode=http",
                        "app.ai.http.base-url=http://localhost:8000")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TimelineAiDispatcher.class);
                    assertThat(context.getBean(TimelineAiDispatcher.class))
                            .isInstanceOf(HttpTimelineAiDispatcher.class);
                    assertThat(context).hasSingleBean(UserMemoryUpdateDispatcher.class);
                    assertThat(context.getBean(UserMemoryUpdateDispatcher.class))
                            .isInstanceOf(HttpUserMemoryUpdateDispatcher.class);
                });
    }

    @Test
    void httpMode_withoutBaseUrl_failsContext() {
        // 빈 base-url(기본값 "")은 기동 시점에 fail-fast여야 한다 — 첫 dispatch에서야 상대 URI 오류가 나는 걸 방지.
        runner.withPropertyValues("app.ai.mode=http", "app.ai.http.base-url=")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("base-url"));
    }

    // --- agentcore mode(#338) ---

    @Test
    void agentcoreMode_wiresAgentCoreDispatchers() {
        agentcoreRunner().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TimelineAiDispatcher.class);
            assertThat(context.getBean(TimelineAiDispatcher.class))
                    .isInstanceOf(AgentCoreTimelineAiDispatcher.class);
            assertThat(context).hasSingleBean(UserMemoryUpdateDispatcher.class);
            assertThat(context.getBean(UserMemoryUpdateDispatcher.class))
                    .isInstanceOf(AgentCoreUserMemoryUpdateDispatcher.class);
            // 두 dispatcher는 하나의 adapter·client를 공유한다(도메인 경계만 분리).
            assertThat(context).hasSingleBean(AgentCoreDispatchClient.class);
        });
    }

    @Test
    void agentcoreMode_withoutRuntimeArn_failsContext() {
        runner.withPropertyValues("app.ai.mode=agentcore", "app.ai.agentcore.endpoint=DEFAULT")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("app.ai.agentcore.runtime-arn"));
    }

    @Test
    void agentcoreMode_withoutEndpoint_failsContext() {
        runner.withPropertyValues("app.ai.mode=agentcore",
                        "app.ai.agentcore.runtime-arn=" + VALID_RUNTIME_ARN,
                        "app.ai.agentcore.endpoint=")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("app.ai.agentcore.endpoint"));
    }

    @Test
    void agentcoreMode_partialArn_failsContext() {
        // Runtime ID만 주는 형태는 대상이 모호해질 수 있어 full ARN만 허용한다.
        runner.withPropertyValues("app.ai.mode=agentcore",
                        "app.ai.agentcore.runtime-arn=laimory_ai-AbCdEf",
                        "app.ai.agentcore.endpoint=DEFAULT")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("full AgentCore Runtime ARN"));
    }

    @Test
    void agentcoreMode_arnRegionMismatch_failsContext() {
        // client는 aws.region으로 만들어지므로 다른 리전 ARN이면 첫 dispatch가 반드시 실패한다 → 기동에서 잡는다.
        runner.withPropertyValues("app.ai.mode=agentcore",
                        "app.ai.agentcore.runtime-arn=" + VALID_RUNTIME_ARN,
                        "app.ai.agentcore.endpoint=DEFAULT",
                        "aws.region=us-east-1")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("region"));
    }

    @Test
    void unknownMode_failsContext() {
        // fail-fast 경로 고정: 오타 → noop/fake/http/agentcore 어느 조건도 매칭 안 됨 → dispatcher 빈 부재로
        // consumer 주입 실패가 root cause여야 한다(무관한 오류가 아니라 이 경로임을 못박음).
        runner.withPropertyValues("app.ai.mode=fak")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(NoSuchBeanDefinitionException.class)
                        .hasMessageContaining("Dispatcher"));
    }

    private ApplicationContextRunner agentcoreRunner() {
        return runner.withPropertyValues("app.ai.mode=agentcore",
                "app.ai.agentcore.runtime-arn=" + VALID_RUNTIME_ARN,
                "app.ai.agentcore.endpoint=DEFAULT");
    }
}
