package com.laimory.server.user;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 계정 삭제 작업 leaf 서비스(#305) — userId-only PENDING enqueue와 관측 조회의 경계.
 * 자신과 1:1인 {@link AccountErasureJobRepository}에만 접근한다. worker claim/stage 확장은 #302 몫.
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

    /** PENDING backlog 수 — gauge와 HMAC secret 갱신 전 runbook gate가 읽는다. */
    public long countPending() {
        return accountErasureJobRepository.countByStatus(AccountErasureJobStatus.PENDING);
    }

    /** 가장 오래된 PENDING 접수 시각 — oldest-age gauge 기준. 없으면 empty. */
    public Optional<LocalDateTime> findOldestPendingCreatedAt() {
        return accountErasureJobRepository.findOldestCreatedAtByStatus(AccountErasureJobStatus.PENDING);
    }
}
