package com.laimory.server.push.repository;

import com.laimory.server.push.entity.NotificationConsent;
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
 * notification_consents 레포 — 광고성·야간 광고성 수신 동의의 현재 상태 snapshot.
 *
 * <p>행 부재는 미동의다. 신규 행은 native insert-if-absent(기본 OFF)로만 만들어 "없으면 동의로 간주"가
 * 어떤 경로에서도 생기지 않게 한다. 상태 전이는 각각 한 문장이며 호출자 transaction에 합류해
 * append-only event insert와 함께 commit된다.
 *
 * <p>모든 전이는 <b>조건부 UPDATE</b>다. 직전 상태를 미리 읽어 판단하지 않고 영향 행 수로 알아내므로,
 * 읽기와 쓰기 사이에 낀 남의 commit 때문에 "이미 그 상태였다"는 잘못된 증적이 남지 않는다.
 */
public interface NotificationConsentRepository extends JpaRepository<NotificationConsent, UUID> {

    @Modifying
    @Transactional
    @Query(value = "insert ignore into notification_consents "
            + "(subject_id, advertising_push_consented, night_advertising_push_consented, created_at, updated_at) "
            + "values (:subjectId, false, false, :now, :now)",
            nativeQuery = true)
    int insertIfAbsent(@Param("subjectId") String subjectId, @Param("now") LocalDateTime now);

    /** worker의 광고 발송 gate — 행이 없는 subject는 결과에서 빠지고 호출자가 미동의로 해석한다. */
    @Query("select c from NotificationConsent c where c.subjectId in :subjectIds")
    List<NotificationConsent> findAllBySubjectIdIn(@Param("subjectIds") Collection<UUID> subjectIds);

    /**
     * 광고 동의 ON. <b>영향 행 수가 곧 직전 상태</b>다 — 1이면 이 문장이 상태를 바꾼 것이고(APPLIED),
     * 0이면 그 순간 이미 같은 문서로 동의 상태였다는 뜻이다(ALREADY_IN_STATE).
     *
     * <p>판정을 미리 읽지 않고 UPDATE 조건으로 옮기는 이유: 읽기와 쓰기 사이에 남의 commit이 끼면
     * "읽을 때 OFF였다"는 근거가 이미 낡은 값이 된다. UPDATE가 잡는 행 락이 직렬화하므로 별도
     * {@code SELECT ... FOR UPDATE} 없이도 직전 상태를 정확히 알 수 있다.
     */
    @Modifying
    @Transactional
    @Query("update NotificationConsent c set c.advertisingPushConsented = true, "
            + "c.advertisingTermDocumentId = :termDocumentId, c.advertisingConsentedAt = :occurredAt "
            + "where c.subjectId = :subjectId "
            + "and (c.advertisingPushConsented = false or c.advertisingTermDocumentId <> :termDocumentId)")
    int consentAdvertising(@Param("subjectId") UUID subjectId,
                           @Param("termDocumentId") Long termDocumentId,
                           @Param("occurredAt") LocalDateTime occurredAt);

    /**
     * 광고 동의 OFF. 영향 1행 = 내가 껐다, 0행 = 그 순간 이미 OFF였다.
     * 근거 문서 ID는 지우지 않는다 — 마지막으로 어떤 버전에 동의했는지 증명해야 한다.
     */
    @Modifying
    @Transactional
    @Query("update NotificationConsent c set c.advertisingPushConsented = false, "
            + "c.advertisingWithdrawnAt = :occurredAt "
            + "where c.subjectId = :subjectId and c.advertisingPushConsented = true")
    int withdrawAdvertising(@Param("subjectId") UUID subjectId,
                            @Param("occurredAt") LocalDateTime occurredAt);

    /**
     * 야간 동의 ON — 일반 광고 동의가 ON인 행만 바꾼다(불변식을 문장 조건으로도 지킨다).
     * 영향 0행은 "전제 불충족"과 "이미 같은 상태" 두 경우라 호출자가 한 번 더 읽어 분류한다.
     */
    @Modifying
    @Transactional
    @Query("update NotificationConsent c set c.nightAdvertisingPushConsented = true, "
            + "c.nightTermDocumentId = :termDocumentId, c.nightConsentedAt = :occurredAt "
            + "where c.subjectId = :subjectId and c.advertisingPushConsented = true "
            + "and (c.nightAdvertisingPushConsented = false or c.nightTermDocumentId <> :termDocumentId)")
    int consentNight(@Param("subjectId") UUID subjectId,
                     @Param("termDocumentId") Long termDocumentId,
                     @Param("occurredAt") LocalDateTime occurredAt);

    /** 야간 동의 OFF. 영향 1행 = 내가 껐다, 0행 = 그 순간 이미 OFF였다. */
    @Modifying
    @Transactional
    @Query("update NotificationConsent c set c.nightAdvertisingPushConsented = false, "
            + "c.nightWithdrawnAt = :occurredAt "
            + "where c.subjectId = :subjectId and c.nightAdvertisingPushConsented = true")
    int withdrawNight(@Param("subjectId") UUID subjectId, @Param("occurredAt") LocalDateTime occurredAt);

    /** 탈퇴 transaction 합류용 — 현재 상태 snapshot만 지운다(증적 event는 보존). 0행 허용(멱등). */
    @Modifying
    @Transactional
    @Query("delete from NotificationConsent c where c.subjectId = :subjectId")
    int deleteBySubjectId(@Param("subjectId") UUID subjectId);
}
