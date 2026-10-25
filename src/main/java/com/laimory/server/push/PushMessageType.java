package com.laimory.server.push;

import java.util.List;

/**
 * 발송 가능한 푸시 종류 — 사용자에게 보일 문구와 발송 결과 metric의 계열을 함께 소유한다.
 *
 * <p>결과에 따라 문구가 달라지면 <b>종류를 나눈다</b>({@code TIMELINE_COMPLETION_*}). 상수 하나가 가진 문구는
 * 모두 같은 의미의 바리에이션이라 상태에 맞지 않는 문구를 고르는 실수를 표현할 수 없다. 바리에이션이 여럿인
 * 종류({@link #DAILY_REMINDER})는 {@link PushMessage}가 발송 날짜로 하나를 고른다. 대신 운영이 한 알림으로 보는
 * 단위는 {@link #metricGroup()}이 지킨다 — 나뉜 종류가 같은 계열을 공유하므로 종류를 나눠도 metric
 * 차원은 늘지 않는다. 흐름별로 타입을 나누되 공개 code는 공유하는
 * {@link com.laimory.server.common.error.ExceptionType}과 같은 구조다.
 *
 * <p>현재 모든 종류가 사용자 행동·설정에 대한 정보성 통지다. 영리 목적의 광고성 알림을 추가하려면
 * 정보통신망법 제50조가 요구하는 수신 동의·야간 전송 제한·{@code (광고)} 표기·무료 수신거부 수단을
 * 함께 도입해야 한다 — 종류만 추가하고 끝낼 수 없다.
 */
public enum PushMessageType {

    /** AI 타임라인 생성 성공 통지 — 사용자가 직접 시작한 작업의 결과다. */
    TIMELINE_COMPLETION_SUCCESS("TIMELINE_COMPLETION",
            new PushCopy("타임라인 생성 완료", "타임라인이 준비됐어요.")),

    /** AI 타임라인 생성 실패 통지 — 성공과 같은 계열이지만 결과를 오해하지 않도록 문구가 다르다. */
    TIMELINE_COMPLETION_FAILED("TIMELINE_COMPLETION",
            new PushCopy("타임라인 생성 실패", "타임라인을 만들지 못했어요. 앱에서 다시 시도해 주세요.")),

    /**
     * 전체 사용자에게 매일 고정 시각으로 가는 일일 리마인더 — 기본 ON이고 사용자는 끄기만 한다. 타임라인 생성을
     * 권하는 문구를 날짜마다 순서대로 돌린다(선언 순서가 순환 순서다).
     */
    DAILY_REMINDER("DAILY_REMINDER",
            new PushCopy("오늘 하루 어땠나요?", "오늘의 기록으로 타임라인을 만들어보세요."),
            new PushCopy("하루를 마무리할 시간이에요", "오늘 하루를 타임라인으로 정리해보세요."),
            new PushCopy("오늘을 잊기 전에", "오늘 있었던 일을 타임라인으로 남겨보세요."),
            new PushCopy("오늘의 순간들을 모아볼까요?", "흩어진 기록을 타임라인으로 만들어보세요."));

    private final String metricGroup;
    private final List<PushCopy> copies;

    PushMessageType(String metricGroup, PushCopy... copies) {
        this.metricGroup = metricGroup;
        this.copies = List.of(copies);
    }

    /**
     * 발송 결과 metric의 {@code type} 태그 값 — 운영이 한 알림으로 보는 단위다. 문구 때문에 나뉜 종류는
     * 같은 값을 공유하므로 counter 수가 종류 수를 따라 늘지 않는다. 값이 어긋나면 미터 개수가 달라져
     * {@code ServerApplicationTests}의 고정 개수 단언이 잡는다.
     */
    public String metricGroup() {
        return metricGroup;
    }

    /** 이 종류가 쓸 수 있는 문구 — 코드가 소유하며 DB·운영 설정으로 바꾸지 않는다. 비어 있지 않다. */
    public List<PushCopy> copies() {
        return copies;
    }
}
