package com.laimory.server.push;

import java.util.Map;

/**
 * 발송할 알림 한 건 — 종류와 그 종류가 요구하는 data payload만 담는다. 문구·광고 표기는 sender가
 * {@link PushMessageType}에서 합성하므로 호출자가 title/body를 만들지 않는다(개별 notifier의 표기 누락 차단).
 *
 * @param type 알림 종류(문구·법적 분류의 소유자)
 * @param data FCM data payload — 종류별 고정 key만 담고 FID·token 같은 credential은 담지 않는다.
 */
public record PushMessage(PushMessageType type, Map<String, String> data) {

    public PushMessage {
        data = Map.copyOf(data);
    }

    /** 타임라인 완료 통지 — 앱이 조회를 재개할 task 식별자와 terminal 상태를 싣는다. */
    public static PushMessage timelineCompletion(String taskId, String status) {
        return new PushMessage(PushMessageType.TIMELINE_COMPLETION,
                Map.of("taskId", taskId, "status", status));
    }

    /** 일일 리마인더 — 알림을 탭했을 때 열 화면 route만 싣는다. */
    public static PushMessage dailyReminder() {
        return new PushMessage(PushMessageType.DAILY_REMINDER, Map.of("route", DAILY_REMINDER_ROUTE));
    }

    /** 알림 탭 시 여는 화면 route. */
    public static final String DAILY_REMINDER_ROUTE = "timeline/today";
}
