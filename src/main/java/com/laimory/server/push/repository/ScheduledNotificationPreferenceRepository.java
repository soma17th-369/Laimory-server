package com.laimory.server.push.repository;

import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.entity.ScheduledNotificationPreference;
import com.laimory.server.push.entity.ScheduledNotificationPreferenceId;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** scheduled_notification_preferences 레포 — 종류별 설정과 worker의 occurrence claim을 함께 소유한다. */
public interface ScheduledNotificationPreferenceRepository
        extends JpaRepository<ScheduledNotificationPreference, ScheduledNotificationPreferenceId> {

    @Modifying
    @Transactional
    @Query(value = "insert ignore into scheduled_notification_preferences "
            + "(subject_id, notification_type, enabled, notification_time, next_due_at, created_at, updated_at) "
            + "values (:subjectId, :notificationType, :enabled, :notificationTime, :nextDueAt, :now, :now)",
            nativeQuery = true)
    int insertIfAbsent(@Param("subjectId") String subjectId,
                       @Param("notificationType") String notificationType,
                       @Param("enabled") boolean enabled,
                       @Param("notificationTime") LocalTime notificationTime,
                       @Param("nextDueAt") LocalDateTime nextDueAt,
                       @Param("now") LocalDateTime now);

    /**
     * 생성 직후 재조회 전용 <b>잠금 읽기</b>. 앞선 비잠금 읽기가 이 transaction의 REPEATABLE READ
     * 스냅샷을 고정하기 때문에, 그 사이 다른 transaction이 만들어 commit한 행은 일반 재조회로는 보이지
     * 않는다(insert-if-absent는 최신을 보고 no-op이 되는데 읽기만 과거를 본다). 잠금 읽기는 항상 최신
     * 커밋을 보므로 그 창을 닫는다 — 행이 없던 경로에서만 쓰므로 정상 경로에는 락이 생기지 않는다.
     */
    @Query(value = "select * from scheduled_notification_preferences "
            + "where subject_id = :subjectId and notification_type = :notificationType for update",
            nativeQuery = true)
    Optional<ScheduledNotificationPreference> findForUpdate(@Param("subjectId") String subjectId,
                                                            @Param("notificationType") String notificationType);

    /**
     * due occurrence를 row lock으로 분리한다. 허용 지연을 넘긴 행도 함께 claim한다 — 발송은 하지 않지만
     * 다음 미래 occurrence로 옮겨야 오래된 행이 매분 다시 선택되지 않는다(판정은 worker가 소유).
     */
    @Query(value = "select * from scheduled_notification_preferences "
            + "where notification_type = :notificationType and enabled = true and next_due_at <= :nowKst "
            + "order by next_due_at, subject_id "
            + "limit :limit for update skip locked",
            nativeQuery = true)
    List<ScheduledNotificationPreference> findDueForUpdateSkipLocked(
            @Param("notificationType") String notificationType,
            @Param("nowKst") LocalDateTime nowKst,
            @Param("limit") int limit);

    /**
     * claim한 occurrence를 처리 완료로 표시하고 다음 예정 시각으로 옮긴다. 발송한 행과 지연 초과로
     * 건너뛴 행이 같은 문장을 공유하며, 같은 (occurrence 날짜, 다음 예정 시각) 값을 갖는 행끼리 묶여 온다.
     *
     * <p>두 값은 <b>Java에서 KST로 계산해</b> 넘긴다 — SQL 안에서 {@code date()}/{@code timestamp()}로
     * 파생하지 않는다. JDBC가 {@code LocalDateTime} 파라미터를 connection timezone 기준으로 변환하는데
     * {@code TIME} 컬럼은 변환하지 않아, DB 안에서 둘을 섞어 연산하면 JVM timezone에 따라 결과가 달라진다
     * (UTC JVM에서 날짜 +1일·시각 −9시간). 파라미터만 주고받으면 읽기와 쓰기의 변환이 대칭이라 안전하다.
     */
    @Modifying
    @Transactional
    @Query("update ScheduledNotificationPreference s "
            + "set s.lastProcessedOccurrenceDate = :occurrenceDate, s.nextDueAt = :nextDueAt "
            + "where s.id.notificationType = :notificationType and s.id.subjectId in :subjectIds")
    int markProcessedAndAdvance(@Param("notificationType") ScheduledNotificationType notificationType,
                                @Param("subjectIds") Collection<UUID> subjectIds,
                                @Param("occurrenceDate") LocalDate occurrenceDate,
                                @Param("nextDueAt") LocalDateTime nextDueAt);

    /** 종류별 ON/OFF 전환 — ON 전환은 다음 예정 시각을 함께 다시 계산한다. */
    @Modifying
    @Transactional
    @Query("update ScheduledNotificationPreference s set s.enabled = :enabled, s.nextDueAt = :nextDueAt "
            + "where s.id.subjectId = :subjectId and s.id.notificationType = :notificationType")
    int updateEnabled(@Param("subjectId") UUID subjectId,
                      @Param("notificationType") ScheduledNotificationType notificationType,
                      @Param("enabled") boolean enabled,
                      @Param("nextDueAt") LocalDateTime nextDueAt);

    /** 시각 변경 — OFF 상태에서도 저장하며 다음 예정 시각을 함께 옮긴다(발송 여부는 enabled가 결정). */
    @Modifying
    @Transactional
    @Query("update ScheduledNotificationPreference s set s.notificationTime = :notificationTime, "
            + "s.nextDueAt = :nextDueAt "
            + "where s.id.subjectId = :subjectId and s.id.notificationType = :notificationType")
    int updateNotificationTime(@Param("subjectId") UUID subjectId,
                               @Param("notificationType") ScheduledNotificationType notificationType,
                               @Param("notificationTime") LocalTime notificationTime,
                               @Param("nextDueAt") LocalDateTime nextDueAt);

    /** 탈퇴 transaction 합류용 — subject의 모든 종류 행 삭제(마스터보다 먼저). 0행 허용(멱등). */
    @Modifying
    @Transactional
    @Query("delete from ScheduledNotificationPreference s where s.id.subjectId = :subjectId")
    int deleteAllBySubjectId(@Param("subjectId") UUID subjectId);
}
