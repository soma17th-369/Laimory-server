package com.laimory.server.terms;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 약관 종류 — 각 종류의 기대 {@code (stage, required, displayOrder)} mapping의 단일 소유자다.
 *
 * <p>공개 응답의 {@code required}·정렬 순서와 필수 동의 판정은 전부 이 enum을 사용한다. DB
 * {@code term_documents}는 이 mapping을 복제하지 않는다 — 종류·버전·효력일과 제목만 들고 있고,
 * 운영자가 문서별로 단계·필수 여부·순서를 임의 변경하는 모델이 아니다.
 *
 * <p>{@code contentSlug}는 {@code laimory.app}에 게시된 버전별 정적 원문 page의 path segment다. 한 번
 * 공개 API로 나간 slug는 불변 계약이며(과거 버전 URL이 동의 이력의 재현 근거다) 이름을 바꾸려면
 * 기존 URL을 영구 보존한 채 새 버전으로 게시한다.
 *
 * <p>{@code required} 값은 계획(#303)의 기본값(다섯 종류 모두 필수)이다 — 제품·법무가 문서의 법적
 * 성격(단순 고지 vs 명시적 동의)을 확정하면 이 mapping과 운영 seed를 함께 갱신한다.
 */
public enum TermType {

    /** 이용약관. */
    TERMS_OF_SERVICE(TermStage.LOGIN, true, 1, "terms-of-service"),
    /** 개인정보 처리방침. */
    PRIVACY_POLICY(TermStage.LOGIN, true, 2, "privacy-policy"),
    /** 민감정보 처리방침/동의. */
    SENSITIVE_INFORMATION_CONSENT(TermStage.TIMELINE_FIRST_CREATE, true, 3, "sensitive-information-consent"),
    /** 제3자 정보 제공 동의. */
    THIRD_PARTY_PROVISION_CONSENT(TermStage.TIMELINE_FIRST_CREATE, true, 4, "third-party-provision-consent"),
    /** 국외 이전 고지·동의. */
    CROSS_BORDER_TRANSFER_CONSENT(TermStage.TIMELINE_FIRST_CREATE, true, 5, "cross-border-transfer-consent");

    private final TermStage stage;
    private final boolean required;
    private final int displayOrder;
    private final String contentSlug;

    TermType(TermStage stage, boolean required, int displayOrder, String contentSlug) {
        this.stage = stage;
        this.required = required;
        this.displayOrder = displayOrder;
        this.contentSlug = contentSlug;
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

    /** 게시된 원문 page URL의 종류 segment — 한 번 노출되면 불변인 공개 계약이다. */
    public String contentSlug() {
        return contentSlug;
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
