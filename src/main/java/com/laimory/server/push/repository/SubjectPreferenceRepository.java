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
            + "(subject_id, push_enabled, created_at, updated_at) "
            + "values (:subjectId, :pushEnabled, :now, :now)",
            nativeQuery = true)
    int insertIfAbsent(@Param("subjectId") String subjectId,
                       @Param("pushEnabled") boolean pushEnabled,
                       @Param("now") LocalDateTime now);

    /** worker가 claim한 subject들의 마스터 상태 batch 조회 — 행이 없는 subject는 결과에서 빠진다. */
    @Query("select p from SubjectPreference p where p.subjectId in :subjectIds")
    List<SubjectPreference> findAllBySubjectIdIn(@Param("subjectIds") Collection<UUID> subjectIds);

    /** 마스터 ON/OFF만 바꾼다 — 종류별 설정값·시각은 건드리지 않는다. 같은 값 재요청은 멱등이다. */
    @Modifying
    @Transactional
    @Query("update SubjectPreference p set p.pushEnabled = :pushEnabled where p.subjectId = :subjectId")
    int updatePushEnabled(@Param("subjectId") UUID subjectId, @Param("pushEnabled") boolean pushEnabled);

    /** 탈퇴 transaction 합류용 — 종류별 행을 먼저 지운 뒤 호출한다(FK RESTRICT). 0행 허용(멱등). */
    @Modifying
    @Transactional
    @Query("delete from SubjectPreference p where p.subjectId = :subjectId")
    int deleteBySubjectId(@Param("subjectId") UUID subjectId);
}
