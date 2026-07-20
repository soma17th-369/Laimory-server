package com.laimory.server.timeline;

/**
 * 타임라인 이벤트 분류. {@link ItemType}(source 종류)과 독립된 Event 자체의 분류다 —
 * 서로 변환·추론하지 않는다(Item 구성으로 Event 타입을 재추론 금지).
 *
 * <p>의미: WAKE_UP=기상, SLEEP=수면, MOVEMENT=이동, CALENDAR_EVENT=캘린더 일정, MEAL=식사,
 * PHOTO_MOMENT=사진으로 찍은 순간들, MEETING=회의, CLASS=수업, WORK=근무, EXERCISE=운동,
 * SOCIAL=대화, REST=휴식, UNKNOWN=알 수 없음.
 *
 * <p>{@code UNKNOWN}은 기존 데이터, 구버전 writer의 컬럼 생략(DB default), AI가 확정 타입을 판별하지
 * 못한 상태를 나타내는 fallback sentinel이다. AI 분류 경계·우선순위는 별도 결정으로 미구현이다.
 *
 * <p>새 literal은 Server enum 배포 후 AI writer에서 활성화한다 — AI가 먼저 쓰면 assembler 변환 실패로
 * 해당 task가 FAILED 처리된다.
 */
public enum TimelineEventType {
    WAKE_UP,
    SLEEP,
    MOVEMENT,
    CALENDAR_EVENT,
    MEAL,
    PHOTO_MOMENT,
    MEETING,
    CLASS,
    WORK,
    EXERCISE,
    SOCIAL,
    REST,
    UNKNOWN
}
