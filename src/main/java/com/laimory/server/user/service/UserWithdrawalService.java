package com.laimory.server.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 회원 탈퇴 오케스트레이터(#305). HTTP 경계에서 받은 applicationVersion/userId로
 * {@link UserWithdrawalTransactionService}의 단일 DB transaction을 호출하고, <b>commit이 끝난 뒤</b>
 * 인증 캐시 2종을 evict한다(#429). S3·AI 정리는 이 흐름에 없다(물리 삭제는 #302 worker 몫).
 *
 * <p>evict가 transaction 바깥(이 클래스)인 이유: transaction 안에서 지우면 commit 전에 다른 요청이
 * DB에서 ACTIVE를 다시 읽어 재적재해 evict가 무효가 된다. 정상 반환 = commit 완료이므로 그 직후가
 * 올바른 시점이다. 멱등 202 수렴(이미 탈퇴)에서도 evict는 반복 무해(DEL/invalidate 멱등)하고,
 * transaction 예외 시에는 evict 없이 전파한다(회원이 ACTIVE로 남으므로 지울 것도 없다).
 * evict 실패는 {@code FailSafeCacheErrorHandler}가 삼킨다 — stale은 TTL이 수렴시키고 탈퇴 202를
 * 캐시 장애로 실패시키지 않는다(#429 보안 정책 ⓐ).
 */
@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final UserWithdrawalTransactionService userWithdrawalTransactionService;
    private final RedisActiveStatusCache redisActiveStatusCache;
    private final SubjectMappingService subjectMappingService;

    /**
     * 탈퇴를 접수한다. 정상 반환 = 논리 탈퇴·모든 push 차단(알림 OFF)·삭제 작업 접수가 commit되고
     * 인증 캐시가 evict됐다는 뜻이며(credential 행은 폐기하지 않고 보존한다 — 차단은 요청·발급 전
     * ACTIVE 검사가 담당하되 필터 경로는 evict 뒤 miss부터, #367·#429) 컨트롤러는 202를 반환한다.
     * 회원 없음(이미 최종 삭제)은 기존 401 {@code -2001}로 수렴한다.
     */
    public void withdraw(String applicationVersion, Long userId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        userWithdrawalTransactionService.withdraw(userId);
        // ACTIVE gate가 보안 경계라 먼저 지운다(공유 Redis DEL — 전 인스턴스 즉시). subject는 값
        // 불변이라 위생 evict(자기 인스턴스 한정, 타 인스턴스 잔존은 ACTIVE gate가 앞에서 끊음).
        redisActiveStatusCache.evict(userId);
        subjectMappingService.evictCachedMapping(userId);
    }
}
