package com.laimory.server.user;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * users 레포. 조회는 (provider, provider_user_id) 유일키 기준.
 *
 * <p>탈퇴 이후의 상태·닉네임 쓰기는 전부 {@code status} 조건부 UPDATE(영향 행 수 반환)다 —
 * read-then-write 엔티티 저장은 탈퇴와 겹친 stale 엔티티가 status/provider identity를 되살릴 수
 * 있어 금지한다(refresh_tokens 레포 선례). bulk UPDATE는 JPA auditing을 우회하므로 updated_at을
 * 직접 갱신한다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByProviderAndProviderUserId(Provider provider, String providerUserId);

    /** {@code /a/api} 매 요청·token 발급 경로의 활성 확인 — 회원 없음과 탈퇴를 구분하지 않는다. */
    boolean existsByUserIdAndStatus(Long userId, UserStatus status);

    /**
     * 탈퇴 원자 전이(#305): {@code ACTIVE → WITHDRAWAL_PENDING} + 탈퇴 시각 기록 + provider identity
     * release(NULL)를 한 문장으로 수행한다. 반환 1 = 최초 탈퇴 승자, 0 = 이미 탈퇴됐거나 회원 없음.
     * 한 UPDATE라서 "탈퇴됐지만 UNIQUE가 안 풀림"/"identity는 풀렸는데 ACTIVE" 부분 상태가 없다.
     */
    @Modifying
    @Transactional // REQUIRED — 탈퇴 transaction(UserWithdrawalTransactionService)에 합류한다
    @Query("update User u set u.status = com.laimory.server.user.UserStatus.WITHDRAWAL_PENDING, "
            + "u.withdrawalRequestedAt = :requestedAt, u.providerUserId = null, "
            + "u.updatedAt = CURRENT_TIMESTAMP "
            + "where u.userId = :userId and u.status = com.laimory.server.user.UserStatus.ACTIVE")
    int transitionToWithdrawalPending(@Param("userId") Long userId,
                                      @Param("requestedAt") LocalDateTime requestedAt);

    /**
     * Kakao 재로그인의 nickname-only 조건부 갱신(#305 §5.4). {@code ACTIVE} 행만 대상이라 탈퇴와 겹친
     * stale 로그인이 탈퇴 행의 status나 NULL 처리된 provider identity를 덮어쓰지 못한다. 탈퇴 행은
     * {@code provider_user_id}가 NULL이라 이 조건에 어차피 매칭되지 않는다(이중 방어). 영향 0행 =
     * 그 사이 탈퇴 — 갱신을 버린다.
     */
    @Modifying
    @Transactional
    @Query("update User u set u.nickname = :nickname, u.updatedAt = CURRENT_TIMESTAMP "
            + "where u.provider = :provider and u.providerUserId = :providerUserId "
            + "and u.status = com.laimory.server.user.UserStatus.ACTIVE")
    int updateNicknameIfActive(@Param("provider") Provider provider,
                               @Param("providerUserId") String providerUserId,
                               @Param("nickname") String nickname);
}
