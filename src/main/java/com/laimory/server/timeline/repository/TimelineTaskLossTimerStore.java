package com.laimory.server.timeline.repository;

import com.laimory.server.common.redis.RedisGateway;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * draft task 유실 타이머와 유실 처리 잠금의 Redis 데이터 접근 계층(#565).
 *
 * <p>유실 타이머 논리 키 {@code timeline:draft-task:loss-timer:{taskId}}는 값이 없는 트리거다 — 결과 저장·환불로
 * 정상 종결한 경로가 DEL하므로 만료(keyspace {@code expired} 알림)는 결과도 실패 보고도 없이 끝난 task에서만
 * 일어난다. 금액·주인은 크레딧 이력이 권위라 여기에 두지 않는다.
 *
 * <p>잠금 논리 키 {@code timeline:draft-task:loss-lock:{taskId}}는 만료 알림을 함께 받은 서버들 중 한 대만
 * 유실 처리하도록 유실된 task에서만 {@code SET NX}로 만든다. 환경 prefix 부착은 {@link RedisGateway}가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class TimelineTaskLossTimerStore {

    /** 유실 타이머 논리 키 prefix — keyspace 알림 구독 패턴도 이 값으로 만든다. */
    public static final String TIMER_KEY_PREFIX = "timeline:draft-task:loss-timer:";

    private static final String LOCK_KEY_PREFIX = "timeline:draft-task:loss-lock:";
    private static final Duration LOCK_TTL = Duration.ofMinutes(3);
    private static final Pattern TIMER_KEY = Pattern.compile(Pattern.quote(TIMER_KEY_PREFIX)
            + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    private final RedisGateway redis;

    public void start(String taskId, Duration ttl) {
        redis.set(TIMER_KEY_PREFIX + taskId, "", ttl);
    }

    public void cancel(String taskId) {
        redis.delete(TIMER_KEY_PREFIX + taskId);
    }

    /** 유실 처리 잠금을 잡는다 — 반환값이 곧 이 서버가 처리할지 여부다. 잠금은 TTL로만 풀린다(재처리 없음). */
    public boolean tryLock(String taskId) {
        return redis.setIfAbsent(LOCK_KEY_PREFIX + taskId, "", LOCK_TTL);
    }

    /** 만료된 논리 키가 유실 타이머 키와 정확히 일치하면(canonical UUID) taskId를 돌려준다. */
    public static Optional<String> taskIdOf(String logicalKey) {
        var matcher = TIMER_KEY.matcher(logicalKey);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
