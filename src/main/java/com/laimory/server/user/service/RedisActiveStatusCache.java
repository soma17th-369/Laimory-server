package com.laimory.server.user.service;

import java.time.Duration;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * {@code JwtAuthenticationFilter} 전용 ACTIVE 검사 캐시(#429) — 매 {@code /a/api} 요청의 users PK
 * 조회(요청 고정비)를 공유 Redis 캐시로 대체한다. prod는 WAS 2대가 한 Redis를 공유하므로 탈퇴
 * evict가 전 인스턴스에 즉시 반영된다(per-host 캐시로는 다른 host의 stale을 못 지운다).
 *
 * <p><b>이 클래스가 별도 컴포넌트인 이유는 호출자 분리다.</b> 발급·회전({@code AuthTokenService})은
 * DB 직행을 유지한다 — 탈퇴자의 refresh 회전을 막는 유일한 장치가 그 검사라서, 캐시를 공유하면
 * 회전 사슬 1회 종결 보장이 깨진다(#429 "보안 정책 개정"). 그래서 캐시를
 * {@link SubjectMappingService}처럼 서비스 메서드에 직접 달지 않고 wrapper로 감싸
 * {@link UserAccountService} 직행 경로를 남겨 두며, 이 경계는
 * arch test({@code AuthContextCacheAccessArchTest})로 고정한다.
 *
 * <p>캐시 규칙(#429 확정 원칙): <b>ACTIVE=true만</b> 캐시하고 음성(회원 없음/탈퇴)은 캐시하지 않는다
 * ({@code unless}). 무효화는 탈퇴 시 {@link #evict} 하나뿐이며 갱신 경로를 만들지 않는다. TTL은
 * 무효화 수단이 아니라 evict 유실 대비 안전망이라 쓰기 시점 고정이다(조회로 연장되지 않음).
 * 탈퇴 직후 stale 인증은 한시적으로 허용된다 — 정확한 노출 정책은 #429 "🔒 보안 정책 개정" 참고.
 *
 * <p>저장소·직렬화·장애 의미론은 {@code CacheConfig}와 {@code FailSafeCacheErrorHandler}가 소유한다.
 * 요약하면 Redis 장애는 fail-safe-to-DB다 — 저장소 연산 실패는 삼켜 miss로 강등되고, miss 경로의
 * DB 장애는 그대로 전파돼 필터의 fail-closed 500 {@code -500} 계약이 유지된다(warm hit는 DB를
 * 호출하지 않으므로 그 요청에서는 DB 장애가 관측되지 않는다 — 의도된 의미 변화).
 */
@Component
public class RedisActiveStatusCache implements UserAccountAccessService {

    /**
     * 캐시 이름이 곧 logical key namespace다 — {@code CacheConfig}가 캐시 이름 뒤에 {@code :}를 붙여
     * 키를 만들어 다른 application key와 같은 {@code {feature}:{entity}:{id}} 규칙에 남는다.
     */
    public static final String CACHE_NAME = "user:active";
    /** 실제 Redis 키의 논리 부분({@code user:active:{userId}}) — 운영 점검·테스트가 참조한다. */
    public static final String KEY_PREFIX = CACHE_NAME + ":";
    /**
     * 확정 원칙의 10~30분 안전망 대역에서 access token 수명(15m)과 같은 차수로 고정 — 사용자당 DB
     * 재조회는 TTL당 1회뿐이라 대역 내 어느 값이든 성능 차는 미미하고, 짧은 쪽이 stale 수렴이 빠르다.
     */
    public static final Duration TTL = Duration.ofMinutes(15);

    private static final String CACHE_MANAGER = "activeStatusCacheManager";

    private final UserAccountService userAccountService;

    public RedisActiveStatusCache(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @Override
    @Cacheable(cacheNames = CACHE_NAME, cacheManager = CACHE_MANAGER, unless = "#result == false")
    public boolean isActive(long userId) {
        return userAccountService.isActive(userId);
    }

    /**
     * 탈퇴 evict — 공유 Redis DEL이라 전 인스턴스에 즉시 반영된다. 호출자는 DB 커밋이 끝난 뒤에
     * 불러야 한다(커밋 전 evict는 동시 요청의 DB 재적재로 무효가 된다). DEL 실패는 error handler가
     * 삼킨다 — stale은 TTL이 수렴시키고(#429 정책 ⓐ), 탈퇴 202를 캐시 장애로 실패시키지 않는다.
     */
    @CacheEvict(cacheNames = CACHE_NAME, cacheManager = CACHE_MANAGER)
    public void evict(long userId) {
        // 무효화는 어노테이션이 수행한다 — 본문에 할 일이 없다.
    }
}
