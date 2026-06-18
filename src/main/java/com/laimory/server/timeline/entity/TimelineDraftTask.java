package com.laimory.server.timeline.entity;

import com.laimory.server.timeline.TaskStatus;
import java.time.LocalDate;

/**
 * timeline draft 비동기 작업의 상태 모델. Redis에 JSON으로 저장된다(JPA 엔티티 아님).
 *
 * <p>dailyRecordId는 SUCCESS일 때만, error는 FAILED일 때만 채워지고 나머지는 null이다.
 */
public record TimelineDraftTask(
        TaskStatus status,
        LocalDate recordDate,
        Long dailyRecordId,
        String error
) {

    public static TimelineDraftTask processing(LocalDate recordDate) {
        return new TimelineDraftTask(TaskStatus.PROCESSING, recordDate, null, null);
    }

    public static TimelineDraftTask success(LocalDate recordDate, Long dailyRecordId) {
        return new TimelineDraftTask(TaskStatus.SUCCESS, recordDate, dailyRecordId, null);
    }

    public static TimelineDraftTask failed(LocalDate recordDate, String error) {
        return new TimelineDraftTask(TaskStatus.FAILED, recordDate, null, error);
    }
}
