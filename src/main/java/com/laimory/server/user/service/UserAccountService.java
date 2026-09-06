package com.laimory.server.user.service;

import com.laimory.server.user.UserStatus;
import com.laimory.server.user.entity.User;
import com.laimory.server.user.repository.UserRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * 회원 계정 상태 leaf 서비스(#305) — 일반 active 조회와 탈퇴 조건부 상태 전이만 담당하며
 * {@link UserRepository}에만 접근한다. 로그인 find-or-create/프로필 조회는 {@link UserService} 소유다.
 * userId는 예외 message·log에 넣지 않는다.
 *
 * <p>{@link #isActive}는 공유 Redis 캐시(#429)를 서비스 메서드에 직접 단다(#441 — wrapper 제거).
 * 필터({@code JwtAuthenticationFilter})와 token 발급·회전({@code AuthTokenService})이 같은 프록시를
 * 경유해 한 캐시를 공유한다 — 매 {@code /a/api} 요청·발급의 users PK 조회(요청 고정비)를 대체하고,
 * prod는 WAS 2대가 한 Redis를 공유하므로 탈퇴 evict가 전 인스턴스에 즉시 반영된다(per-host
 * 캐시로는 다른 host의 stale을 못 지운다).
 *
 * <p>캐시 규칙(#429 확정 원칙): <b>ACTIVE=true만</b> 캐시하고 음성(회원 없음/탈퇴)은 캐시하지 않는다
 * ({@code unless}). 무효화는 탈퇴 시 {@link #evictActive} 하나뿐이며 갱신 경로를 만들지 않는다. TTL은
 * 무효화 수단이 아니라 evict 유실 대비 안전망이라 쓰기 시점 고정이다(조회로 연장되지 않음).
 * 탈퇴 커밋·evict 뒤 시작된 검사는 miss → DB로 결정적으로 거절되고, evict 유실·늦은 적재로 stale
 * 엔트리가 남은 창에서만 인증·발급이 한시적으로 통과할 수 있다(#429 "보안 정책 개정"의 허용 범위,
 * 발급·회전 포함은 #441).
 *
 * <p>저장소·직렬화·장애 의미론은 {@code CacheConfig}와 {@code FailSafeCacheErrorHandler}가 소유한다.
 * 요약하면 Redis 장애는 fail-safe-to-DB다 — 저장소 연산 실패는 삼켜 miss로 강등되고, miss 경로의
 * DB 장애는 그대로 전파돼 필터의 fail-closed 500 {@code -500} 계약이 유지된다(warm hit는 DB를
 * 호출하지 않으므로 그 요청에서는 DB 장애가 관측되지 않는다 — 의도된 의미 변화).
 */
@Service
@RequiredArgsConstructor
public class UserAccountService {

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

    private final UserRepository userRepository;

    /** userId의 회원 행이 존재하고 {@code ACTIVE}인지 확인한다. DB 장애는 예외로 전파한다. */
    @Cacheable(cacheNames = CACHE_NAME, cacheManager = CACHE_MANAGER, unless = "#result == false")
    public boolean isActive(long userId) {
        return userRepository.existsByUserIdAndStatus(userId, UserStatus.ACTIVE);
    }

    /**
     * 탈퇴 evict — 공유 Redis DEL이라 전 인스턴스에 즉시 반영된다. 호출자는 DB 커밋이 끝난 뒤에
     * 불러야 한다(커밋 전 evict는 동시 요청의 DB 재적재로 무효가 된다). DEL 실패는 error handler가
     * 삼킨다 — stale은 TTL이 수렴시키고(#429 정책 ⓐ), 탈퇴 202를 캐시 장애로 실패시키지 않는다.
     */
    @CacheEvict(cacheNames = CACHE_NAME, cacheManager = CACHE_MANAGER)
    public void evictActive(long userId) {
        // 무효화는 어노테이션이 수행한다 — 본문에 할 일이 없다.
    }

    /**
     * 탈퇴 원자 전이 — 상태·탈퇴 시각·provider identity release를 한 조건부 UPDATE로 수행한다.
     * true = 최초 탈퇴 승자(후속 정리를 계속), false = 이미 탈퇴됐거나 회원 없음(호출자가 fresh 조회로 분류).
     */
    public boolean transitionToWithdrawalPending(long userId, LocalDateTime requestedAt) {
        return userRepository.transitionToWithdrawalPending(userId, requestedAt) == 1;
    }

    /** 전이 실패(영향 0행) 뒤 멱등 202와 401을 가르는 fresh 상태 조회. */
    public Optional<UserStatus> findStatus(long userId) {
        return userRepository.findById(userId).map(User::getStatus);
    }

    /**
     * 계정 삭제 finalization의 회원 행 제거(#302 — 완전 소거 확정, tombstone 없음).
     * {@code WITHDRAWAL_PENDING} 행만 지우므로 재가입한 신규 {@code ACTIVE} generation은 영향받지 않는다.
     * 같은 transaction에서 {@code account_erasure_jobs} 행을 먼저 지워야 FK RESTRICT가 풀린다.
     *
     * @return {@code false} = 영향 0행(이미 삭제됐거나 상태 불일치)
     */
    public boolean deleteWithdrawn(long userId) {
        return userRepository.deleteByUserIdAndStatus(userId, UserStatus.WITHDRAWAL_PENDING) == 1;
    }
}
