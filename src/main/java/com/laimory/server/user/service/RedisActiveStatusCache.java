package com.laimory.server.user.service;

import com.laimory.server.common.redis.RedisGateway;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code JwtAuthenticationFilter} 전용 ACTIVE 검사 캐시(#429) — 매 {@code /a/api} 요청의 users PK
 * 조회(요청 고정비)를 공유 Redis 캐시로 대체한다. prod는 WAS 2대가 한 Redis를 공유하므로 탈퇴
 * evict(DEL)가 전 인스턴스에 즉시 반영된다(in-memory local evict로는 다른 host의 stale을 못 지운다).
 *
 * <p><b>적용 범위는 필터 경로뿐이다.</b> 발급·회전({@code AuthTokenService})은 DB 직행을 유지한다 —
 * 탈퇴자의 refresh 회전을 막는 유일한 장치가 그 검사라서, 캐시를 공유하면 회전 사슬 1회 종결 보장이
 * 깨진다(#429 "보안 정책 개정"). 이 경계는 arch test({@code AuthContextCacheAccessArchTest})로 고정한다.
 *
 * <p>캐시 규칙(#429 확정 원칙): <b>ACTIVE=true만</b> 캐시하고 음성(회원 없음/탈퇴)은 캐시하지 않는다.
 * 무효화는 탈퇴 시 DEL 하나뿐이며 갱신(SET) 경로를 만들지 않는다. TTL은 무효화 수단이 아니라 evict
 * 유실 대비 안전망이라 쓰기 시점 고정(SET EX — 조회로 연장되지 않음)이다. 탈퇴 직후 stale 인증은
 * 한시적으로 허용된다 — 정확한 노출 정책은 #429 "🔒 보안 정책 개정" 참고.
 *
 * <p>장애 의미론: Redis 장애는 fail-safe-to-DB다 — GET 실패는 miss로 간주해 DB 직행하고(Redis 장애로
 * 인증을 500으로 만들지 않는다), GET이 실패한 요청은 SET도 생략해 장애 중 요청당 Redis 시도를
 * 1회(command timeout 1회분)로 제한한다. SET 실패는 무시, DEL 실패는 TTL 수렴에 맡긴다. 반면 miss 경로의
 * DB 장애는 그대로 전파해 필터의 기존 fail-closed 500 {@code -500} 계약을 유지한다(warm hit는 DB를
 * 호출하지 않으므로 그 요청에서는 DB 장애가 관측되지 않는다 — 의도된 의미 변화). 예외·로그에
 * userId를 담지 않는다.
 */
@Slf4j
@Component
public class RedisActiveStatusCache implements UserAccountAccessService {

    /**
     * logical key는 {@code {feature}:{entity}:{id}} 규칙(persistence knowledge)이다. 값은 존재 표식
     * "1"이며 그 외 값은 hit로 인정하지 않는다 — 손상·비호환 값이 DB 확인 없이 인증되는 것을 막고,
     * miss로 강등된 뒤 정상 적재("1")가 덮어쓴다.
     */
    static final String KEY_PREFIX = "user:active:";
    static final String CACHED_VALUE = "1";
    /**
     * 확정 원칙의 10~30분 안전망 대역에서 access token 수명(15m)과 같은 차수로 고정 — 사용자당 DB
     * 재조회는 TTL당 1회뿐이라 대역 내 어느 값이든 성능 차는 미미하고, 짧은 쪽이 stale 수렴이 빠르다.
     */
    static final Duration TTL = Duration.ofMinutes(15);

    private static final String METER_NAME = "laimory.user.active.cache";

    private final UserAccountService userAccountService;
    private final RedisGateway redisGateway;
    private final MeterRegistry meterRegistry;

    public RedisActiveStatusCache(UserAccountService userAccountService,
                                  RedisGateway redisGateway,
                                  MeterRegistry meterRegistry) {
        this.userAccountService = userAccountService;
        this.redisGateway = redisGateway;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public boolean isActive(long userId) {
        String cached = null;
        boolean redisDown = false;
        try {
            cached = redisGateway.get(KEY_PREFIX + userId);
        } catch (RuntimeException e) {
            // fail-safe-to-DB: Redis 장애를 miss로 간주 — 조용한 401 강등도, Redis발 500도 만들지 않는다.
            redisDown = true;
            log.warn("active cache read failed - falling back to DB: type={}", e.getClass().getName());
        }
        if (CACHED_VALUE.equals(cached)) {
            record("hit");
            return true;
        }
        record(redisDown ? "fallback" : "miss");
        boolean active = userAccountService.isActive(userId);
        // GET이 실패한 장애 중엔 SET을 생략 — 어차피 실패할 시도로 요청당 timeout을 한 번 더 물지 않는다.
        if (active && !redisDown) {
            try {
                redisGateway.set(KEY_PREFIX + userId, CACHED_VALUE, TTL);
            } catch (RuntimeException e) {
                // 적재 실패는 성능 손실로 끝난다(다음 요청이 다시 DB를 읽음).
                log.warn("active cache write failed: type={}", e.getClass().getName());
            }
        }
        return active;
    }

    /**
     * 탈퇴 evict — 공유 Redis DEL이라 전 인스턴스에 즉시 반영된다. 호출자는 DB 커밋이 끝난 뒤에
     * 불러야 한다(커밋 전 evict는 동시 요청의 DB 재적재로 무효가 된다). DEL 실패는 삼킨다 — stale은
     * TTL이 수렴시키고(#429 정책 ⓐ), 탈퇴 202 응답을 캐시 장애로 실패시키지 않는다.
     */
    public void evict(long userId) {
        try {
            redisGateway.delete(KEY_PREFIX + userId);
        } catch (RuntimeException e) {
            log.warn("active cache evict failed - stale entry converges by TTL: type={}",
                    e.getClass().getName());
        }
    }

    private void record(String result) {
        meterRegistry.counter(METER_NAME, "result", result).increment();
    }
}
