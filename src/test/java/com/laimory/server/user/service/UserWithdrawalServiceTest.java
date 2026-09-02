package com.laimory.server.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * 탈퇴 오케스트레이터의 캐시 evict 계약(#429): evict는 transaction 정상 반환(=commit) <b>뒤에만</b>,
 * ACTIVE(공유 Redis) → subject(per-host) 순서로 실행되고, transaction 실패 시에는 실행되지 않는다.
 */
class UserWithdrawalServiceTest {

    private static final long USER_ID = 11L;

    private UserWithdrawalTransactionService transactionService;
    private RedisActiveStatusCache redisActiveStatusCache;
    private SubjectMappingCache subjectMappingCache;
    private UserWithdrawalService service;

    @BeforeEach
    void setUp() {
        transactionService = mock(UserWithdrawalTransactionService.class);
        redisActiveStatusCache = mock(RedisActiveStatusCache.class);
        subjectMappingCache = mock(SubjectMappingCache.class);
        service = new UserWithdrawalService(transactionService, redisActiveStatusCache, subjectMappingCache);
    }

    @Test
    void withdraw_evictsBothCachesAfterCommit() {
        service.withdraw("v1", USER_ID);

        // commit(정상 반환) 뒤 evict — transaction 안에서 지우면 커밋 전 재적재로 무효가 된다(#429 ③).
        InOrder inOrder = inOrder(transactionService, redisActiveStatusCache, subjectMappingCache);
        inOrder.verify(transactionService).withdraw(USER_ID);
        inOrder.verify(redisActiveStatusCache).evict(USER_ID);
        inOrder.verify(subjectMappingCache).evict(USER_ID);
    }

    @Test
    void withdraw_transactionFailure_skipsEvictAndPropagates() {
        Mockito.doThrow(new BusinessException(ExceptionType.API_AUTHENTICATION_REQUIRED))
                .when(transactionService).withdraw(USER_ID);

        assertThatThrownBy(() -> service.withdraw("v1", USER_ID))
                .isInstanceOf(BusinessException.class);

        // rollback/거절 경로에서는 회원이 그대로라 지울 것이 없다.
        verifyNoInteractions(redisActiveStatusCache, subjectMappingCache);
    }
}
