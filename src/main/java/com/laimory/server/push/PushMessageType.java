package com.laimory.server.push;

/**
 * 발송 가능한 푸시 종류. 발송 결과 metric의 고정 차원이자 알림의 정체성이다 — 문구는 상태에 따라
 * 달라질 수 있어 {@link PushMessage} factory가 소유한다.
 *
 * <p>현재 두 종류 모두 사용자 행동·설정에 대한 정보성 통지다. 영리 목적의 광고성 알림을 추가하려면
 * 정보통신망법 제50조가 요구하는 수신 동의·야간 전송 제한·{@code (광고)} 표기·무료 수신거부 수단을
 * 함께 도입해야 한다 — 종류만 추가하고 끝낼 수 없다.
 */
public enum PushMessageType {

    /** AI 타임라인 생성 terminal 통지 — 사용자가 직접 시작한 작업의 결과다(성공·실패 문구가 다르다). */
    TIMELINE_COMPLETION,

    /** 사용자가 직접 켜고 시각을 고른 일일 리마인더. */
    DAILY_REMINDER
}
