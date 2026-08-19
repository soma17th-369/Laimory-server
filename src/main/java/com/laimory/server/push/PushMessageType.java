package com.laimory.server.push;

/**
 * 발송 가능한 푸시 종류와 그 고정 문구의 단일 소유자. sender는 이 type으로 title/body를 고르고
 * 호출자는 문구를 만들지 않는다.
 *
 * <p>현재 두 종류 모두 사용자 행동·설정에 대한 정보성 통지다. 영리 목적의 광고성 알림을 추가하려면
 * 정보통신망법 제50조가 요구하는 수신 동의·야간 전송 제한·{@code (광고)} 표기·무료 수신거부 수단을
 * 함께 도입해야 한다 — 문구만 추가하고 끝낼 수 없다.
 */
public enum PushMessageType {

    /** AI 타임라인 생성 terminal 통지 — 사용자가 직접 시작한 작업의 결과다. */
    TIMELINE_COMPLETION("타임라인 생성 완료", "타임라인이 준비됐어요."),

    /** 사용자가 직접 켜고 시각을 고른 일일 리마인더. */
    DAILY_REMINDER("타임라인을 완성해보세요!", "하루를 기록해보세요!");

    private final String title;
    private final String body;

    PushMessageType(String title, String body) {
        this.title = title;
        this.body = body;
    }

    /** 고정 알림 제목. */
    public String title() {
        return title;
    }

    /** 고정 알림 본문. */
    public String body() {
        return body;
    }
}
