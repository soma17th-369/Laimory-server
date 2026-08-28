package com.laimory.server.timeline.service;

import java.time.Duration;

/**
 * dev 전용 AI 동기 테스트 endpoint({@code app.ai.timeline-test.enabled=true})에서만 소비하는 검증된 설정
 * 스냅샷. 값은 {@link TimelineAiTestConfig}가 기동 시 한 번 검증해 만든다 — 이 record가 존재한다는 것은
 * URL·token·timeout·크기 상한이 형식까지 통과했다는 뜻이다(위반은 컨텍스트 기동 실패).
 *
 * <p>호출자 token은 <b>원문을 담지 않는다</b> — SHA-256 digest만 보관해 상수 시간 대조에 쓴다.
 * AI 인증 token은 전송해야 해서 원문을 들고 있지만, {@link #toString()}이 두 비밀을 모두 제외하므로
 * 로그·예외 메시지에 실려 나가지 않는다.
 */
public record TimelineAiTestProperties(
        String url,
        String callerTokenDigest,
        String aiAuthToken,
        Duration connectTimeout,
        Duration readTimeout,
        int maxRequestBytes,
        int maxResponseBytes) {

    /** 비밀(호출자 token digest·AI 인증 token)을 제외한다 — record 기본 toString이 값을 노출하지 않게 한다. */
    @Override
    public String toString() {
        return "TimelineAiTestProperties[url=%s, connectTimeout=%s, readTimeout=%s, maxRequestBytes=%d, "
                .formatted(url, connectTimeout, readTimeout, maxRequestBytes)
                + "maxResponseBytes=%d, aiAuthToken=%s]"
                .formatted(maxResponseBytes, aiAuthToken == null ? "absent" : "present");
    }
}
