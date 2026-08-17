package com.laimory.server.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 회원 탈퇴 오케스트레이터(#305). HTTP 경계에서 받은 applicationVersion/userId로
 * {@link UserWithdrawalTransactionService}의 단일 DB transaction을 호출한다 — S3·Redis·AI 정리는
 * 이 흐름에 없다(물리 삭제는 #302 worker 몫).
 */
@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final UserWithdrawalTransactionService userWithdrawalTransactionService;

    /**
     * 탈퇴를 접수한다. 정상 반환 = 논리 탈퇴·credential 폐기·삭제 작업 접수가 commit됐다는 뜻이며
     * 컨트롤러는 202를 반환한다. 회원 없음(이미 최종 삭제)은 기존 401 {@code -2001}로 수렴한다.
     */
    public void withdraw(String applicationVersion, Long userId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        userWithdrawalTransactionService.withdraw(userId);
    }
}
