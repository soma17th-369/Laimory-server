package com.laimory.server.push.repository;

import com.laimory.server.push.entity.PushRegistration;
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
 * push_registrations 레포. 등록·재결합은 native upsert 한 문장으로 원자 보장한다 —
 * read-then-insert + unique 예외 복구 금지(서로 다른 계정의 동시 등록에서 마지막 commit이 단일 owner가
 * 되고, 중간 unique 예외가 API 500으로 새지 않는다).
 */
public interface PushRegistrationRepository extends JpaRepository<PushRegistration, Long> {

    /**
     * FID 등록·갱신·계정 전환 원자 upsert. 신규면 insert, 이미 있으면(같은/다른 owner 모두) 현재
     * 사용자로 owner를 덮고 freshness를 갱신한다. native INSERT는 JPA auditing을 우회하므로
     * 감사 컬럼을 직접 채운다(modified_by는 NULL 유지 — 요청 주체는 access log가 기록).
     */
    @Modifying
    @Transactional
    @Query(value = "insert into push_registrations "
            + "(subject_id, firebase_installation_id, last_registered_at, created_at, updated_at) "
            + "values (:subjectId, :fid, :now, :now, :now) "
            + "on duplicate key update subject_id = :subjectId, last_registered_at = :now, updated_at = :now",
            nativeQuery = true)
    void upsert(@Param("subjectId") String subjectId, @Param("fid") String firebaseInstallationId,
                @Param("now") LocalDateTime now);

    /** callback task owner의 활성 설치 전체 발송 대상 조회(FID만 — 엔티티 로드 불필요). */
    @Query("select p.firebaseInstallationId from PushRegistration p where p.subjectId = :subjectId")
    List<String> findAllFirebaseInstallationIdsBySubjectId(@Param("subjectId") UUID subjectId);

    /** 예정 알림 발송의 subject batch 대상 조회 — 해당 subject들의 활성 설치 FID 전부다. */
    @Query("select p.firebaseInstallationId from PushRegistration p where p.subjectId in :subjectIds")
    List<String> findAllFirebaseInstallationIdsBySubjectIdIn(
            @Param("subjectIds") Collection<UUID> subjectIds);

    /**
     * owner 조건 해제 — (principal에서 해석한 subject, FID)가 함께 일치할 때만 삭제해
     * 계정 전환 뒤 이전 사용자의
     * 늦은 해제가 재결합된 등록을 지우지 않는다. 미존재 삭제는 0행(멱등).
     */
    @Modifying
    @Transactional
    @Query("delete from PushRegistration p where p.subjectId = :subjectId and p.firebaseInstallationId = :fid")
    int deleteBySubjectIdAndFirebaseInstallationId(@Param("subjectId") UUID subjectId,
                                                   @Param("fid") String firebaseInstallationId);

    /**
     * subject 단위 FID 전체 해제 — REQUIRED 전파로 호출자 transaction에 합류하며 미존재 삭제는
     * 0행(멱등)이다. 반환 = 삭제 행 수.
     *
     * <p>탈퇴는 이제 FID를 지우지 않으므로(#367) 프로덕션 호출자가 없다 — #302 물리 삭제 worker를
     * 위해 남겨 둔 메서드다(현재는 테스트 정리에서만 쓰인다).
     */
    @Modifying
    @Transactional
    @Query("delete from PushRegistration p where p.subjectId = :subjectId")
    int deleteAllBySubjectId(@Param("subjectId") UUID subjectId);

    /**
     * FCM이 영구 무효(UNREGISTERED 등)로 판정한 FID 등록 일괄 삭제. repository 메서드 단위의 짧은 별도
     * transaction이라 한 batch 실패가 callback이나 다른 batch를 되돌리지 않는다.
     *
     * <p>{@code registeredAtOrBefore}(발송 대상 조회 snapshot 시각) 조건이 있어야 한다 — FID만으로 지우면
     * snapshot 이후 같은 FID로 갱신된 정상 재등록(만료 등록의 재활성화·앱 재오픈)을 지연 도착한
     * UNREGISTERED 응답이 삭제한다. snapshot보다 최신인 행은 남긴다(보수적 — 무효면 다음 발송이 다시 정리).
     */
    @Modifying
    @Transactional
    @Query("delete from PushRegistration p where p.firebaseInstallationId in :fids "
            + "and p.lastRegisteredAt <= :registeredAtOrBefore")
    int deleteInvalidRegistrations(@Param("fids") Collection<String> firebaseInstallationIds,
                                   @Param("registeredAtOrBefore") LocalDateTime registeredAtOrBefore);
}
