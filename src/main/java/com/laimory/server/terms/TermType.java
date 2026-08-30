package com.laimory.server.terms;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 약관 종류 — 각 종류의 기대 {@code (stage, displayOrder)} mapping의 단일 소유자다.
 *
 * <p>공개 응답의 정렬 순서는 이 enum을 사용한다. DB {@code term_documents}는
 * 단계·순서를 복제하지 않고 종류·버전·효력일과 제목만 들고 있다. 실제 필수 동의
 * 대상은 사용자 흐름을 차단하는 enforcement 지점에서 명시한다.
 */
public enum TermType {

    /** 이용약관. */
    TERMS_OF_SERVICE(TermStage.LOGIN, 1),
    /** 상시 공개하는 개인정보 처리방침. */
    PRIVACY_POLICY(TermStage.LOGIN, 2),
    /** 민감정보 처리방침/동의. */
    SENSITIVE_INFORMATION_CONSENT(TermStage.TIMELINE_FIRST_CREATE, 3),
    /** 제3자 정보 제공 동의. */
    THIRD_PARTY_PROVISION_CONSENT(TermStage.TIMELINE_FIRST_CREATE, 4),
    /** 국외 이전 고지·동의. */
    CROSS_BORDER_TRANSFER_CONSENT(TermStage.TIMELINE_FIRST_CREATE, 5),
    /** 위치정보가 포함된 타임라인 생성에만 조건부로 요구하는 위치기반서비스 이용약관. */
    LOCATION_BASED_SERVICE_TERMS(TermStage.TIMELINE_FIRST_CREATE, 6);

    private final TermStage stage;
    private final int displayOrder;

    TermType(TermStage stage, int displayOrder) {
        this.stage = stage;
        this.displayOrder = displayOrder;
    }

    public TermStage stage() {
        return stage;
    }

    /** 서버가 정의한 화면 순서 — 공개 응답 정렬 기준(DB PK·삽입 순서에 의존하지 않는다). */
    public int displayOrder() {
        return displayOrder;
    }

    /** 해당 단계의 전체 종류(화면 순서 정렬). */
    public static List<TermType> typesOf(TermStage stage) {
        return Stream.of(values())
                .filter(type -> type.stage == stage)
                .sorted(Comparator.comparingInt(TermType::displayOrder))
                .toList();
    }
}
