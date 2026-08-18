package com.laimory.server.push.repository;

import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.entity.ScheduledNotificationPreference;
import com.laimory.server.push.entity.ScheduledNotificationPreferenceId;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
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
     * claim한 occurrence를 처리 완료로 표시하고 <b>현재 시각 이후 첫 occurrence</b>로 옮긴다.
     * 발송한 행과 지연 초과로 건너뛴 행이 같은 문장을 공유한다.
     *
     * <p>{@code last_processed_occurrence_date}는 claim 시각의 날짜가 아니라 방금 처리한 예정
     * occurrence({@code next_due_at})의 날짜다 — D일 21:00 행을 D+1 03:00에 처리해도 D+1 21:00 알림이
     * 남는다. 다음 시각은 오늘 {@code notification_time}이 아직 미래면 오늘, 아니면 다음 날이라
     * 며칠이 밀려 있어도 한 문장으로 현재 이후 첫 occurrence에 도달한다.
     */
    @Modifying
    @Transactional
    @Query(value = "update scheduled_notification_preferences "
            + "set last_processed_occurrence_date = date(next_due_at), "
            + "    next_due_at = if(timestamp(date(:nowKst), notification_time) > :nowKst, "
            + "                     timestamp(date(:nowKst), notification_time), "
            + "                     timestamp(date(:nowKst) + interval 1 day, notification_time)), "
            + "    updated_at = :nowKst "
            + "where notification_type = :notificationType and subject_id in :subjectIds",
            nativeQuery = true)
    int markProcessedAndAdvance(@Param("notificationType") String notificationType,
                                @Param("subjectIds") Collection<String> subjectIds,
                                @Param("nowKst") LocalDateTime nowKst);

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
