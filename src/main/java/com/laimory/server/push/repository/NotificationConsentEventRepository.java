package com.laimory.server.push.repository;

import com.laimory.server.push.NotificationConsentType;
import com.laimory.server.push.entity.NotificationConsentEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * notification_consent_events 레포 — append-only 증적. UPDATE/DELETE 메서드를 두지 않는다.
 *
 * <p>{@code (subject, clientRequestId, consentType)} 조회가 재시도 멱등의 축이다. UNIQUE 경합에서 진
 * 요청은 이 조회로 winner의 event를 되읽어 같은 응답을 돌려준다.
 */
public interface NotificationConsentEventRepository extends JpaRepository<NotificationConsentEvent, Long> {

    Optional<NotificationConsentEvent> findBySubjectIdAndClientRequestIdAndConsentType(
            UUID subjectId, UUID clientRequestId, NotificationConsentType consentType);

    /** 같은 request ID로 접수된 event 전부(일반 철회가 야간 철회를 동반한 경우 2건). */
    List<NotificationConsentEvent> findAllBySubjectIdAndClientRequestId(UUID subjectId, UUID clientRequestId);

    /** 설정 GET의 최근 처리결과 — 최신순, 같은 시각은 ID 역순으로 안정 정렬한다. */
    @Query("select e from NotificationConsentEvent e "
            + "where e.subjectId = :subjectId and e.occurredAt >= :since "
            + "order by e.occurredAt desc, e.notificationConsentEventId desc")
    List<NotificationConsentEvent> findRecent(@Param("subjectId") UUID subjectId,
                                              @Param("since") LocalDateTime since);
}
