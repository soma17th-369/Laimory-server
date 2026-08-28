package com.laimory.server.timeline.service;

import java.time.Duration;

/**
 * dev 전용 AI 동기 테스트 endpoint({@code app.ai.timeline-test.enabled=true})에서만 소비하는 검증된 설정
 * 스냅샷. 값은 {@link TimelineAiTestConfig}가 기동 시 한 번 검증해 만든다 — 이 record가 존재한다는 것은
 * URL·token·timeout·크기 상한이 형식까지 통과했다는 뜻이다(위반은 컨텍스트 기동 실패).
 *
 * <p>호출자 인증 정보는 담지 않는다 — {@code /t/api} 경로의 인증은 이 feature가 아니라 security 계층이
 * 소유한다. AI 인증 token은 전송해야 해서 원문을 들고 있지만 {@link #toString()}이 이를 제외하므로
 * 로그·예외 메시지에 실려 나가지 않는다.
 */
public record TimelineAiTestProperties(
        String url,
        String aiAuthToken,
        Duration connectTimeout,
        Duration readTimeout,
        int maxRequestBytes,
        int maxResponseBytes) {

    /** 비밀(AI 인증 token)을 제외한다 — record 기본 toString이 값을 노출하지 않게 한다. */
    @Override
    public String toString() {
        return "TimelineAiTestProperties[url=%s, connectTimeout=%s, readTimeout=%s, maxRequestBytes=%d, "
                .formatted(url, connectTimeout, readTimeout, maxRequestBytes)
                + "maxResponseBytes=%d, aiAuthToken=%s]"
                .formatted(maxResponseBytes, aiAuthToken == null ? "absent" : "present");
    }
}
