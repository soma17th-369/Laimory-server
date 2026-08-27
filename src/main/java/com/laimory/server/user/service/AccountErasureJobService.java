package com.laimory.server.user.service;

import com.laimory.server.user.AccountErasureJobStatus;
import com.laimory.server.user.entity.AccountErasureJob;
import com.laimory.server.user.repository.AccountErasureJobRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 삭제 작업 leaf 서비스 — 접수(#305)와 worker의 claim·전이·완료(#302) 경계다.
 * 자신과 1:1인 {@link AccountErasureJobRepository}에만 접근한다.
 *
 * <p>claim transaction은 짧다: {@code SKIP LOCKED}로 후보를 잠그고 {@code updated_at} 표식만 갱신한
 * 뒤 commit한다. 실제 삭제 I/O는 이 transaction 밖에서 일어나고 단계 전이는 작업이 끝난 뒤 별도
 * 조건부 UPDATE로 한다 — 그래서 여러 인스턴스가 같은 cron을 돌려도 한 job을 한 번만 잡는다.
 *
 * <p>경계 시각은 전부 호출자(worker)가 계산해 넘긴다 — 이 서비스는 properties도 처리 창 규칙도 모른다.
 *
 * <p>PENDING backlog 관측 지표는 두지 않는다(경보 미부착 지표 금지 원칙) — 만료·{@code MANUAL_REVIEW}
 * 건수를 worker가 ERROR 로그로 남겨 기존 application ERROR 경보에 태운다.
 */
@Service
@RequiredArgsConstructor
public class AccountErasureJobService {

    private final AccountErasureJobRepository accountErasureJobRepository;
    private final Clock clock;

    /**
     * PENDING 삭제 작업 접수 — 탈퇴 transaction에 합류한다. {@code user_id} UNIQUE insert-if-absent라
     * 재시도·동시 요청에도 회원당 한 번만 durable하게 남는다(중복은 원자 no-op).
     */
    public void enqueue(long userId) {
        accountErasureJobRepository.insertIfAbsent(userId, LocalDateTime.now(clock));
    }

    /**
     * 정지 대상 claim — 접수 후 유예 대기가 끝난 {@code PENDING} 행을 잠그고 표식을 갱신한다.
     *
     * @param eligibleBefore {@code now - quiesce-delay} — 이 시각 이전 접수만 정지 대상이다
     * @param staleBefore    {@code now - stale-after} — 이보다 최근에 잡힌 행은 건너뛴다
     */
    @Transactional
    public List<AccountErasureJob> claimForQuiesce(
            LocalDateTime eligibleBefore, LocalDateTime staleBefore, LocalDateTime claimedAt, int limit) {
        return markClaimed(accountErasureJobRepository
                .findQuiesceClaimableForUpdateSkipLocked(eligibleBefore, staleBefore, limit), claimedAt);
    }

    /**
     * 삭제 대상 claim — 처리 창 안에서 오늘 아직 처리하지 않은 {@code QUIESCED} 행을 잠그고
     * {@code updated_at}을 오늘로 갱신한다. 같은 날 재선택을 막고, 실패한 행은 {@code updated_at}이
     * 전날이 되는 다음 날 실행이 다시 잡는다(#365와 같은 규칙).
     *
     * @param windowStart    {@code T-(grace+window) 00:00} — 이보다 오래된 접수는 만료다
     * @param eligibleBefore {@code T-grace 00:00} — 유예가 지난 접수만 대상이다
     * @param todayStart     {@code T 00:00}(KST)
     */
    @Transactional
    public List<AccountErasureJob> claimForDelete(LocalDateTime windowStart, LocalDateTime eligibleBefore,
                                                  LocalDateTime todayStart, LocalDateTime claimedAt, int limit) {
        return markClaimed(accountErasureJobRepository.findDeleteClaimableForUpdateSkipLocked(
                windowStart, eligibleBefore, todayStart, limit), claimedAt);
    }

    private List<AccountErasureJob> markClaimed(List<AccountErasureJob> claimed, LocalDateTime claimedAt) {
        if (claimed.isEmpty()) {
            return List.of();
        }
        accountErasureJobRepository.markClaimed(
                claimed.stream().map(AccountErasureJob::getAccountErasureJobId).toList(), claimedAt);
        return claimed;
    }

    /** 조건부 단계 전이. {@code false} = 기대 상태가 아님(다른 worker가 이미 처리) — 실패가 아니다. */
    @Transactional
    public boolean transition(long jobId, AccountErasureJobStatus expected, AccountErasureJobStatus next) {
        return accountErasureJobRepository.transition(jobId, expected, next, LocalDateTime.now(clock)) == 1;
    }

    /** 사람이 봐야 하는 실패로 격리한다. 이후 claim 대상에서 빠진다. */
    @Transactional
    public boolean markManualReview(long jobId, AccountErasureJobStatus expected) {
        return transition(jobId, expected, AccountErasureJobStatus.MANUAL_REVIEW);
    }

    /**
     * 완료 — job 행을 지워 {@code users}를 향한 {@code ON DELETE RESTRICT}를 푼다.
     * <b>finalization transaction에 합류</b>하며 user 행 삭제보다 먼저 호출해야 한다.
     * {@code false} = 다른 worker가 이미 완료(0행).
     */
    @Transactional
    public boolean deleteCompleted(long jobId, AccountErasureJobStatus expected) {
        return accountErasureJobRepository.deleteByIdAndStatus(jobId, expected) == 1;
    }

    /**
     * 처리 창을 벗어난 미완료 job 수. {@code MANUAL_REVIEW}는 자체 경보가 있어 제외한다.
     *
     * @param windowStart {@code T-(grace+window) 00:00}(KST) — 이보다 오래된 접수는 재시도하지 않는다
     */
    public long countExpired(LocalDateTime windowStart) {
        return accountErasureJobRepository.countExpired(windowStart, AccountErasureJobStatus.MANUAL_REVIEW);
    }

    public long countManualReview() {
        return accountErasureJobRepository.countByStatus(AccountErasureJobStatus.MANUAL_REVIEW);
    }
}
