package com.laimory.server.user.service;

import com.laimory.server.user.SubjectLookupKeyDeriver;
import com.laimory.server.user.entity.UserSubjectLink;
import com.laimory.server.user.repository.UserSubjectLinkRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
 */
@Service
@RequiredArgsConstructor
public class SubjectMappingService {

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
     * @throws IllegalStateException mapping 누락 — 자동 생성·raw userId fallback 없이 fail-closed.
     *                               메시지에 userId·lookup key·subject를 담지 않는다.
     */
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

    private static UUID requireUuidV4(UUID subjectId) {
        if (subjectId == null || subjectId.version() != 4 || subjectId.variant() != 2) {
            throw new IllegalStateException("subject mapping contains an invalid UUIDv4");
        }
        return subjectId;
    }
}
