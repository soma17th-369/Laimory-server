package com.laimory.server.push;

import com.laimory.server.timeline.TaskStatus;
import java.time.LocalDate;
import java.util.Map;

/**
 * 발송할 알림 한 건 — 종류, 그 종류의 문구 중 고른 한 벌, 종류가 요구하는 data payload를 담는다.
 *
 * <p>문구 후보는 {@link PushMessageType} 상수가 소유하고, 여기서는 그중 하나를 고르기만 한다. 이 record가
 * 남아 있는 이유는 고른 문구와 data가 실행 시점 값이기 때문이다(발송 날짜·{@code taskId}).
 *
 * @param copy 사용자에게 보일 문구 — 반드시 {@code type.copies()} 중 하나다.
 * @param data FCM data payload — 종류별 고정 key만 담고 credential은 담지 않는다.
 */
public record PushMessage(PushMessageType type, PushCopy copy, Map<String, String> data) {

    /** 알림 탭 시 여는 화면 route. */
    public static final String DAILY_REMINDER_ROUTE = "timeline/today";

    public PushMessage {
        data = Map.copyOf(data);
    }

    /**
     * 타임라인 완료 통지 — terminal 상태를 그에 맞는 종류로 옮기고, 앱이 조회를 재개할 task 식별자와
     * 상태를 data로 싣는다. 비terminal 상태는 발송 대상이 아니므로 여기서 거절한다.
     */
    public static PushMessage timelineCompletion(String taskId, TaskStatus status) {
        PushMessageType type = completionType(status);
        return new PushMessage(type, type.copies().getFirst(),
                Map.of("taskId", taskId, "status", status.name()));
    }

    private static PushMessageType completionType(TaskStatus status) {
        return switch (status) {
            case SUCCESS -> PushMessageType.TIMELINE_COMPLETION_SUCCESS;
            case FAILED -> PushMessageType.TIMELINE_COMPLETION_FAILED;
            case PROCESSING -> throw new IllegalArgumentException("terminal status required: " + status);
        };
    }

    /**
     * 일일 리마인더 — 문구는 발송 날짜로 순서대로 돌리고, data에는 알림을 탭했을 때 열 화면 route만 싣는다.
     * 같은 날짜면 모든 process·batch가 같은 문구를 고르고, 문구가 둘 이상이면 이틀 연속 같은 문구가 나가지 않는다.
     */
    public static PushMessage dailyReminder(LocalDate sendDate) {
        PushMessageType type = PushMessageType.DAILY_REMINDER;
        PushCopy copy = type.copies().get(Math.floorMod(sendDate.toEpochDay(), type.copies().size()));
        return new PushMessage(type, copy, Map.of("route", DAILY_REMINDER_ROUTE));
    }
}
