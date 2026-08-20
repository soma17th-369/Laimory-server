package com.laimory.server.push.entity;

import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * subject별 FCM 전체 수신 마스터 행. 이 행 하나가 모든 푸시의 최상위 ON/OFF이며, 종류별 설정
 * ({@link ScheduledNotificationPreference})은 이 마스터를 통과한 뒤에만 의미가 있다.
 *
 * <p>새 리텐션 알림이 늘어도 이 테이블에는 컬럼을 추가하지 않는다(종류별 값은 종류 행이 소유).
 * 쓰기는 repository의 native insert-if-absent와 조건 UPDATE로 수행하므로 이 엔티티는 조회와
 * {@code ddl-auto=validate} 검증용 read model이다.
 */
@Entity
@Table(name = "push_preferences")
@Getter
public class PushPreference extends BaseEntity {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subject_id", nullable = false, length = 36)
    private UUID subjectId;

    /** 기본 ON — 기존 알림 동작을 보존한다. */
    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled;

    protected PushPreference() {
    }
}
