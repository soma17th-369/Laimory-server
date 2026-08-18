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

    /** 광고 동의 ON — 근거 문서와 동의 시각을 함께 기록한다(이전 철회 시각은 이력으로 남긴다). */
    @Modifying
    @Transactional
    @Query("update NotificationConsent c set c.advertisingPushConsented = true, "
            + "c.advertisingTermDocumentId = :termDocumentId, c.advertisingConsentedAt = :occurredAt "
            + "where c.subjectId = :subjectId")
    int consentAdvertising(@Param("subjectId") UUID subjectId,
                           @Param("termDocumentId") Long termDocumentId,
                           @Param("occurredAt") LocalDateTime occurredAt);

    /**
     * 광고 동의 OFF — 야간 동의도 함께 내린다(야간 ON이면 일반도 ON이어야 하는 불변식).
     * 근거 문서 ID는 지우지 않는다 — 마지막으로 어떤 버전에 동의했는지 증명해야 한다.
     */
    @Modifying
    @Transactional
    @Query("update NotificationConsent c set c.advertisingPushConsented = false, "
            + "c.advertisingWithdrawnAt = :occurredAt, "
            + "c.nightAdvertisingPushConsented = false, "
            + "c.nightWithdrawnAt = case when c.nightAdvertisingPushConsented = true "
            + "then :occurredAt else c.nightWithdrawnAt end "
            + "where c.subjectId = :subjectId")
    int withdrawAdvertising(@Param("subjectId") UUID subjectId, @Param("occurredAt") LocalDateTime occurredAt);

    /** 야간 동의 ON — 일반 광고 동의가 ON인 행만 바꾼다(불변식을 DB 문장 조건으로도 지킨다). */
    @Modifying
    @Transactional
    @Query("update NotificationConsent c set c.nightAdvertisingPushConsented = true, "
            + "c.nightTermDocumentId = :termDocumentId, c.nightConsentedAt = :occurredAt "
            + "where c.subjectId = :subjectId and c.advertisingPushConsented = true")
    int consentNight(@Param("subjectId") UUID subjectId,
                     @Param("termDocumentId") Long termDocumentId,
                     @Param("occurredAt") LocalDateTime occurredAt);

    @Modifying
    @Transactional
    @Query("update NotificationConsent c set c.nightAdvertisingPushConsented = false, "
            + "c.nightWithdrawnAt = :occurredAt where c.subjectId = :subjectId")
    int withdrawNight(@Param("subjectId") UUID subjectId, @Param("occurredAt") LocalDateTime occurredAt);

    /** 탈퇴 transaction 합류용 — 현재 상태 snapshot만 지운다(증적 event는 보존). 0행 허용(멱등). */
    @Modifying
    @Transactional
    @Query("delete from NotificationConsent c where c.subjectId = :subjectId")
    int deleteBySubjectId(@Param("subjectId") UUID subjectId);
}
