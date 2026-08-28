package com.laimory.server.timeline.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * dev 전용 AI 동기 테스트 endpoint 배선(#394). 저장소 관례대로 {@code @ConditionalOnProperty} 하나가
 * 유일한 스위치이며 <b>기본값이 off</b>라, 미설정 환경에서는 이 설정과 controller·service·client 빈이
 * 전부 없어 경로 자체가 존재하지 않는다(없는 경로와 같은 404 — fail-closed).
 *
 * <p>켠 환경에서는 반대로 <b>fail-fast</b>다 — AI URL이 없거나 형식이 틀리면 첫 호출이 아니라 기동에서
 * 실패한다. dev 테스트 도구가 "열려는 있는데 부르면 터지는" 상태로 남지 않게 하려는 것이다.
 *
 * <p>호출자 인증은 여기서 다루지 않는다 — {@code /t/api} 경로의 Bearer token 검증은 이 feature가 아니라
 * security 계층이 소유한다.
 */
@Configuration
@ConditionalOnProperty(name = "app.ai.timeline-test.enabled", havingValue = "true")
class TimelineAiTestConfig {

    /**
     * AI {@code PIPELINE_TIMEOUT_SEC} 기본값. AI는 이 시간이 끝나면 마지막 확정본을
     * {@code X-Timeline-Timed-Out}과 함께 <b>정상 200</b>으로 돌려주므로, 우리 read timeout이 이보다 짧으면
     * 성공 응답을 받기 직전에 끊어 502로 만든다. 그래서 초과를 기동 시 강제한다.
     */
    static final Duration AI_PIPELINE_TIMEOUT = Duration.ofSeconds(120);

    /** {@code readNBytes(cap + 1)} 오버플로를 막는 상한. 실제 body는 이보다 훨씬 작다. */
    private static final long MAX_BYTES_CEILING = 64L * 1024 * 1024;

    @Bean
    TimelineAiTestProperties timelineAiTestProperties(
            @Value("${app.ai.timeline-test.url:}") String url,
            @Value("${app.ai.timeline-test.ai-auth-token:}") String aiAuthToken,
            @Value("${app.ai.timeline-test.connect-timeout:3s}") Duration connectTimeout,
            @Value("${app.ai.timeline-test.read-timeout:150s}") Duration readTimeout,
            @Value("${app.ai.timeline-test.max-request-bytes:1MB}") DataSize maxRequestBytes,
            @Value("${app.ai.timeline-test.max-response-bytes:1MB}") DataSize maxResponseBytes) {
        return new TimelineAiTestProperties(
                requireAbsoluteHttpUrl(url),
                blankToNull(aiAuthToken),
                requirePositive(connectTimeout, "connect-timeout"),
                requireReadTimeoutOutlastsAiPipeline(requirePositive(readTimeout, "read-timeout")),
                requireByteCap(maxRequestBytes, "max-request-bytes"),
                requireByteCap(maxResponseBytes, "max-response-bytes"));
    }

    private static String requireAbsoluteHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "app.ai.timeline-test.url은 테스트 endpoint 활성화 시 필수입니다(APP_AI_TIMELINE_TEST_URL 미주입).");
        }
        String trimmed = url.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("app.ai.timeline-test.url이 유효한 URI가 아닙니다.", e);
        }
        String scheme = uri.getScheme();
        boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!httpScheme || uri.getHost() == null) {
            throw new IllegalStateException("app.ai.timeline-test.url은 absolute http(s) URL이어야 합니다.");
        }
        return trimmed;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("app.ai.timeline-test." + name + "은(는) 양수여야 합니다.");
        }
        return value;
    }

    private static Duration requireReadTimeoutOutlastsAiPipeline(Duration readTimeout) {
        if (readTimeout.compareTo(AI_PIPELINE_TIMEOUT) <= 0) {
            throw new IllegalStateException(
                    "app.ai.timeline-test.read-timeout(" + readTimeout + ")은 AI PIPELINE_TIMEOUT_SEC("
                            + AI_PIPELINE_TIMEOUT + ")보다 길어야 합니다 — 같거나 짧으면 AI가 "
                            + "X-Timeline-Timed-Out과 함께 돌려주는 정상 응답을 끊게 됩니다.");
        }
        return readTimeout;
    }

    private static int requireByteCap(DataSize value, String name) {
        long bytes = value == null ? 0 : value.toBytes();
        if (bytes <= 0 || bytes > MAX_BYTES_CEILING) {
            throw new IllegalStateException(
                    "app.ai.timeline-test." + name + "은(는) 1B 이상 " + MAX_BYTES_CEILING + "B 이하여야 합니다.");
        }
        return (int) bytes;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
