package com.laimory.server.push.entity;

import com.laimory.server.common.BaseEntity;
import com.laimory.server.push.ScheduledNotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;

/**
 * subject의 예정 알림 종류별 설정과 스케줄 상태. 알림 종류가 늘어도 컬럼이 아니라 행이 늘어난다.
 *
 * <p>{@code nextDueAt}과 {@code notificationTime}은 {@code Asia/Seoul} 벽시계 계약이다.
 *
 * <p>claim은 native {@code FOR UPDATE SKIP LOCKED} + 조건 UPDATE라 이 엔티티는 조회 결과와
 * {@code ddl-auto=validate} 검증을 담당한다.
 */
@Entity
@Table(name = "scheduled_notification_preferences")
@Getter
public class ScheduledNotificationPreference extends BaseEntity {

    @EmbeddedId
    private ScheduledNotificationPreferenceId id;

    /** 종류별 수신 여부. */
    @Column(nullable = false)
    private boolean enabled;

    /** 사용자가 고른 KST 발송 시각(분 단위). OFF 상태에서도 보존한다. */
    @Column(name = "notification_time", nullable = false)
    private LocalTime notificationTime;

    /** 다음 예정 occurrence의 KST 벽시계 시각 — worker due 조회 축이다. */
    @Column(name = "next_due_at", nullable = false)
    private LocalDateTime nextDueAt;

    protected ScheduledNotificationPreference() {
    }

    public UUID getSubjectId() {
        return id.getSubjectId();
    }

    public ScheduledNotificationType getNotificationType() {
        return id.getNotificationType();
    }
}
