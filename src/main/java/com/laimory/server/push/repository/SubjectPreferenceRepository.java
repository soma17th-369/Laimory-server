package com.laimory.server.push.repository;

import com.laimory.server.push.entity.SubjectPreference;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * subject_preferences 레포. 신규 행 쓰기는 native insert-if-absent 한 문장이라 read-then-insert 경합이
 * UNIQUE 예외로 새지 않는다(행을 만드는 것은 가입 transaction뿐이고 rollout backfill이 같은 의미의
 * 운영 SQL을 쓴다 — 설정 쓰기는 행을 만들지 않는다).
 * native INSERT는 JPA auditing을 우회하므로 감사 timestamp를 직접 채운다.
 */
public interface SubjectPreferenceRepository extends JpaRepository<SubjectPreference, UUID> {

    @Modifying
    @Transactional
    @Query(value = "insert ignore into subject_preferences "
            + "(subject_id, push_enabled, onboarding_completed, created_at, updated_at) "
            + "values (:subjectId, :pushEnabled, :onboardingCompleted, :now, :now)",
            nativeQuery = true)
    int insertIfAbsent(@Param("subjectId") String subjectId,
                       @Param("pushEnabled") boolean pushEnabled,
                       @Param("onboardingCompleted") boolean onboardingCompleted,
                       @Param("now") LocalDateTime now);

    /** worker가 claim한 subject들의 마스터 상태 batch 조회 — 행이 없는 subject는 결과에서 빠진다. */
    @Query("select p from SubjectPreference p where p.subjectId in :subjectIds")
    List<SubjectPreference> findAllBySubjectIdIn(@Param("subjectIds") Collection<UUID> subjectIds);

    /** 마스터 ON/OFF만 바꾼다 — 일일 알림 설정값·온보딩 값은 건드리지 않는다. 같은 값 재요청은 멱등이다. */
    @Modifying
    @Transactional
    @Query("update SubjectPreference p set p.pushEnabled = :pushEnabled where p.subjectId = :subjectId")
    int updatePushEnabled(@Param("subjectId") UUID subjectId, @Param("pushEnabled") boolean pushEnabled);

    /**
     * 온보딩 완료 표시(#382) — 알림 설정값은 건드리지 않는 단방향 전이다. 되돌리는 writer는 두지 않는다.
     * 이미 true인 행도 matched row 1이므로 반복 호출이 멱등 성공한다({@code updatePushEnabled}와 같은
     * 계약 — 0행은 값이 같아서가 아니라 행이 없다는 뜻이다).
     */
    @Modifying
    @Transactional
    @Query("update SubjectPreference p set p.onboardingCompleted = true where p.subjectId = :subjectId")
    int markOnboardingCompleted(@Param("subjectId") UUID subjectId);

    /**
     * 마스터 행 삭제 — 일일 알림 행을 먼저 지운 뒤 호출한다(FK RESTRICT). 0행 허용(멱등).
     * 탈퇴는 삭제 대신 OFF로 바뀌었으므로(#367) 프로덕션 호출자가 없다 — #302 물리 삭제용이다.
     */
    @Modifying
    @Transactional
    @Query("delete from SubjectPreference p where p.subjectId = :subjectId")
    int deleteBySubjectId(@Param("subjectId") UUID subjectId);
}
