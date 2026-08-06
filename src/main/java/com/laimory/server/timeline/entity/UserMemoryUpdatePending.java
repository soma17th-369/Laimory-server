package com.laimory.server.timeline.entity;

import java.time.Instant;
import java.util.Objects;

/**
 * User Memory 갱신 대기 항목. 저장 커밋 직후 등록되고, worker가 사용자 guard를 잡으면 실제 접수로 넘어간다.
 *
 * <p><b>식별자만 담는다.</b> 접수 body와 base memory 해시는 guard를 잡은 <b>뒤</b> 그 시점의 상태를 읽어
 * 만든다 — 대기 중에 앞선 날짜의 갱신이 User Memory를 바꾸므로, 등록 시점에 조립해 두면 낡은 문서를
 * base로 삼게 되고 대기 자체가 무의미해진다.
 *
 * <p>{@code deadline}을 넘긴 항목은 접수하지 않고 버린다(앞이 계속 막혔다는 뜻).
 */
public record UserMemoryUpdatePending(
        long userId,
        long dailyRecordId,
        Instant deadline
) {

    public UserMemoryUpdatePending {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId는 양수여야 합니다");
        }
        if (dailyRecordId <= 0) {
            throw new IllegalArgumentException("dailyRecordId는 양수여야 합니다");
        }
        Objects.requireNonNull(deadline, "deadline");
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(deadline);
    }
}
