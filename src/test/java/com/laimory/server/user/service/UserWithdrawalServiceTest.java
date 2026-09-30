package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * 탈퇴 오케스트레이터의 commit 후 정리 계약.
 *
 * <ul>
 *   <li>캐시 evict(#429)는 transaction 정상 반환(=commit) <b>뒤에만</b>, ACTIVE(공유 Redis) → subject(per-host)
 *       순서로 실행되고, transaction 실패 시에는 실행되지 않는다.</li>
 *   <li>User Memory 미반영 큐(#397)도 commit 뒤에 비운다. subject 해석이 subject 캐시를 적재하므로 그 evict보다
 *       먼저이고, 비우기에 실패해도 탈퇴는 정상 반환한다.</li>
 * </ul>
 */
class UserWithdrawalServiceTest {

    private static final long USER_ID = 11L;

    private UserWithdrawalTransactionService transactionService;
    private UserAccountService userAccountService;
    private SubjectMappingService subjectMappingService;
    private AccountErasureService accountErasureService;
    private UserWithdrawalService service;

    @BeforeEach
    void setUp() {
        transactionService = mock(UserWithdrawalTransactionService.class);
        userAccountService = mock(UserAccountService.class);
        subjectMappingService = mock(SubjectMappingService.class);
        accountErasureService = mock(AccountErasureService.class);
        service = new UserWithdrawalService(
                transactionService, userAccountService, subjectMappingService, accountErasureService);
    }

    @Test
    void withdraw_evictsBothCachesAfterCommit() {
        service.withdraw("v1", USER_ID);

        // commit(정상 반환) 뒤 evict — transaction 안에서 지우면 커밋 전 재적재로 무효가 된다(#429 ③).
        InOrder inOrder = inOrder(transactionService, userAccountService, subjectMappingService);
        inOrder.verify(transactionService).withdraw(USER_ID);
        inOrder.verify(userAccountService).evictActive(USER_ID);
        inOrder.verify(subjectMappingService).evictCachedMapping(USER_ID);
    }

    @Test
    void withdraw_transactionFailure_skipsEvictAndPropagates() {
        Mockito.doThrow(new BusinessException(ExceptionType.API_AUTHENTICATION_REQUIRED))
                .when(transactionService).withdraw(USER_ID);

        assertThatThrownBy(() -> service.withdraw("v1", USER_ID))
                .isInstanceOf(BusinessException.class);

        // rollback/거절 경로에서는 회원이 그대로라 지울 것이 없다.
        verifyNoInteractions(userAccountService, subjectMappingService, accountErasureService);
    }

    @Test
    void withdraw_clearsUserMemoryPendingAfterCommitBeforeSubjectEvict() {
        UUID subjectId = UUID.randomUUID();
        when(accountErasureService.resolveTarget(USER_ID)).thenReturn(subjectId);

        service.withdraw("v1", USER_ID);

        InOrder inOrder = inOrder(transactionService, accountErasureService, subjectMappingService);
        inOrder.verify(transactionService).withdraw(USER_ID);
        inOrder.verify(accountErasureService).clearUserMemoryPending(subjectId);
        inOrder.verify(subjectMappingService).evictCachedMapping(USER_ID);
    }

    @Test
    void withdraw_queueClearFailure_stillCompletesWithdrawal() {
        when(accountErasureService.resolveTarget(USER_ID)).thenReturn(UUID.randomUUID());
        Mockito.doThrow(new IllegalStateException("redis down"))
                .when(accountErasureService).clearUserMemoryPending(Mockito.any());

        service.withdraw("v1", USER_ID);

        // 탈퇴는 이미 commit됐다 — 큐 비우기 실패로 응답을 뒤집지 않고 나머지 정리도 이어서 한다.
        verify(subjectMappingService).evictCachedMapping(USER_ID);
    }
}
