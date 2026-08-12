package com.laimory.server.push.entity;

import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * FCM 푸시 등록 행 — 사용자 한 명의 활성 앱 설치 하나(FID)다. 행 존재가 활성 등록이며 해제·영구 무효는
 * 행 삭제로 표현한다(별도 boolean 상태 없음).
 *
 * <p>쓰기는 전부 repository의 native upsert/조건부 delete로 수행하므로 이 엔티티는 발송 대상 조회와
 * {@code ddl-auto=validate} 검증용 read model이다. FID는 대소문자를 구분하는 opaque 식별자라 컬럼에
 * binary collation({@code utf8mb4_bin})을 둔다 — 비교 의미는 schema가 소유한다.
 */
@Entity
@Table(name = "push_registrations")
@Getter
public class PushRegistration extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "push_registration_id")
    private Long pushRegistrationId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subject_id", nullable = false, length = 36)
    private UUID subjectId;

    /** Firebase Installation ID(FID) — 발송 target. UNIQUE(한 시점 단일 owner — 계정 전환 시 재결합). */
    @Column(nullable = false, length = 255)
    private String firebaseInstallationId;

    /** Android가 최근 FID를 서버에 동기화한 시각(등록 freshness — 후속 stale 정리 정책의 기준). */
    @Column(nullable = false)
    private LocalDateTime lastRegisteredAt;

    protected PushRegistration() {
    }
}
