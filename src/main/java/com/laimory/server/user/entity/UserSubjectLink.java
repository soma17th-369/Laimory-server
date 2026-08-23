package com.laimory.server.user.entity;

import com.laimory.server.user.repository.UserSubjectLinkRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 인증 사용자 ↔ 콘텐츠 subject 매핑 행(#282, 계획 §2.3). raw {@code user_id}를 저장하지 않는다 —
 * PK는 HMAC-SHA-256 lookup key 32바이트이고, subject는 CSPRNG UUIDv4의 canonical 문자열이다.
 *
 * <p>{@code BaseEntity} 미상속 — 계획이 이 테이블에 감사 컬럼·정밀 생성 시각을 두지 않는다고 명시한다
 * (행 자체가 최소 정보 원칙의 대상이다). JPA 연관 매핑 없이 값 컬럼만 둔다(저장소 전역 방침).
 *
 * <p>rotation의 PK 교체는 엔티티 상태 변경이 아니라 {@link UserSubjectLinkRepository#rekey} 벌크
 * UPDATE로만 수행한다 — JPA는 @Id 갱신을 지원하지 않는다.
 */
@Entity
@Table(name = "user_subject_links")
@Getter
public class UserSubjectLink {

    @Id
    @Column(name = "user_lookup_key", columnDefinition = "BINARY(32)")
    private byte[] userLookupKey;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "subject_id", length = 36, nullable = false)
    private UUID subjectId;

    @Column(name = "lookup_key_version", nullable = false)
    private short lookupKeyVersion;

    protected UserSubjectLink() {
    }

    private UserSubjectLink(byte[] userLookupKey, UUID subjectId, short lookupKeyVersion) {
        this.userLookupKey = userLookupKey;
        this.subjectId = subjectId;
        this.lookupKeyVersion = lookupKeyVersion;
    }

    public static UserSubjectLink of(byte[] userLookupKey, UUID subjectId, short lookupKeyVersion) {
        return new UserSubjectLink(userLookupKey, subjectId, lookupKeyVersion);
    }
}
