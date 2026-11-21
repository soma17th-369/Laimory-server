package com.laimory.server.aidispatch;

import com.laimory.server.common.redis.RedisGateway;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AgentCore 새 세션 생성률 limiter(#515). 접수 1건이 세션 1개를 새로 만들므로(session id = prefix + taskId), 모든
 * 인스턴스의 접수를 Redis 카운터 하나로 묶어 계정·리전 한도(25 TPS) 안에 둔다. TIMELINE 대기열 worker와 User Memory
 * 배치가 같은 카운터를 쓴다.
 *
 * <p>250ms 고정 창마다 {@code INCR}로 세고 창당 {@code L}건까지 허가한다. 반열린 1초 구간은 창 5개에 걸칠 수 있으므로
 * 어느 1초를 잘라도 최대 {@code 5L}건, 평균은 {@code 4L}건/초다. 1초 창에 25를 주면 창 경계를 사이에 두고 1초 안에
 * 최대 50건이 나갈 수 있어 쓰지 않는다. 한도에 걸리면 다음 창 시작까지 기다렸다가(0~50ms jitter) 다시 센다.
 *
 * <p>창 번호는 앱 시계로 정한다. 인스턴스 간 시계 오차(ms 단위)로 생기는 미세 초과는 AgentCore throttle과 그 재시도가
 * 흡수한다. Redis 오류는 그대로 전파한다(fail-closed) — 세지 못하면 보내지 않는다. 카운터 key는 환경 prefix가 붙으므로
 * 같은 AWS 계정을 쓰는 환경끼리는 {@code L}을 나눠 정한다(예: prod 4 + dev 1).
 */
@Component
public class SessionCreationRateLimiter {

    static final long WINDOW_MILLIS = 250;
    /** 창 key 수명. 창(250ms)보다 넉넉히 길면 되고, 지난 창 key는 다시 쓰이지 않는다. */
    static final Duration WINDOW_KEY_TTL = Duration.ofSeconds(2);
    static final String KEY_PREFIX = "ai-dispatch:session-rate:";
    private static final long MAX_JITTER_MILLIS = 50;

    private final RedisGateway redis;
    private final Clock clock;
    private final Sleeper sleeper;
    private final long permitsPerWindow;

    public SessionCreationRateLimiter(RedisGateway redis, Clock clock, Sleeper sleeper,
                                      @Value("${app.ai.dispatch.limiter.permits-per-window:5}") long permitsPerWindow) {
        if (permitsPerWindow < 1) {
            throw new IllegalStateException(
                    "app.ai.dispatch.limiter.permits-per-window는 1 이상이어야 합니다(APP_AI_DISPATCH_LIMITER_PERMITS).");
        }
        this.redis = redis;
        this.clock = clock;
        this.sleeper = sleeper;
        this.permitsPerWindow = permitsPerWindow;
    }

    /**
     * 세션 하나를 만들 허가를 {@code maxWait} 안에 얻으면 true다. 얻지 못하거나 대기 중 interrupt되면 false다
     * (interrupt 상태는 복원한다). 허가는 돌려주지 않는다 — 허가를 받은 뒤 접수하지 않아도 그 창의 몫이 하나 줄 뿐이다.
     */
    public boolean tryAcquire(Duration maxWait) {
        long deadline = clock.millis() + maxWait.toMillis();
        while (true) {
            long now = clock.millis();
            long window = Math.floorDiv(now, WINDOW_MILLIS);
            long count = redis.incrementWithTtlOnCreate(KEY_PREFIX + window, WINDOW_KEY_TTL);
            if (count <= permitsPerWindow) {
                return true;
            }
            long wait = (window + 1) * WINDOW_MILLIS - now + ThreadLocalRandom.current().nextLong(MAX_JITTER_MILLIS + 1);
            if (now + wait > deadline) {
                return false;
            }
            try {
                sleeper.sleep(Duration.ofMillis(wait));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
