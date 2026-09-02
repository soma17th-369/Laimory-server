package com.laimory.server.user.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code @CurrentSubject} 해석 전용 subject 매핑 캐시(#429) — 매 콘텐츠 요청의
 * {@code userId → subjectId} 조회(요청 고정비)를 in-memory로 대체한다. 캐시가
 * {@link SubjectMappingService#getRequired} <b>바깥</b>에 있으므로 적중 시 transaction 진입과
 * repository 호출이 둘 다 생략된다(실측 비용의 몸통이 transaction 래퍼 구간 — #251).
 *
 * <p>값은 생성 후 불변이다(key rotation의 rekey도 subject는 유지 — 계획 §2.9). 그래서 인스턴스 간
 * 무효화가 원천적으로 불필요해 prod WAS 2대에서도 per-host Caffeine이 안전하고, 요청당 네트워크가
 * 0이다(ACTIVE 캐시가 Redis인 것과 대비되는 근거 — #429 원칙 4).
 *
 * <p>이 캐시는 요청 고정비 경로({@code CurrentSubjectArgumentResolver})에서만 쓴다. 탈퇴
 * transaction의 subject 해석은 원본 서비스를 직접 타야 한다 — 그 조회가 rotation 기간의 마지막
 * lazy rekey 기회라서(#302의 {@code deleteMapping}이 current key 전제), 캐시 적중이 rekey를
 * 건너뛰면 erasure가 깨진다. 경계는 arch test({@code AuthContextCacheAccessArchTest})로 고정한다.
 *
 * <p>탈퇴 시 {@link #evict}는 자기 인스턴스만 비우는 위생 조치다 — 다른 인스턴스의 잔존 엔트리는
 * 값이 불변이고 ACTIVE gate(공유 Redis)가 앞에서 401로 끊으므로 무해하다. 미스 시 예외(매핑 누락
 * fail-closed 포함)는 캐시하지 않고 그대로 전파한다.
 */
@Component
public class SubjectMappingCache {

    /**
     * 최대 크기는 현 회원 규모(약 1천)와 TTL당 활성 사용자 수를 넉넉히 덮는 상한이다(엔트리는
     * Long→UUID라 1만 개여도 수 MB 미만). TTL은 확정 원칙의 10~30분 안전망 대역에서 ACTIVE 캐시와
     * 같은 15분 — 값이 불변이라 TTL은 정합성 수단이 아니고, erasure(#302) 시점 이전 소멸과 rotation
     * 기간의 주기적 rekey 기회(만료 → miss → getRequired)만 보장하면 된다. 쓰기 시점 고정
     * expireAfterWrite다(expireAfterAccess는 접근마다 만료가 밀려 그 보장이 깨진다).
     */
    static final long MAX_SIZE = 10_000;
    static final Duration TTL = Duration.ofMinutes(15);

    private final SubjectMappingService subjectMappingService;
    private final Cache<Long, UUID> cache;

    public SubjectMappingCache(SubjectMappingService subjectMappingService, MeterRegistry meterRegistry) {
        this.subjectMappingService = subjectMappingService;
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_SIZE)
                .expireAfterWrite(TTL)
                .recordStats()
                .build();
        // 표준 cache.* meter로 적중률을 노출한다(배포 후 재실측 보조 — #429 체크리스트).
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "subject-mapping");
    }

    /**
     * 인증 사용자의 subject를 캐시 우선으로 해석한다. miss는 같은 key끼리 직렬화돼
     * {@link SubjectMappingService#getRequired} 중복 호출 없이 한 번만 적재된다.
     */
    public UUID getRequired(long userId) {
        return cache.get(userId, subjectMappingService::getRequired);
    }

    /** 탈퇴 시 위생 evict — 자기 인스턴스 한정(잔존 무해 근거는 클래스 주석). */
    public void evict(long userId) {
        cache.invalidate(userId);
    }
}
