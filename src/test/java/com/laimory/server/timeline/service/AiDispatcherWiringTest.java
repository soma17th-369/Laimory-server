package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.config.AsyncConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code app.ai.mode} 배선 검증({@link ApplicationContextRunner}) — noop/fake/http dispatcher 선택과
 * unknown mode fail-fast를 실제 Spring 컨텍스트 기동으로 확인한다({@link com.laimory.server.geo.GeoWiringTest}
 * 선례).
 *
 * <p>{@link DispatcherConsumer}를 <b>required consumer</b>로 함께 등록하는 것이 핵심이다 — 이게 있어야
 * 매칭 dispatcher 빈이 없을 때(오타·미배선) 주입이 실패해 컨텍스트가 실제로 실패한다. consumer 없이
 * dispatcher 빈만 등록하면 매칭 빈 0개여도 빈 컨텍스트가 정상 기동해 "오타→실패" 단언이 거짓이 된다.
 *
 * <p>fake/http dispatcher 생성자의 {@code RestClient.Builder}는 수동 빈이 아니라 production과 같은 Boot
 * 자동설정으로 제공한다 — 자동설정이 깨져 dev fake context가 기동 실패하는 회귀를 수동 빈이 가리는 것을
 * 막는다. append 서비스는 실제 빈으로 등록해 {@code app.ai.mode=fake} 조건을 함께 검증하고, leaf
 * dependency만 mock한다. dispatcher는 호출하지 않으므로 HTTP·sleep·DB·Redis가 필요 없다.
 */
class AiDispatcherWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class))
            // dispatcher 생성자의 @Value "2s" → Duration 변환은 production에선 SpringApplication이
            // beanFactory에 설정하는 ApplicationConversionService가 담당한다. plain runner에는 없어
            // ConversionNotSupportedException으로 실패하므로 같은 변환기를 conversionService 빈으로 제공한다.
            .withBean(ConfigurableApplicationContext.CONVERSION_SERVICE_BEAN_NAME,
                    ApplicationConversionService.class, ApplicationConversionService::new)
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
                    FakeAiTimelineAppendService.class,
                    DispatcherConsumer.class);

    /** dispatcher 인터페이스를 생성자에서 요구하는 test-only consumer(production {@code TimelineDraftTaskService} 대역). */
    static class DispatcherConsumer {
        DispatcherConsumer(TimelineAiDispatcher dispatcher) {
        }
    }

    @Test
    void modeMissing_defaultsToNoOpDispatcher() {
        // matchIfMissing=true — app.ai.mode 미설정(prod 포함) 시 NoOp이 기본.
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TimelineAiDispatcher.class);
            assertThat(context.getBean(TimelineAiDispatcher.class))
                    .isInstanceOf(NoOpTimelineAiDispatcher.class);
            assertThat(context).doesNotHaveBean(FakeAiTimelineAppendService.class);
        });
    }

    @Test
    void noopMode_wiresNoOpDispatcher() {
        runner.withPropertyValues("app.ai.mode=noop").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(TimelineAiDispatcher.class);
            assertThat(context.getBean(TimelineAiDispatcher.class))
                    .isInstanceOf(NoOpTimelineAiDispatcher.class);
            assertThat(context).doesNotHaveBean(FakeAiTimelineAppendService.class);
        });
    }

    @Test
    void fakeMode_wiresFakeDispatcherWithAsyncProxyAndAppendService() {
        // production property 이름(spring.http.client.*)이 자동설정을 거쳐 builder에 적용되는 경로 그대로
        // fake dispatcher 생성자가 충족되어야 한다.
        runner.withPropertyValues(
                        "app.ai.mode=fake",
                        "spring.http.client.connect-timeout=2s",
                        "spring.http.client.read-timeout=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(TimelineAiDispatcher.class)
                            .hasSingleBean(FakeAiTimelineAppendService.class);
                    TimelineAiDispatcher dispatcher = context.getBean(TimelineAiDispatcher.class);
                    // @Async 배선 회귀 가드 — proxy가 빠지면 dev POST가 callback delay만큼 블로킹된다.
                    // 실제 thread 전환·delay timing은 검증하지 않는다.
                    assertThat(AopUtils.isAopProxy(dispatcher)).isTrue();
                    assertThat(AopProxyUtils.ultimateTargetClass(dispatcher))
                            .isEqualTo(FakeTimelineAiDispatcher.class);
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
                    assertThat(context).doesNotHaveBean(FakeAiTimelineAppendService.class);
                });
    }

    @Test
    void unknownMode_failsContext() {
        // fail-fast 경로 고정: 오타 → noop/fake/http 어느 조건도 매칭 안 됨 → dispatcher 빈 부재로 consumer
        // 주입 실패가 root cause여야 한다(무관한 오류가 아니라 이 경로임을 못박음).
        runner.withPropertyValues("app.ai.mode=fak")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(NoSuchBeanDefinitionException.class)
                        .hasMessageContaining("TimelineAiDispatcher"));
    }
}
