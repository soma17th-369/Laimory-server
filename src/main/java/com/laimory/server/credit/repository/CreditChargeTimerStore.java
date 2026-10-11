package com.laimory.server.credit.repository;

import com.laimory.server.common.redis.RedisGateway;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 차감 타이머와 환불 잠금의 Redis 데이터 접근 계층(#565).
 *
 * <p>타이머 논리 키 {@code credit:charge-timer:{referenceId}}는 값이 없는 트리거다 — 정상 종결 경로가 DEL하므로
 * 만료(keyspace {@code expired} 알림)는 결과도 실패 보고도 없이 끝난 작업에서만 일어난다. 금액·주인은 DB 기록이
 * 권위라 여기에 두지 않는다.
 *
 * <p>잠금 논리 키 {@code credit:refund-lock:{referenceId}}는 만료 알림을 함께 받은 서버들 중 한 대만 환불하도록
 * 유실된 작업에서만 {@code SET NX}로 만든다. 환경 prefix 부착은 {@link RedisGateway}가 담당한다.
 */
@Component
@RequiredArgsConstructor
public class CreditChargeTimerStore {

    /** 타이머 논리 키 prefix — keyspace 알림 구독 패턴도 이 값으로 만든다. */
    public static final String TIMER_KEY_PREFIX = "credit:charge-timer:";

    private static final String REFUND_LOCK_KEY_PREFIX = "credit:refund-lock:";
    private static final Duration REFUND_LOCK_TTL = Duration.ofMinutes(3);
    private static final Pattern TIMER_KEY = Pattern.compile(Pattern.quote(TIMER_KEY_PREFIX)
            + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");

    private final RedisGateway redis;

    public void start(String referenceId, Duration ttl) {
        redis.set(TIMER_KEY_PREFIX + referenceId, "", ttl);
    }

    public void cancel(String referenceId) {
        redis.delete(TIMER_KEY_PREFIX + referenceId);
    }

    /** 환불 잠금을 잡는다 — 반환값이 곧 이 서버가 처리할지 여부다. 잠금은 TTL로만 풀린다(재처리 없음). */
    public boolean tryLockRefund(String referenceId) {
        return redis.setIfAbsent(REFUND_LOCK_KEY_PREFIX + referenceId, "", REFUND_LOCK_TTL);
    }

    /** 만료된 논리 키가 타이머 키와 정확히 일치하면(canonical UUID) referenceId를 돌려준다. */
    public static Optional<String> referenceIdOf(String logicalKey) {
        var matcher = TIMER_KEY.matcher(logicalKey);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
