package com.laimory.server.push;

import com.laimory.server.timeline.TaskStatus;
import java.util.Map;

/**
 * 발송할 알림 한 건 — 종류, 사용자에게 보일 문구, 그 종류가 요구하는 data payload를 담는다.
 *
 * <p>문구 선택은 <b>여기 factory가 소유한다.</b> 같은 종류라도 상태에 따라 다른 문구를 써야 하는 경우가
 * 있어서({@code TIMELINE_COMPLETION}의 성공/실패) 종류 상수에 문구를 하나만 매달아 두면 상태를 잃는다.
 * {@link PushMessageType}은 metric 차원과 정체성만 소유한다.
 *
 * @param data FCM data payload — 종류별 고정 key만 담고 credential은 담지 않는다.
 */
public record PushMessage(PushMessageType type, String title, String body, Map<String, String> data) {

    static final String TIMELINE_SUCCESS_TITLE = "타임라인 생성 완료";
    static final String TIMELINE_SUCCESS_BODY = "타임라인이 준비됐어요.";
    static final String TIMELINE_FAILED_TITLE = "타임라인 생성 실패";
    static final String TIMELINE_FAILED_BODY = "타임라인을 만들지 못했어요. 앱에서 다시 시도해 주세요.";
    static final String DAILY_REMINDER_TITLE = "타임라인을 완성해보세요!";
    static final String DAILY_REMINDER_BODY = "하루를 기록해보세요!";

    /** 알림 탭 시 여는 화면 route. */
    public static final String DAILY_REMINDER_ROUTE = "timeline/today";

    public PushMessage {
        data = Map.copyOf(data);
    }

    /**
     * 타임라인 완료 통지 — terminal 상태에 맞는 문구를 고르고, 앱이 조회를 재개할 task 식별자와 상태를
     * data로 싣는다. 비terminal 상태는 발송 대상이 아니므로 여기서 거절한다.
     */
    public static PushMessage timelineCompletion(String taskId, TaskStatus status) {
        if (status != TaskStatus.SUCCESS && status != TaskStatus.FAILED) {
            throw new IllegalArgumentException("terminal status required: " + status);
        }
        boolean success = status == TaskStatus.SUCCESS;
        return new PushMessage(PushMessageType.TIMELINE_COMPLETION,
                success ? TIMELINE_SUCCESS_TITLE : TIMELINE_FAILED_TITLE,
                success ? TIMELINE_SUCCESS_BODY : TIMELINE_FAILED_BODY,
                Map.of("taskId", taskId, "status", status.name()));
    }

    /** 일일 리마인더 — 알림을 탭했을 때 열 화면 route만 싣는다. */
    public static PushMessage dailyReminder() {
        return new PushMessage(PushMessageType.DAILY_REMINDER, DAILY_REMINDER_TITLE, DAILY_REMINDER_BODY,
                Map.of("route", DAILY_REMINDER_ROUTE));
    }
}
