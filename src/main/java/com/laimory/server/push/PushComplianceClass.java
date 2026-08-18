package com.laimory.server.push;

/**
 * 알림 종류의 법적 성격 — 광고성 수신 동의·야간 전송 제한·{@code (광고)} 표기 적용 여부를 가르는 축이다.
 *
 * <p>제품 책임자가 알림 종류마다 확정해 코드에 넣는 값이며 사용자·Android 입력이나 DB 설정값이 아니다.
 * 서버가 title/body 문구를 보고 runtime에 추론하지 않는다 — 문구가 바뀌어도 분류는 코드 변경으로만
 * 바뀐다. 누락은 안전한 기본값(정보성)으로 흡수하지 않고 기동을 실패시킨다
 * ({@link PushComplianceStartupValidator}).
 */
public enum PushComplianceClass {

    /** 사용자 행동에 대한 응답·서비스 운영 고지. 광고 동의·야간 제한·{@code (광고)} 표기 대상이 아니다. */
    INFORMATIONAL,

    /** 영리 목적의 이용 유도. 광고 수신 동의가 있어야 발송하고 야간에는 야간 동의까지 요구한다. */
    ADVERTISING
}
