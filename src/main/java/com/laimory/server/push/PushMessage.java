package com.laimory.server.push;

import com.laimory.server.timeline.TaskStatus;
import java.util.Map;

/**
 * 발송할 알림 한 건 — 종류와 그 종류가 요구하는 data payload를 담는다.
 *
 * <p>문구는 {@link PushMessageType} 상수가 소유하므로 여기서 고르지 않는다. 이 record가 남아 있는 이유는
 * data가 실행 시점 값이기 때문이다({@code taskId}) — 종류만으로 정해지는 것은 전부 enum에 있다.
 *
 * @param data FCM data payload — 종류별 고정 key만 담고 credential은 담지 않는다.
 */
public record PushMessage(PushMessageType type, Map<String, String> data) {

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
        return new PushMessage(completionType(status), Map.of("taskId", taskId, "status", status.name()));
    }

    private static PushMessageType completionType(TaskStatus status) {
        return switch (status) {
            case SUCCESS -> PushMessageType.TIMELINE_COMPLETION_SUCCESS;
            case FAILED -> PushMessageType.TIMELINE_COMPLETION_FAILED;
            case PROCESSING -> throw new IllegalArgumentException("terminal status required: " + status);
        };
    }

    /** 일일 리마인더 — 알림을 탭했을 때 열 화면 route만 싣는다. */
    public static PushMessage dailyReminder() {
        return new PushMessage(PushMessageType.DAILY_REMINDER, Map.of("route", DAILY_REMINDER_ROUTE));
    }
}
