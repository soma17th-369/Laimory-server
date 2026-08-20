package com.laimory.server.push.entity;

import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * subject의 일일 알림 설정과 스케줄 상태 — subject당 한 행이다.
 *
 * <p>발송 시각은 서버가 고정하므로 컬럼이 아니라 애플리케이션 상수가 소유한다. 이 행이 들고 있는 것은
 * 수신 여부와 <b>다음 예정 occurrence</b>뿐이다. {@code nextDueAt}은 {@code Asia/Seoul} 벽시계 계약이다.
 *
 * <p>두 번째 일일 알림이 생기면 여기에 컬럼이나 판별자를 더하지 않고 새 테이블을 만든다(#321).
 *
 * <p>claim은 native {@code FOR UPDATE SKIP LOCKED} + 조건 UPDATE라 이 엔티티는 조회 결과와
 * {@code ddl-auto=validate} 검증을 담당한다.
 */
@Entity
@Table(name = "daily_notification_preferences")
@Getter
public class DailyNotificationPreference extends BaseEntity {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subject_id", nullable = false, length = 36)
    private UUID subjectId;

    /** 수신 여부. */
    @Column(nullable = false)
    private boolean enabled;

    /** 다음 예정 occurrence의 KST 벽시계 시각 — worker due 조회 축이다. */
    @Column(name = "next_due_at", nullable = false)
    private LocalDateTime nextDueAt;

    protected DailyNotificationPreference() {
    }
}
