package com.laimory.server.timeline.service;

import com.laimory.server.aidispatch.SessionCreationRateLimiter;
import com.laimory.server.aidispatch.Sleeper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 세션 생성 한도 안에서 AI에 접수시키는 관문(#515). TIMELINE 대기열 worker와 User Memory 배치가 쓴다.
 *
 * <p>호출부는 접수 상태를 쓰기 전에 {@link #acquirePermit()}으로 허가를 받고, 상태를 쓴 뒤 {@link #dispatch}로 접수한다.
 * throttle({@link TimelineAiDispatchThrottledException})은 접수되지 않았음이 확실하므로 같은 token으로 잠시 뒤 다시
 * 보낸다. 대기열에 메시지를 돌려보내지 않고 같은 실행 안에서 재시도하므로 처리 단계를 되돌릴 일이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCreationGate {

    /** 허가 대기 상한. 넘기면 호출부가 접수하지 않는다. */
    static final Duration PERMIT_MAX_WAIT = Duration.ofSeconds(10);
    /** throttle 재시도 간격(시도마다 jitter를 더한다). 길이가 곧 최대 재시도 횟수다. */
    static final List<Duration> THROTTLE_BACKOFFS =
            List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
    private static final long MAX_JITTER_MILLIS = 250;

    private final SessionCreationRateLimiter limiter;
    private final Sleeper sleeper;

    /** 세션 하나를 만들 허가를 {@link #PERMIT_MAX_WAIT} 안에 얻으면 true다. Redis 오류는 그대로 전파한다. */
    public boolean acquirePermit() {
        return limiter.tryAcquire(PERMIT_MAX_WAIT);
    }

    /**
     * 허가를 받은 뒤 {@code call}(접수)을 실행한다. throttle로 거절되면 backoff 뒤 허가를 다시 받아 최대
     * {@code THROTTLE_BACKOFFS.size()}번 더 부른다. 재시도를 다 쓰거나, 재시도 허가를 못 받거나, 대기 중 interrupt되면
     * 마지막 throttle 예외를 던진다 — 호출부는 미접수 확정으로 처리한다. 그 밖의 예외는 그대로 전파한다.
     */
    public void dispatch(Runnable call) {
        for (int attempt = 0; ; attempt++) {
            try {
                call.run();
                return;
            } catch (TimelineAiDispatchThrottledException e) {
                if (attempt >= THROTTLE_BACKOFFS.size()) {
                    throw e;
                }
                try {
                    sleeper.sleep(THROTTLE_BACKOFFS.get(attempt)
                            .plusMillis(ThreadLocalRandom.current().nextLong(MAX_JITTER_MILLIS + 1)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                if (!limiter.tryAcquire(PERMIT_MAX_WAIT)) {
                    throw e;
                }
                log.info("AI 접수 throttle 재시도: retry={}", attempt + 1);
            }
        }
    }
}
