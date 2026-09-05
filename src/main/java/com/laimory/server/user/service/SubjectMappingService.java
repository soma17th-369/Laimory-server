package com.laimory.server.user.service;

import com.laimory.server.user.SubjectLookupKeyDeriver;
import com.laimory.server.user.entity.UserSubjectLink;
import com.laimory.server.user.repository.UserSubjectLinkRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * raw userId ↔ subject mapping의 유일한 관문(#282, 계획 §2.3). {@link UserSubjectLinkRepository}와
 * {@link SubjectLookupKeyDeriver}는 이 클래스만 의존한다(arch test로 강제) — raw userId를 받아 lookup
 * key를 만들고 mapping을 다루는 책임이 여기 밖으로 퍼지지 않게 한다.
 *
 * <p>일반 경로에는 {@link #getRequired(long)}만 제공한다. mapping을 자동 생성하거나 raw userId로
 * fallback하지 않으며, 누락은 내부 불변식 위반으로 fail-closed한다. 예외·로그에 userId·lookup
 * key·subject를 담지 않는다.
 *
 * <p><b>캐시 계약(#429)</b>: {@link #getRequired(long)}는 per-host Caffeine 캐시를 탄다. 값이 생성 후
 * 불변이라(rotation의 rekey도 subject는 유지 — 계획 §2.9) 인스턴스 간 무효화가 원천적으로 불필요해
 * 로컬 저장소로 충분하고, 요청당 네트워크가 0이다. 캐시 인터셉터가 {@code @Transactional}보다
 * 바깥이라({@code CacheConfig}의 order) 적중 시 transaction 진입과 repository 호출이 둘 다 생략된다.
 * 우회해야 하는 호출자가 없어 wrapper 없이 여기 직접 단다 — 탈퇴·erasure의 해석도 캐시를 타도
 * 되고, rotation 중 lookup key를 current로 옮기는 최종 보장은 {@code AccountErasureService}의
 * 대상 해석이 한다(유예가 캐시 TTL을 압도해 그 시점엔 사실상 miss다). raw userId를 캐시 키로
 * 쓰는 것은 힙이 유출 산출물이 아니기 때문이며, 이 캐시를 공유 저장소로 승격한다면 HMAC 파생
 * 키잉이 필요하다(#282 가명화 우회 금지).
 */
@Service
@RequiredArgsConstructor
public class SubjectMappingService {

    /** 캐시 이름 — 저장소·상한·TTL 배선은 {@code CacheConfig}의 로컬 매니저가 소유한다. */
    public static final String CACHE_NAME = "subject:mapping";
    /**
     * 현 회원 규모(약 1천)와 TTL당 활성 사용자 수를 넉넉히 덮는 상한(엔트리는 Long→UUID라 1만 개여도
     * 수 MB 미만). TTL은 정합성 수단이 아니라(값 불변) erasure 시점 이전 소멸과 rotation 기간의
     * 주기적 rekey 기회를 보장하는 장치라 ACTIVE 캐시와 같은 15분으로 맞춘다.
     */
    public static final long CACHE_MAX_SIZE = 10_000;
    public static final Duration CACHE_TTL = Duration.ofMinutes(15);

    private static final String CACHE_MANAGER = "localCacheManager";

    private final UserSubjectLinkRepository userSubjectLinkRepository;
    private final SubjectLookupKeyDeriver subjectLookupKeyDeriver;
    private final SubjectMappingMetrics subjectMappingMetrics;

    /**
     * 신규 사용자의 subject mapping을 만든다 — 새 UUIDv4 subject를 생성해 current key lookup key로
     * insert한다. 호출자는 {@link NewUserProvisioner}뿐이며, {@code MANDATORY} 전파로 그 transaction에
     * 합류해 mapping insert 실패가 user insert까지 함께 rollback시킨다(독립 호출 = 계약 위반 → 예외).
     *
     * <p>생성한 subject를 반환한다 — 같은 가입 transaction에서 subject 축 기본 행(푸시 설정)을
     * 만들어야 하고, 그러려면 방금 만든 값을 다시 조회 없이 알아야 하기 때문이다.
     *
     * @return 새로 만든 콘텐츠 subject
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createFor(long userId) {
        var sample = subjectMappingMetrics.start();
        String result = "failed";
        try {
            UUID subjectId = UUID.randomUUID();
            userSubjectLinkRepository.saveAndFlush(UserSubjectLink.of(
                    subjectLookupKeyDeriver.deriveCurrent(userId),
                    subjectId,
                    subjectLookupKeyDeriver.currentVersion()));
            result = "success";
            return subjectId;
        } finally {
            subjectMappingMetrics.recordMapping(sample, "create", result);
        }
    }

    /**
     * 인증 사용자의 subject를 조회한다. current key lookup miss면 snapshot에 previous key가 있는
     * rotation 기간에만 previous로 조회하고, hit면 그 행의 PK·version을 current 값으로 원자 교체한
     * 뒤 반환한다(subject 불변 — 계획 §2.9).
     *
     * <p>캐시 적중은 이 본문을 아예 실행하지 않는다(클래스 주석의 캐시 계약). {@code sync}라
     * 같은 userId의 동시 miss는 한 번만 적재되고, 여기서 던지는 예외는 캐시되지 않고 원형으로
     * 전파된다 — 다음 호출이 다시 조회한다.
     *
     * @throws IllegalStateException mapping 누락 — 자동 생성·raw userId fallback 없이 fail-closed.
     *                               메시지에 userId·lookup key·subject를 담지 않는다.
     */
    @Cacheable(cacheNames = CACHE_NAME, cacheManager = CACHE_MANAGER, sync = true)
    @Transactional
    public UUID getRequired(long userId) {
        var sample = subjectMappingMetrics.start();
        String result = "failed";
        try {
            byte[] currentLookupKey = subjectLookupKeyDeriver.deriveCurrent(userId);
            Optional<UserSubjectLink> current = userSubjectLinkRepository.findById(currentLookupKey);
            if (current.isPresent()) {
                UUID subjectId = requireUuidV4(current.get().getSubjectId());
                result = "success";
                return subjectId;
            }
            Optional<byte[]> previousLookupKey = subjectLookupKeyDeriver.derivePrevious(userId);
            if (previousLookupKey.isPresent()) {
                Optional<UserSubjectLink> previous =
                        userSubjectLinkRepository.findById(previousLookupKey.get());
                if (previous.isPresent()) {
                    // 영향 행 0 = 동시 getRequired가 먼저 교체 — subject는 어느 쪽이든 같으므로 멱등이다.
                    userSubjectLinkRepository.rekey(previousLookupKey.get(), currentLookupKey,
                            subjectLookupKeyDeriver.currentVersion());
                    UUID subjectId = requireUuidV4(previous.get().getSubjectId());
                    result = "rotated";
                    return subjectId;
                }
            }
            result = "missing";
            throw new IllegalStateException("subject mapping missing for authenticated user");
        } finally {
            subjectMappingMetrics.recordMapping(sample, "lookup", result);
        }
    }

    /**
     * 캐시된 해석을 버린다 — 무효화 정합성 때문이 아니라 위생 조치다(값이 불변이라 stale이 틀리지
     * 않는다). 탈퇴 orchestrator는 commit 뒤에, erasure worker는 finalization 뒤에 부른다. 후자는
     * 자기 호출이 적재한 엔트리를 스스로 걷어내 "erasure 이후 캐시가 비어 있다"를 복원하는 것이고,
     * 적재 host와 worker host가 같으므로 로컬 evict로 충분하다. 다른 인스턴스의 잔존 엔트리는
     * ACTIVE gate(공유 Redis)가 앞에서 401로 끊으므로 무해하다.
     */
    @CacheEvict(cacheNames = CACHE_NAME, cacheManager = CACHE_MANAGER)
    public void evictCachedMapping(long userId) {
        // 무효화는 어노테이션이 수행한다 — 본문에 할 일이 없다.
    }

    /**
     * 계정 삭제 finalization의 mapping 제거(#302) — lookup key와 subject가 <b>둘 다</b> 일치할 때만
     * 지운다. {@code MANDATORY} 전파로 finalization transaction에 합류하므로, 콘텐츠 owner 행이
     * 남아 있어 subject FK({@code RESTRICT})가 이 삭제를 거절하면 job·user 삭제까지 함께 rollback된다.
     *
     * <p>rotation 중이어도 current key로 지운다 — 이 시점 직전에 {@link #getRequired(long)}이
     * previous hit를 current로 rekey해 두기 때문이다.
     *
     * @return {@code false} = 영향 0행(이미 지워졌거나 기대 subject와 다름). 호출자는 fail-closed 처리한다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean deleteMapping(long userId, UUID expectedSubjectId) {
        return userSubjectLinkRepository.deleteByLookupKeyAndSubjectId(
                subjectLookupKeyDeriver.deriveCurrent(userId), expectedSubjectId.toString()) == 1;
    }

    private static UUID requireUuidV4(UUID subjectId) {
        if (subjectId == null || subjectId.version() != 4 || subjectId.variant() != 2) {
            throw new IllegalStateException("subject mapping contains an invalid UUIDv4");
        }
        return subjectId;
    }
}
