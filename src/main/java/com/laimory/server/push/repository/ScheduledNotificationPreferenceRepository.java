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
     * claim한 occurrence를 다음 예정 시각으로 옮긴다. 발송한 행과 지연 초과로 건너뛴 행이 같은 문장을
     * 공유하며, 같은 다음 예정 시각을 갖는 행끼리 묶여 온다.
     *
     * <p>전진 값은 <b>Java에서 KST로 계산해</b> 넘긴다 — SQL 안에서 {@code date()}/{@code timestamp()}로
     * 파생하지 않는다. JDBC가 {@code LocalDateTime} 파라미터를 connection timezone 기준으로 변환하는데
     * {@code TIME} 컬럼은 변환하지 않아, DB 안에서 둘을 섞어 연산하면 JVM timezone에 따라 결과가 달라진다
     * (UTC JVM에서 날짜 +1일·시각 −9시간). 파라미터만 주고받으면 읽기와 쓰기의 변환이 대칭이라 안전하다.
     */
    @Modifying
    @Transactional
    @Query("update ScheduledNotificationPreference s set s.nextDueAt = :nextDueAt "
            + "where s.id.notificationType = :notificationType and s.id.subjectId in :subjectIds")
    int advanceNextDueAt(@Param("notificationType") ScheduledNotificationType notificationType,
                         @Param("subjectIds") Collection<UUID> subjectIds,
                         @Param("nextDueAt") LocalDateTime nextDueAt);

    /**
     * 종류별 ON/OFF 전환 — <b>{@code enabled} 한 컬럼만</b> 바꾼다.
     *
     * <p>{@code next_due_at}은 건드리지 않는다. 꺼둔 사이 과거가 된 값은 다시 켰을 때 worker가 허용 지연
     * 초과로 걸러 발송 없이 다음 occurrence로 넘긴다 — 여기서 미리 고치려면 행을 읽어 계산해야 하고,
     * 그 읽기와 쓰기 사이가 벌어지면 시각 변경과 겹쳤을 때 파생값이 자기 입력과 어긋난다.
     */
    @Modifying
    @Transactional
    @Query("update ScheduledNotificationPreference s set s.enabled = :enabled "
            + "where s.id.subjectId = :subjectId and s.id.notificationType = :notificationType")
    int updateEnabled(@Param("subjectId") UUID subjectId,
                      @Param("notificationType") ScheduledNotificationType notificationType,
                      @Param("enabled") boolean enabled);

    /**
     * 시각 변경 — 시각과 그 시각에서 파생되는 다음 예정 시각을 <b>한 문장에서 함께</b> 바꾼다.
     *
     * <p>행을 미리 읽어 계산하지 않는다. 읽기와 쓰기 사이가 벌어지면 다른 설정 변경이나 worker claim이
     * 그 사이에 끼어들어 {@code notification_time}과 {@code next_due_at}이 서로 어긋난 상태가 남는다.
     *
     * @param nextDueAt 새 시각의 다음 미래 occurrence(오늘이 미래면 오늘, 아니면 내일)
     */
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
