package com.laimory.server.push.entity;

import com.laimory.server.push.ScheduledNotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@link ScheduledNotificationPreference}의 복합 키 — (subject, 알림 종류) pair가 곧 정체성이다.
 * 같은 subject가 종류마다 독립된 ON/OFF·시각·스케줄 상태를 갖고, 같은 pair의 중복 행은 DB가 거부한다.
 *
 * <p>JPA 요구사항: {@link Serializable} + no-arg 생성자 + 값 기반 equals/hashCode
 * ({@code TimelineEventItemId} 선례).
 */
@Embeddable
@Getter
public class ScheduledNotificationPreferenceId implements Serializable {

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subject_id", nullable = false, length = 36)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 64)
    private ScheduledNotificationType notificationType;

    protected ScheduledNotificationPreferenceId() {
    }

    public ScheduledNotificationPreferenceId(UUID subjectId, ScheduledNotificationType notificationType) {
        this.subjectId = subjectId;
        this.notificationType = notificationType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ScheduledNotificationPreferenceId that)) {
            return false;
        }
        return Objects.equals(subjectId, that.subjectId) && notificationType == that.notificationType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(subjectId, notificationType);
    }
}
