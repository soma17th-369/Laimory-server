package com.laimory.server.user.service;

import com.laimory.server.user.UserStatus;
import com.laimory.server.user.entity.User;
import com.laimory.server.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 회원 계정 상태 leaf 서비스(#305) — 일반 active 조회와 탈퇴 조건부 상태 전이만 담당하며
 * {@link UserRepository}에만 접근한다. 로그인 find-or-create/프로필 조회는 {@link UserService} 소유다.
 *
 * <p>{@link UserAccountAccessService} 구현으로 {@code JwtAuthenticationFilter}의 매 요청 검사와
 * token/refresh 발급 경로가 사용한다. active 상태를 cache하지 않는다 — cache하면 탈퇴 직후 stale
 * 허용 창이 생긴다(#305 §5.3). userId는 예외 message·log에 넣지 않는다.
 */
@Service
@RequiredArgsConstructor
public class UserAccountService implements UserAccountAccessService {

    private final UserRepository userRepository;

    @Override
    public boolean isActive(long userId) {
        return userRepository.existsByUserIdAndStatus(userId, UserStatus.ACTIVE);
    }

    /**
     * 탈퇴 원자 전이 — 상태·탈퇴 시각·provider identity release를 한 조건부 UPDATE로 수행한다.
     * true = 최초 탈퇴 승자(후속 정리를 계속), false = 이미 탈퇴됐거나 회원 없음(호출자가 fresh 조회로 분류).
     */
    public boolean transitionToWithdrawalPending(long userId, LocalDateTime requestedAt) {
        return userRepository.transitionToWithdrawalPending(userId, requestedAt) == 1;
    }

    /** 전이 실패(영향 0행) 뒤 멱등 202와 401을 가르는 fresh 상태 조회. */
    public Optional<UserStatus> findStatus(long userId) {
        return userRepository.findById(userId).map(User::getStatus);
    }

    /**
     * 계정 삭제 finalization의 회원 행 제거(#302 — 완전 소거 확정, tombstone 없음).
     * {@code WITHDRAWAL_PENDING} 행만 지우므로 재가입한 신규 {@code ACTIVE} generation은 영향받지 않는다.
     * 같은 transaction에서 {@code account_erasure_jobs} 행을 먼저 지워야 FK RESTRICT가 풀린다.
     *
     * @return {@code false} = 영향 0행(이미 삭제됐거나 상태 불일치)
     */
    public boolean deleteWithdrawn(long userId) {
        return userRepository.deleteByUserIdAndStatus(userId, UserStatus.WITHDRAWAL_PENDING) == 1;
    }
}
