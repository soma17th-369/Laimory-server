package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.auth.service.RefreshTokenService;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.service.NotificationConsentService;
import com.laimory.server.push.service.PushPreferenceService;
import com.laimory.server.push.service.PushRegistrationService;
import com.laimory.server.push.service.ScheduledNotificationPreferenceService;
import com.laimory.server.user.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 탈퇴 transaction orchestration 계약(#305 §5.1): CAS 승자만 subject 해석→refresh 전량 폐기→push
 * 삭제→job enqueue를 순서대로 수행, 영향 0행은 fresh 조회로 멱등 202(WITHDRAWAL_PENDING) / 401(없음)
 * 분류, 중간 실패는 전파(rollback은 @Transactional 소유 — 실 DB 검증은 integration). 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class UserWithdrawalTransactionServiceTest {

    private static final long USER_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-08-17T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDateTime LOCAL_NOW = LocalDateTime.now(CLOCK);
    private static final UUID SUBJECT_ID = UUID.fromString("2f2cfa36-52fd-4478-a6f2-b02f0341f1f4");

    @Mock
    private UserAccountService userAccountService;
    @Mock
    private SubjectMappingService subjectMappingService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private PushRegistrationService pushRegistrationService;
    @Mock
    private ScheduledNotificationPreferenceService scheduledNotificationPreferenceService;
    @Mock
    private PushPreferenceService pushPreferenceService;
    @Mock
    private NotificationConsentService notificationConsentService;
    @Mock
    private AccountErasureJobService accountErasureJobService;

    private UserWithdrawalTransactionService newService() {
        return new UserWithdrawalTransactionService(userAccountService, subjectMappingService,
                refreshTokenService, pushRegistrationService, scheduledNotificationPreferenceService,
                pushPreferenceService, notificationConsentService, accountErasureJobService, CLOCK);
    }

    @Test
    void withdraw_casWinner_runsAllStepsWithServerCapturedTime() {
        when(userAccountService.transitionToWithdrawalPending(USER_ID, LOCAL_NOW)).thenReturn(true);
        when(subjectMappingService.getRequired(USER_ID)).thenReturn(SUBJECT_ID);

        newService().withdraw(USER_ID);

        InOrder order = inOrder(userAccountService, subjectMappingService, refreshTokenService,
                pushRegistrationService, scheduledNotificationPreferenceService, pushPreferenceService,
                notificationConsentService, accountErasureJobService);
        order.verify(userAccountService).transitionToWithdrawalPending(USER_ID, LOCAL_NOW);
        order.verify(subjectMappingService).getRequired(USER_ID);
        order.verify(refreshTokenService).revokeAllForUser(USER_ID);
        order.verify(pushRegistrationService).unregisterAllForSubject(SUBJECT_ID);
        // 종류별 설정 → 마스터 순서가 FK RESTRICT 계약이다. 동의 snapshot 삭제는 그 뒤.
        order.verify(scheduledNotificationPreferenceService).deleteAllForSubject(SUBJECT_ID);
        order.verify(pushPreferenceService).deleteForSubject(SUBJECT_ID);
        order.verify(notificationConsentService).deleteStateForSubject(SUBJECT_ID);
        order.verify(accountErasureJobService).enqueue(USER_ID);
        // 승자 경로에서 findStatus 재조회는 없다 — CAS 영향 행 수가 유일한 판정이다.
        verify(userAccountService, never()).findStatus(anyLong());
    }

    @Test
    void withdraw_alreadyWithdrawalPending_convergesIdempotentlyWithoutSideEffects() {
        // 이미 인증을 통과한 동시 탈퇴의 loser — 승자가 정리를 완료했으므로 아무것도 반복하지 않고 202 수렴.
        when(userAccountService.transitionToWithdrawalPending(USER_ID, LOCAL_NOW)).thenReturn(false);
        when(userAccountService.findStatus(USER_ID)).thenReturn(Optional.of(UserStatus.WITHDRAWAL_PENDING));

        assertThatCode(() -> newService().withdraw(USER_ID)).doesNotThrowAnyException();

        verifyNoInteractions(subjectMappingService, refreshTokenService, pushRegistrationService,
                scheduledNotificationPreferenceService, pushPreferenceService, notificationConsentService,
                accountErasureJobService);
    }

    @Test
    void withdraw_missingUser_convergesToExisting401WithoutUserIdInMessage() {
        when(userAccountService.transitionToWithdrawalPending(USER_ID, LOCAL_NOW)).thenReturn(false);
        when(userAccountService.findStatus(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> newService().withdraw(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    // 이미 최종 삭제된 회원 — 무토큰과 같은 401 -2001로 수렴해 존재를 노출하지 않는다.
                    assertThat(e.getExceptionType()).isEqualTo(ExceptionType.API_AUTHENTICATION_REQUIRED);
                    assertThat(e.getMessage()).doesNotContain(String.valueOf(USER_ID));
                });

        verifyNoInteractions(subjectMappingService, refreshTokenService, pushRegistrationService,
                scheduledNotificationPreferenceService, pushPreferenceService, notificationConsentService,
                accountErasureJobService);
    }

    @Test
    void withdraw_subjectResolutionFailure_propagatesWithoutLaterSteps() {
        // 중간 단계 예외는 그대로 전파돼 @Transactional rollback으로 이어진다(부분 상태 금지 — integration 검증).
        when(userAccountService.transitionToWithdrawalPending(USER_ID, LOCAL_NOW)).thenReturn(true);
        IllegalStateException mappingMissing = new IllegalStateException("subject mapping missing");
        when(subjectMappingService.getRequired(USER_ID)).thenThrow(mappingMissing);

        assertThatThrownBy(() -> newService().withdraw(USER_ID)).isSameAs(mappingMissing);

        // 실패 지점 이후 단계는 실행되지 않는다. rollback 자체는 @Transactional 프레임워크 계약 —
        // UserWithdrawalIntegrationTest가 실 DB에서 ACTIVE 잔존(부수효과 0)을 검증한다.
        verifyNoInteractions(refreshTokenService, pushRegistrationService,
                scheduledNotificationPreferenceService, pushPreferenceService, notificationConsentService,
                accountErasureJobService);
        verify(subjectMappingService).getRequired(USER_ID);
        verify(subjectMappingService, never()).createFor(anyLong());
        verify(userAccountService, never()).findStatus(anyLong());
    }
}
