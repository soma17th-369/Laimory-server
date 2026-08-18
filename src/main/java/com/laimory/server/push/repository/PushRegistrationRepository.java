package com.laimory.server.push.repository;

import com.laimory.server.push.entity.PushRegistration;
import com.laimory.server.push.service.SubjectInstallation;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
     *
     * <p>owner 교체와 수신거부 token hash 교체가 이 한 문장 안에서 함께 일어난다 — 계정 전환 뒤 이전
     * owner가 보유한 token으로 현재 owner의 동의를 철회할 수 없다. token을 보내지 않는 구버전 요청은
     * 기존 hash를 {@code NULL}로 되돌린다(수신거부 수단이 없는 설치는 광고 발송 대상에서 빠진다).
     *
     * <p>{@code opt_out_token_hash}에는 UNIQUE를 두지 않는다 — 이 문장의 충돌 권위는 FID 단일 UNIQUE
     * 하나여야 한다. 두 번째 unique key가 있으면 같은 token으로 새 FID를 등록할 때 MySQL이 어느 행을
     * 갱신할지 보장하지 않아, 새 FID가 저장되지 않고 죽은 FID만 남는 경로가 생긴다.
     */
    @Modifying
    @Transactional
    @Query(value = "insert into push_registrations "
            + "(subject_id, firebase_installation_id, opt_out_token_hash, last_registered_at, created_at, updated_at) "
            + "values (:subjectId, :fid, :optOutTokenHash, :now, :now, :now) "
            + "on duplicate key update subject_id = :subjectId, opt_out_token_hash = :optOutTokenHash, "
            + "last_registered_at = :now, updated_at = :now",
            nativeQuery = true)
    void upsert(@Param("subjectId") String subjectId, @Param("fid") String firebaseInstallationId,
                @Param("optOutTokenHash") String optOutTokenHash, @Param("now") LocalDateTime now);

    /** callback task owner의 활성 설치 전체 발송 대상 조회(FID만 — 엔티티 로드 불필요). */
    @Query("select p.firebaseInstallationId from PushRegistration p where p.subjectId = :subjectId")
    List<String> findAllFirebaseInstallationIdsBySubjectId(@Param("subjectId") UUID subjectId);

    /** 정보성 발송의 subject batch 대상 조회 — 수신거부 token 유무와 무관하게 활성 설치 전부다. */
    @Query("select new com.laimory.server.push.service.SubjectInstallation(p.subjectId, p.firebaseInstallationId) "
            + "from PushRegistration p where p.subjectId in :subjectIds")
    List<SubjectInstallation> findAllBySubjectIdIn(@Param("subjectIds") Collection<UUID> subjectIds);

    /**
     * 광고성 발송의 subject batch 대상 조회 — 수신거부 token을 가진 설치만 포함한다.
     * 알림에서 바로 수신거부할 수단이 없는 legacy 설치에는 광고성 알림을 보내지 않는다.
     */
    @Query("select new com.laimory.server.push.service.SubjectInstallation(p.subjectId, p.firebaseInstallationId) "
            + "from PushRegistration p where p.subjectId in :subjectIds and p.optOutTokenHash is not null")
    List<SubjectInstallation> findTokenCapableBySubjectIdIn(@Param("subjectIds") Collection<UUID> subjectIds);

    /**
     * 비로그인 수신거부용 현재 등록 조회 — UNIQUE FID로 정확히 한 행을 잠근다. 같은 transaction 안에서
     * owner subject와 token hash를 함께 읽어야 계정 전환과 경합해도 "지금 이 설치의 owner"에게만
     * 철회가 적용된다.
     */
    @Query(value = "select * from push_registrations where firebase_installation_id = :fid for update",
            nativeQuery = true)
    Optional<PushRegistration> findByFirebaseInstallationIdForUpdate(@Param("fid") String firebaseInstallationId);

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
     * 탈퇴 transaction의 subject 단위 전체 해제(#305) — REQUIRED 전파로 호출자 transaction에 합류하며
     * 미존재 삭제는 0행(멱등)이다. 반환 = 삭제 행 수.
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
