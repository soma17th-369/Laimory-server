package com.laimory.server.timeline.entity;

import com.laimory.server.timeline.TaskStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * timeline draft 비동기 작업의 상태 모델. Redis에 JSON으로 저장된다(JPA 엔티티 아님).
 *
 * <p>error는 FAILED일 때만 채워진다. callbackTokenHash는 종결(SUCCESS/FAILED) 후에도 보존된다 —
 * terminal 재콜백을 token-first로 검증하려면 해시가 남아 있어야 하기 때문이다(idempotent replay 방어).
 * callbackTokenHash는 콜백 토큰의 SHA-256 해시이며, 원문 토큰은 저장하지 않는다(발급 시 AI에만 전달).
 * recordDate는 콜백 persist·결과 조회의 다리값이다.
 * (SUCCESS 결과 record는 (userId, recordDate)로 찾으므로 dailyRecordId는 저장하지 않는다.)
 *
 * <p>{@code recordAt}(벽시계 시각) / {@code recordTimezone}은 finalize 때 daily_records에 쓸 메타데이터다.
 * PROCESSING에서만 채워지고({@link #processing}) 종결(SUCCESS/FAILED)에는 {@code null}이다 —
 * finalize는 PROCESSING task에서 읽고, 종결 task의 소비처(폴링·멱등)는 이 둘을 읽지 않기 때문이다.
 */
public record TimelineDraftTask(
        TaskStatus status,
        LocalDate recordDate,
        LocalDateTime recordAt,
        String recordTimezone,
        String error,
        String callbackTokenHash
) {

    public static TimelineDraftTask processing(LocalDate recordDate, LocalDateTime recordAt, String recordTimezone,
                                               String callbackTokenHash) {
        return new TimelineDraftTask(TaskStatus.PROCESSING, recordDate, recordAt, recordTimezone, null, callbackTokenHash);
    }

    public static TimelineDraftTask success(LocalDate recordDate, String callbackTokenHash) {
        return new TimelineDraftTask(TaskStatus.SUCCESS, recordDate, null, null, null, callbackTokenHash);
    }

    public static TimelineDraftTask failed(LocalDate recordDate, String error, String callbackTokenHash) {
        return new TimelineDraftTask(TaskStatus.FAILED, recordDate, null, null, error, callbackTokenHash);
    }
}
