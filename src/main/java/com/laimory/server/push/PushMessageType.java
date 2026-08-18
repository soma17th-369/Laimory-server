package com.laimory.server.push;

/**
 * 발송 가능한 푸시 종류와 그 법적 분류의 단일 소유자. sender는 이 type으로 고정 문구와 광고 표기 정책을
 * 선택하고, 발송 gate는 {@link #complianceClass()}로 광고 동의·야간 판정 적용 여부를 정한다.
 *
 * <p>분류는 필수 metadata다 — 새 type을 추가하면서 값을 빠뜨리면
 * {@link PushComplianceStartupValidator}가 기동을 막는다(worker enable 여부와 무관).
 */
public enum PushMessageType {

    /** AI 타임라인 생성 terminal 통지 — 사용자가 직접 시작한 작업의 결과라 정보성이다. */
    TIMELINE_COMPLETION(PushComplianceClass.INFORMATIONAL,
            "타임라인 생성 완료", "타임라인이 준비됐어요."),

    /** 하루 기록을 유도하는 일일 리마인더 — 리텐션 목적의 이용 유도라 광고성으로 확정했다. */
    DAILY_REMINDER(PushComplianceClass.ADVERTISING,
            "타임라인을 완성해보세요!", "하루를 기록해보세요!");

    private final PushComplianceClass complianceClass;
    private final String title;
    private final String body;

    PushMessageType(PushComplianceClass complianceClass, String title, String body) {
        this.complianceClass = complianceClass;
        this.title = title;
        this.body = body;
    }

    /** 제품 책임자가 확정한 법적 분류. 운영 설정이 아니라 코드·Android 계약과 함께 바뀐다. */
    public PushComplianceClass complianceClass() {
        return complianceClass;
    }

    public boolean isAdvertising() {
        return complianceClass == PushComplianceClass.ADVERTISING;
    }

    /** 고정 알림 제목 원문 — 광고 표기 prefix 부착 전 값이다(부착은 sender 공통 정책). */
    public String title() {
        return title;
    }

    /** 고정 알림 본문 원문 — 수신거부 안내 footer 부착 전 값이다(부착은 sender 공통 정책). */
    public String body() {
        return body;
    }
}
