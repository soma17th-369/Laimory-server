package com.laimory.server.user;

import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 계정 삭제 작업 leaf 서비스(#305) — userId-only PENDING enqueue의 경계.
 * 자신과 1:1인 {@link AccountErasureJobRepository}에만 접근한다. worker claim/stage 확장은 #302 몫.
 *
 * <p>PENDING backlog 관측 지표는 두지 않는다(경보 미부착 지표 금지 원칙) — HMAC secret 갱신 전
 * runbook gate는 수동 SELECT로 PENDING count/최고령 접수 시각을 확인한다.
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
}
