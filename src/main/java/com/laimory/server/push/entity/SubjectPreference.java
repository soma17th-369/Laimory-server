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
 * subject별 <b>전체 push 마스터</b> 행. 일일 알림 설정({@link DailyNotificationPreference})은 이 마스터를
 * 통과한 뒤에만 의미가 있고, 타임라인 완료 통지도 이 스위치를 읽는다(#367) — OFF가 곧 모든 발송 차단이라야
 * 탈퇴가 FID·설정 행을 지우지 않고도 push를 막을 수 있다.
 *
 * <p>새 리텐션 알림이 늘어도 이 테이블에는 컬럼을 추가하지 않는다(알림별 값은 그 알림의 테이블이 소유).
 * 쓰기는 repository의 native insert-if-absent와 조건 UPDATE로 수행하므로 이 엔티티는 조회와
 * {@code ddl-auto=validate} 검증용 read model이다.
 */
@Entity
@Table(name = "subject_preferences")
@Getter
public class SubjectPreference extends BaseEntity {

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subject_id", nullable = false, length = 36)
    private UUID subjectId;

    /** 기본 ON — 기존 알림 동작을 보존한다. */
    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled;

    protected SubjectPreference() {
    }
}
