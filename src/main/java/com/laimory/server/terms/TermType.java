package com.laimory.server.terms;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 약관 종류 — 각 종류의 기대 {@code (stage, required, displayOrder)} mapping의 단일 소유자다.
 *
 * <p>공개 응답의 {@code required}·정렬 순서와 필수 동의 판정은 전부 이 enum을 사용한다. DB
 * {@code term_documents}의 denormalized {@code stage}/{@code required}는 운영 확인용 사본일 뿐이며,
 * enum과 불일치하면 {@code TermCatalogReadiness}가 잘못된 seed로 판정한다(운영자가 문서별로 의미를
 * 임의 변경하는 모델이 아니다). 잘못된 seed 값이 조용히 전 회원 차단이나 필수 동의 누락으로 이어지지
 * 않게 하는 구조다.
 *
 * <p>{@code required} 값은 계획(#303)의 기본값(다섯 종류 모두 필수)이다 — 제품·법무가 문서의 법적
 * 성격(단순 고지 vs 명시적 동의)을 확정하면 이 mapping과 운영 seed를 함께 갱신한다.
 */
public enum TermType {

    /** 이용약관. */
    TERMS_OF_SERVICE(TermStage.LOGIN, true, 1, true),
    /** 개인정보 처리방침. */
    PRIVACY_POLICY(TermStage.LOGIN, true, 2, true),
    /** 민감정보 처리방침/동의. */
    SENSITIVE_INFORMATION_CONSENT(TermStage.TIMELINE_FIRST_CREATE, true, 3, true),
    /** 제3자 정보 제공 동의. */
    THIRD_PARTY_PROVISION_CONSENT(TermStage.TIMELINE_FIRST_CREATE, true, 4, true),
    /** 국외 이전 고지·동의. */
    CROSS_BORDER_TRANSFER_CONSENT(TermStage.TIMELINE_FIRST_CREATE, true, 5, true),
    /** 광고성 정보 수신 동의(선택) — 상태 권위는 {@code notification_consents}다. */
    ADVERTISING_PUSH_CONSENT(TermStage.PUSH_SETTINGS, false, 6, false),
    /** 야간(21:00~08:00) 광고성 정보 수신 동의(선택) — 일반 광고 동의를 전제한다. */
    NIGHT_ADVERTISING_PUSH_CONSENT(TermStage.PUSH_SETTINGS, false, 7, false);

    private final TermStage stage;
    private final boolean required;
    private final int displayOrder;
    private final boolean agreementManaged;

    TermType(TermStage stage, boolean required, int displayOrder, boolean agreementManaged) {
        this.stage = stage;
        this.required = required;
        this.displayOrder = displayOrder;
        this.agreementManaged = agreementManaged;
    }

    public TermStage stage() {
        return stage;
    }

    public boolean required() {
        return required;
    }

    /** 서버가 정의한 화면 순서 — 공개 응답 정렬 기준(DB PK·삽입 순서에 의존하지 않는다). */
    public int displayOrder() {
        return displayOrder;
    }

    /**
     * 범용 약관 동의 API({@code term_agreements})가 상태를 소유하는 종류인지.
     *
     * <p>{@code term_agreements}는 수락만 표현할 수 있고 철회·현재 상태를 표현하지 못한다. 철회가 있는
     * 알림 수신 동의는 {@code notification_consents} snapshot + append-only event가 권위이므로 범용
     * 동의 API가 그 종류를 기록하면 두 개의 상충하는 진실이 생긴다 — 그래서 여기서 갈라 막는다.
     */
    public boolean isAgreementManaged() {
        return agreementManaged;
    }

    /** 해당 단계의 전체 종류(화면 순서 정렬). */
    public static List<TermType> typesOf(TermStage stage) {
        return Stream.of(values())
                .filter(type -> type.stage == stage)
                .sorted(Comparator.comparingInt(TermType::displayOrder))
                .toList();
    }

    /** 해당 단계의 필수 종류(화면 순서 정렬) — enforcement의 필수 동의 판정 기준. */
    public static List<TermType> requiredTypesOf(TermStage stage) {
        return typesOf(stage).stream()
                .filter(TermType::required)
                .toList();
    }
}
