package com.laimory.server.timeline.entity;

import com.laimory.server.common.id.SubjectId;

/**
 * User Memory 갱신이 아직 반영되지 않은 하루. 사용자 guard를 못 잡았거나 AI가 실패를 통보했을 때
 * 큐에 남고, 하루 1회 배치가 다시 시도한다.
 *
 * <p><b>식별자만 담는다.</b> 접수 body와 base memory 지문은 guard를 잡은 <b>뒤</b> 그 시점의 상태를 읽어
 * 만든다 — 밀려 있는 동안 앞선 날짜의 갱신이 User Memory를 바꾸므로, 미리 조립해 두면 낡은 문서를
 * base로 삼게 되고 미루는 것 자체가 무의미해진다.
 *
 * <p>포기 시한은 이 값이 아니라 큐에 처음 기록된 시각(sorted set score)이 정한다 — 재시도로 항목이
 * 다시 기록돼도 시한이 연장되지 않아야 하기 때문이다.
 */
public record UserMemoryUpdatePending(
        SubjectId subjectId,
        long dailyRecordId
) {

    public UserMemoryUpdatePending {
        if (subjectId == null) {
            throw new IllegalArgumentException("subjectId는 필수입니다");
        }
        if (dailyRecordId <= 0) {
            throw new IllegalArgumentException("dailyRecordId는 양수여야 합니다");
        }
    }
}
