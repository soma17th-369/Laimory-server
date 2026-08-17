package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 계정 상태 leaf 계약(#305): isActive는 ACTIVE existence 단일 조회, 탈퇴 전이는 조건부 UPDATE 영향
 * 행 수(1=승자)로 판정, findStatus는 전이 실패 뒤 202/401 분류용 fresh 조회. 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    private static final long USER_ID = 42L;
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 8, 17, 12, 0);

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserAccountService userAccountService;

    @Test
    void isActive_delegatesToActiveExistenceQuery() {
        when(userRepository.existsByUserIdAndStatus(USER_ID, UserStatus.ACTIVE)).thenReturn(true);

        assertThat(userAccountService.isActive(USER_ID)).isTrue();
    }

    @Test
    void isActive_missingOrWithdrawn_isFalse() {
        // 회원 없음과 WITHDRAWAL_PENDING을 구분하지 않는다 — 둘 다 비활성이다(존재 비노출 계약의 전제).
        when(userRepository.existsByUserIdAndStatus(USER_ID, UserStatus.ACTIVE)).thenReturn(false);

        assertThat(userAccountService.isActive(USER_ID)).isFalse();
    }

    @Test
    void transitionToWithdrawalPending_affectedRowDecidesWinner() {
        when(userRepository.transitionToWithdrawalPending(USER_ID, REQUESTED_AT))
                .thenReturn(1)
                .thenReturn(0);

        assertThat(userAccountService.transitionToWithdrawalPending(USER_ID, REQUESTED_AT)).isTrue();
        assertThat(userAccountService.transitionToWithdrawalPending(USER_ID, REQUESTED_AT)).isFalse();
    }

    @Test
    void findStatus_mapsRowStatus_orEmptyWhenMissing() {
        User user = User.of(Provider.KAKAO, "sub-1", null, null);
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user))
                .thenReturn(Optional.empty());

        assertThat(userAccountService.findStatus(USER_ID)).contains(UserStatus.ACTIVE);
        assertThat(userAccountService.findStatus(USER_ID)).isEmpty();
    }
}
