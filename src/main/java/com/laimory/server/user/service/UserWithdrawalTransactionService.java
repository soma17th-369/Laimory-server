package com.laimory.server.user.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.push.service.DailyNotificationPreferenceService;
import com.laimory.server.push.service.SubjectPreferenceService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탈퇴 DB transaction의 단일 소유자(#305 §5.1). 다른 feature의 repository를 직접 주입하지 않고
 * leaf service들을 합성하며, 모든 단계가 한 transaction으로 commit/rollback된다 — 어느 단계가
 * 실패하면 회원은 {@code ACTIVE}로 남고 부수효과가 없다.
 *
 * <p>순서: ① 조건부 {@code ACTIVE → WITHDRAWAL_PENDING} + 탈퇴 시각 + provider identity release
 * ② subject 해석 ③ 푸시 마스터 OFF ④ 일일 알림 설정 OFF ⑤ userId-only PENDING 삭제 작업
 * insert-if-absent.
 *
 * <p><b>행을 지우지 않는다</b>(#367). refresh 행·FID 등록·두 알림 설정 행은 모두 보존하고, 알림은
 * 삭제 대신 OFF로 바꿔 발송만 차단한다. 탈퇴 credential은 매 요청·발급 전 {@code ACTIVE} 검사가
 * 이미 막으므로 refresh 전량 {@code REVOKED}는 즉시 차단의 필수 조건이 아니고, 운영 요청 경로에서
 * 삭제를 줄이면 transaction 범위와 FK 순서 부담도 사라진다. 보존 행의 물리 삭제는 #302가 소유한다.
 *
 * <p>동시성은 ①의 영향 행 수가 유일한 직렬화 지점이다. 승자만 ②~⑤를 수행하고, 영향 0행은 fresh
 * 조회로 분류한다 — {@code WITHDRAWAL_PENDING}이면 이미 인증을 통과한 동시 탈퇴의 멱등 수렴(202),
 * 회원 없음이면 기존 401 {@code -2001}이다. loser의 ①은 승자의 row lock에 걸려 commit 이후에야
 * 0행을 관측하고, ①이 이 transaction의 첫 문장이라 이후 조회 snapshot도 승자 commit 이후다.
 *
 * <p>두 UPDATE의 0행 예외나 job enqueue 실패는 바깥 {@code @Transactional} 경계에서 회원 전이·
 * identity release·선행 UPDATE까지 전부 rollback한다 — 알림이 켜진 채 탈퇴만 접수되는 상태를
 * 남기지 않는다.
 *
 * <p>S3·Redis·AI 호출은 이 transaction 안에서 하지 않는다. 탈퇴 전에 이미 ACTIVE 검사를 통과한
 * in-flight token 발급이 race로 늦게 저장한 ACTIVE refresh 행은 다음 회전의 ACTIVE 검사에서
 * 거절되며 #302 정리 대상이다(202는 물리적 zero가 아니라 사용·연장 불가를 뜻한다 — §5.2).
 */
@Service
@RequiredArgsConstructor
public class UserWithdrawalTransactionService {

    private final UserAccountService userAccountService;
    private final SubjectMappingService subjectMappingService;
    private final DailyNotificationPreferenceService dailyNotificationPreferenceService;
    private final SubjectPreferenceService subjectPreferenceService;
    private final AccountErasureJobService accountErasureJobService;
    private final Clock clock;

    @Transactional
    public void withdraw(long userId) {
        if (!userAccountService.transitionToWithdrawalPending(userId, LocalDateTime.now(clock))) {
            userAccountService.findStatus(userId)
                    // 회원 행 없음(이미 최종 삭제) — 존재를 노출하지 않는 기존 401 계약으로 수렴.
                    .orElseThrow(() -> new BusinessException(ExceptionType.API_AUTHENTICATION_REQUIRED));
            // WITHDRAWAL_PENDING: 이미 인증을 통과한 동시 탈퇴가 먼저 commit — 멱등 202 수렴(작업은 승자가 완료).
            return;
        }
        // 탈퇴 회원은 일반 request 경로를 다시 타지 않으므로 이 해석이 마지막 lazy rekey 기회다(§4.2).
        UUID subjectId = subjectMappingService.getRequired(userId);
        // 마스터 OFF 하나로 예정 알림과 타임라인 완료 push가 모두 막힌다. 어느 UPDATE든 0행이면 예외가
        // 전파돼 이 transaction 전체가 rollback되므로 중간 상태는 존재하지 않는다.
        subjectPreferenceService.updatePushEnabled(subjectId, false);
        dailyNotificationPreferenceService.updateEnabled(subjectId, false);
        accountErasureJobService.enqueue(userId);
    }
}
