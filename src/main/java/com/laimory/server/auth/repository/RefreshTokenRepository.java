package com.laimory.server.auth.repository;

import com.laimory.server.auth.RefreshTokenStatus;
import com.laimory.server.auth.entity.RefreshToken;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * refresh_tokens 레포. 상태 전이는 전부 조건부 UPDATE(반환 행 수 확인)로 원자 보장한다 —
 * read-then-write 금지(동시 refresh 레이스에서 둘 다 승자가 되는 것 방지).
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * ACTIVE → ROTATED 원자 claim. 반환 1 = 회전 승자, 0 = 이미 ROTATED/REVOKED(재사용 신호).
     * bulk UPDATE는 JPA auditing을 우회하므로 updated_at을 직접 갱신한다.
     */
    @Modifying
    @Transactional
    @Query("update RefreshToken t set t.status = com.laimory.server.auth.RefreshTokenStatus.ROTATED, "
            + "t.updatedAt = CURRENT_TIMESTAMP "
            + "where t.refreshTokenId = :id and t.status = com.laimory.server.auth.RefreshTokenStatus.ACTIVE")
    int claimRotation(@Param("id") Long refreshTokenId);

    /** 재사용 탐지 시 해당 사용자의 refresh 전체(REVOKED 제외)를 폐기한다. 반환 = 폐기 행 수. */
    @Modifying
    @Transactional
    @Query("update RefreshToken t set t.status = :revoked, t.updatedAt = CURRENT_TIMESTAMP "
            + "where t.userId = :userId and t.status <> :revoked")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("revoked") RefreshTokenStatus revoked);

    default int revokeAllByUserId(Long userId) {
        return revokeAllByUserId(userId, RefreshTokenStatus.REVOKED);
    }

    /** 로그아웃: 해시가 가리키는 토큰만 폐기(멱등 — 미존재/이미 REVOKED면 0행). */
    @Modifying
    @Transactional
    @Query("update RefreshToken t set t.status = :revoked, t.updatedAt = CURRENT_TIMESTAMP "
            + "where t.tokenHash = :tokenHash and t.status <> :revoked")
    int revokeByTokenHash(@Param("tokenHash") String tokenHash, @Param("revoked") RefreshTokenStatus revoked);

    default int revokeByTokenHash(String tokenHash) {
        return revokeByTokenHash(tokenHash, RefreshTokenStatus.REVOKED);
    }

    /**
     * 계정 삭제(#302)의 owner 전량 제거 — 상태와 무관하게 지운다. 탈퇴는 refresh를 폐기하지 않고
     * 보존하므로(#367) 전량이 여기 삭제 대상이다.
     *
     * <p>이 테이블은 {@code users} FK가 없어(기존 방침) mapping 삭제의 FK fence가 적용되지 않는다 —
     * 그래서 finalization transaction이 같은 삭제를 한 번 더 수행해 그 사이 완료된 지연 발급을
     * 흡수한다(멱등, 평시 0행).
     */
    @Modifying
    @Transactional
    @Query("delete from RefreshToken t where t.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
