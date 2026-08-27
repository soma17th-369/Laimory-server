package com.laimory.server.user.service;

import com.laimory.server.auth.service.RefreshTokenService;
import com.laimory.server.push.service.DailyNotificationPreferenceService;
import com.laimory.server.push.service.PushRegistrationService;
import com.laimory.server.push.service.SubjectPreferenceService;
import com.laimory.server.terms.service.TermAgreementService;
import com.laimory.server.timeline.repository.UserMemoryUpdatePendingStore;
import com.laimory.server.timeline.service.DailyRecordService;
import com.laimory.server.user.AccountErasureJobStatus;
import com.laimory.server.user.UserStatus;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 삭제 단계 구현(#302 PR1) — worker가 claim한 job 하나를 실제로 처리하는 곳이다.
 * 다른 feature의 repository를 직접 주입하지 않고 leaf service들을 합성한다
 * ({@code UserWithdrawalTransactionService} 선례).
 *
 * <p>세 가지 일을 한다.
 * <ol>
 *   <li>{@link #quiesce} — User Memory 미반영 큐를 비운다. <b>아무것도 삭제하지 않는다.</b>
 *       유예를 기다리지 않고 접수 직후 실행되며, 이게 없으면 일일 User Memory 배치가 탈퇴 subject의
 *       기록을 계속 AI로 보낸다(그 배치는 subject만 알고 회원 상태를 볼 수 없다).</li>
 *   <li>{@link #deleteOwnerRows} — 콘텐츠 graph를 제외한 owner 행을 지운다(짧은 transaction).</li>
 *   <li>{@link #finalizeErasure} — mapping·job·user를 한 transaction에서 지운다.</li>
 * </ol>
 *
 * <p><b>PR1 범위</b>: 콘텐츠 graph({@code daily_records}·Item·draft source)와 S3 객체는 후속 PR이
 * 소유한다. 그래서 콘텐츠가 있는 회원은 {@link #finalizeErasure}의 mapping 삭제가 subject FK
 * {@code RESTRICT}에 막혀 transaction 전체가 rollback되고 job이 durable하게 남는다 — 반쪽 삭제 상태가
 * 생기지 않는 fail-closed 성질이다.
 *
 * <p>로그에 userId·subjectId·jobId를 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountErasureService {

    /** record id를 한 번에 다 읽지 않기 위한 페이지 크기 — 큐 비우기의 ZREM 단위이기도 하다. */
    private static final int RECORD_ID_PAGE_SIZE = 500;

    private final UserAccountService userAccountService;
    private final SubjectMappingService subjectMappingService;
    private final AccountErasureJobService accountErasureJobService;
    private final DailyRecordService dailyRecordService;
    // 미반영 큐를 소유한 leaf service가 없어 store를 직접 합성한다(Redis 접근은 RedisGateway 경유라
    // arch rule을 어기지 않는다). 큐에 넣는 쪽은 UserMemoryUpdateWorker, 빼는 쪽은 결과 endpoint와 여기다.
    private final UserMemoryUpdatePendingStore userMemoryUpdatePendingStore;
    private final UserMemoryService userMemoryService;
    private final DailyNotificationPreferenceService dailyNotificationPreferenceService;
    private final SubjectPreferenceService subjectPreferenceService;
    private final PushRegistrationService pushRegistrationService;
    private final RefreshTokenService refreshTokenService;
    private final TermAgreementService termAgreementService;

    /**
     * 처리 대상이 맞는지 확인하고 subject를 해석한다. 탈퇴 회원은 일반 요청 경로를 다시 타지 않으므로
     * 이 호출이 rotation 중 mapping을 current로 옮기는 마지막 기회이기도 하다.
     *
     * @return 해석한 subject. 회원 상태가 {@code WITHDRAWAL_PENDING}이 아니면 비어 있다
     * @throws IllegalStateException mapping 누락 — 대상을 특정할 수 없으므로 fail-closed
     */
    public UUID resolveTarget(long userId) {
        if (userAccountService.findStatus(userId).filter(UserStatus.WITHDRAWAL_PENDING::equals).isEmpty()) {
            throw new IllegalStateException("account erasure target is not withdrawal-pending");
        }
        return subjectMappingService.getRequired(userId);
    }

    /**
     * User Memory 미반영 큐에서 이 subject의 member를 걷어낸다. 큐 member가
     * {@code {subject}:{recordId}}라 record id가 필요하고, 존재하지 않는 member의 {@code ZREM}은
     * no-op이라 record id 전체를 넘기는 superset 호출이 안전하다.
     *
     * <p>Redis 전용이라 DB transaction 밖에서 실행한다. 중간에 실패하면 단계 전이를 하지 않으므로
     * 다음 실행이 같은 일을 다시 한다(멱등).
     */
    public void quiesce(UUID subjectId) {
        long afterId = 0L;
        while (true) {
            List<Long> recordIds =
                    dailyRecordService.findIdsBySubjectIdAfterId(subjectId, afterId, RECORD_ID_PAGE_SIZE);
            if (recordIds.isEmpty()) {
                return;
            }
            userMemoryUpdatePendingStore.removeAll(subjectId, recordIds);
            afterId = recordIds.get(recordIds.size() - 1);
        }
    }

    /**
     * 콘텐츠 graph를 제외한 owner 행을 지운다. 순서가 강제되는 곳은 하나뿐이다 —
     * 일일 알림 행이 subject 축 설정 행을 FK {@code RESTRICT}로 참조하므로 그 둘은 이 순서여야 한다.
     *
     * <p>미반영 큐 비우기를 한 번 더 한다(멱등) — 정지 이후 삭제까지의 유예 동안 남은 잔여를 흡수하는
     * 이중 방어이며 record 행이 아직 살아 있는 지금이 마지막 기회다.
     */
    @Transactional
    public void deleteOwnerRows(long userId, UUID subjectId) {
        userMemoryService.delete(subjectId);
        dailyNotificationPreferenceService.delete(subjectId);
        subjectPreferenceService.delete(subjectId);
        pushRegistrationService.deleteAll(subjectId);
        refreshTokenService.deleteAllByUserId(userId);
        termAgreementService.deleteAllByUserId(userId);
    }

    /**
     * 마지막 한 transaction — 여기서 실패하면 앞의 모든 변경이 함께 rollback되고 durable job이 남는다.
     *
     * <p>순서에 이유가 있다.
     * <ol>
     *   <li>owner FK가 없는 세 테이블({@code refresh_tokens}·{@code push_registrations}·
     *       {@code term_agreements})을 <b>멱등 재삭제</b>한다. FK가 없어 아래 mapping 삭제의 fence가
     *       적용되지 않으므로, {@code ACTIVE} 검사를 통과하고 늦게 완료된 insert를 여기서 흡수한다.
     *       평시 0행이다.</li>
     *   <li>mapping을 지운다. 콘텐츠 owner 행이 남아 있으면 subject FK {@code RESTRICT}가 이 삭제를
     *       거절해 transaction 전체가 rollback된다 — "콘텐츠를 먼저 지웠다"의 DB 차원 증명이다.</li>
     *   <li>job 행을 지워 {@code users}를 향한 {@code ON DELETE RESTRICT}를 푼다.</li>
     *   <li>회원 행을 지운다(완전 소거 — tombstone 없음).</li>
     * </ol>
     *
     * <p><b>예상 밖 0행은 예외로 보고한다.</b> Spring 선언적 transaction은 예외 전파로만 rollback하므로,
     * 0행을 boolean으로 되돌려주면 그때까지의 DELETE가 그대로 commit돼 "mapping은 지워졌는데 회원 행은
     * 남는" 반쪽 상태가 만들어진다. 특히 마지막 회원 행 삭제가 0행이면 job까지 사라진 뒤라 아무도 그
     * 행을 다시 건드리지 않는다 — 개인정보가 영구히 남는다.
     *
     * @throws AccountErasureConflictException 어느 단계든 영향 0행 — transaction 전체가 rollback되고
     *                                         durable job이 남아 다음 실행이 재시도한다
     */
    @Transactional
    public void finalizeErasure(long jobId, long userId, UUID subjectId) {
        refreshTokenService.deleteAllByUserId(userId);
        pushRegistrationService.deleteAll(subjectId);
        termAgreementService.deleteAllByUserId(userId);

        if (!subjectMappingService.deleteMapping(userId, subjectId)) {
            throw new AccountErasureConflictException("mapping");
        }
        if (!accountErasureJobService.deleteCompleted(jobId, AccountErasureJobStatus.QUIESCED)) {
            throw new AccountErasureConflictException("job");
        }
        if (!userAccountService.deleteWithdrawn(userId)) {
            throw new AccountErasureConflictException("user");
        }
    }
}
