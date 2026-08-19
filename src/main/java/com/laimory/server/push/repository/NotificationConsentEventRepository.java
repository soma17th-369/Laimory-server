package com.laimory.server.push.repository;

import com.laimory.server.push.entity.NotificationConsentEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * notification_consent_events 레포 — append-only 증적. UPDATE/DELETE 메서드를 두지 않는다.
 *
 */
public interface NotificationConsentEventRepository extends JpaRepository<NotificationConsentEvent, Long> {

    /** 설정 GET의 최근 처리결과 — 최신순, 같은 시각은 ID 역순으로 안정 정렬한다. */
    @Query("select e from NotificationConsentEvent e "
            + "where e.subjectId = :subjectId and e.occurredAt >= :since "
            + "order by e.occurredAt desc, e.notificationConsentEventId desc")
    List<NotificationConsentEvent> findRecent(@Param("subjectId") UUID subjectId,
                                              @Param("since") LocalDateTime since);
}
