package com.laimory.server.auth.entity;

import com.laimory.server.auth.RefreshTokenStatus;
import com.laimory.server.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * refresh token 저장 행. 원문은 저장하지 않고 SHA-256 hex 해시만 둔다(DB 유출 시 토큰 원문 보호).
 * 회전 계보는 parent_id(soft ref)로 남긴다 — 폐기 범위 판정엔 쓰지 않고(재사용 탐지는 user 전체 폐기)
 * 감사 추적용이다.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
public class RefreshToken extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refresh_token_id")
    private Long refreshTokenId;

    @Column(nullable = false)
    private Long userId;

    /** 원문 토큰의 SHA-256 hex(64자). UNIQUE — 제시 토큰은 해시로 조회한다. */
    @Column(nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private RefreshTokenStatus status;

    /** 회전 이전 토큰의 refresh_token_id(soft ref, 감사용). 최초 발급이면 null. */
    @Column
    private Long parentId;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    protected RefreshToken() {
    }

    private RefreshToken(Long userId, String tokenHash, LocalDateTime expiresAt, Long parentId) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.status = RefreshTokenStatus.ACTIVE;
        this.expiresAt = expiresAt;
        this.parentId = parentId;
    }

    public static RefreshToken issue(Long userId, String tokenHash, LocalDateTime expiresAt, Long parentId) {
        return new RefreshToken(userId, tokenHash, expiresAt, parentId);
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt.isBefore(now);
    }
}
