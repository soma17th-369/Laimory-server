package com.laimory.server.push;

/**
 * 사용자별 시각 설정을 갖는 예정 알림 종류 — {@code scheduled_notification_preferences}의 행 축이다.
 *
 * <p>새 리텐션 알림은 schema 컬럼이 아니라 이 enum의 새 상수와 새 행으로 추가한다. 저장 구조만
 * 공통이고 대상 선정·주기 계산은 각 worker가 소유한다.
 */
public enum ScheduledNotificationType {

    /** 매일 지정 시각(KST)에 하루 기록을 유도한다. */
    DAILY_REMINDER(PushMessageType.DAILY_REMINDER);

    private final PushMessageType pushMessageType;

    ScheduledNotificationType(PushMessageType pushMessageType) {
        this.pushMessageType = pushMessageType;
    }

    /** 이 예정 알림이 실제로 발송하는 메시지 종류(문구·법적 분류의 소유자). */
    public PushMessageType pushMessageType() {
        return pushMessageType;
    }

    public PushComplianceClass complianceClass() {
        return pushMessageType.complianceClass();
    }
}
