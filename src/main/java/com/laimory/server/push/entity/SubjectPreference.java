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
 * subject 축 설정 버킷 행. 예정 알림 마스터({@code pushEnabled})와 앱 온보딩 완료 여부
 * ({@code onboardingCompleted})처럼 subject만 들고 읽는 값을 한 행에 모은다. 일일 알림 설정
 * ({@link DailyNotificationPreference})은 이 마스터를 통과한 뒤에만 의미가 있고, 타임라인 완료 통지는
 * 사용자가 시작한 작업의 결과라 이 스위치를 읽지 않는다.
 *
 * <p>새 리텐션 알림이 늘어도 이 테이블에는 컬럼을 추가하지 않는다(알림별 값은 그 알림의 테이블이 소유).
 * 그 규칙은 알림별 값에만 걸리며 {@code onboardingCompleted}처럼 알림이 아닌 subject 축 설정은 이
 * 버킷의 원래 용도다(#382).
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

    /**
     * 앱 온보딩 완료 여부의 단일 권위(#382). 기본 false이며 완료 command만 단방향으로 true로 바꾼다 —
     * 약관 동의 이력이나 기록 존재 여부에서 계산·동기화하지 않는다.
     */
    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted;

    protected SubjectPreference() {
    }
}
