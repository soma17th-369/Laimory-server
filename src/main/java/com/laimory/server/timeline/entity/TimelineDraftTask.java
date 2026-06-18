package com.laimory.server.timeline.entity;

import com.laimory.server.timeline.TaskStatus;
import java.time.LocalDate;

/**
 * timeline draft 비동기 작업의 상태 모델. Redis에 JSON으로 저장된다(JPA 엔티티 아님).
 *
 * <p>error는 FAILED일 때만 채워지고 나머지는 null이다. recordDate는 콜백 persist·결과 조회의 다리값이다.
 * (SUCCESS 결과 record는 (userId, recordDate)로 찾으므로 dailyRecordId는 저장하지 않는다.)
 */
public record TimelineDraftTask(
        TaskStatus status,
        LocalDate recordDate,
        String error
) {

    public static TimelineDraftTask processing(LocalDate recordDate) {
        return new TimelineDraftTask(TaskStatus.PROCESSING, recordDate, null);
    }

    public static TimelineDraftTask success(LocalDate recordDate) {
        return new TimelineDraftTask(TaskStatus.SUCCESS, recordDate, null);
    }

    public static TimelineDraftTask failed(LocalDate recordDate, String error) {
        return new TimelineDraftTask(TaskStatus.FAILED, recordDate, error);
    }
}
