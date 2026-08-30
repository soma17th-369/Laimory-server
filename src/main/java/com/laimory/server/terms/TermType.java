package com.laimory.server.terms;

/**
 * 약관 종류.
 *
 * <p>DB {@code term_documents}는 종류·버전·효력일과 제목을 들고 있다. 공개 조회 순서는
 * 클라이언트가 반복 query parameter로 보낸 순서가 권위이며, 실제 필수 동의 대상은
 * enforcement catalog에서 명시한다.
 */
public enum TermType {

    /** 이용약관. */
    TERMS_OF_SERVICE,
    /** 상시 공개하는 개인정보 처리방침. */
    PRIVACY_POLICY,
    /** 민감정보 처리방침/동의. */
    SENSITIVE_INFORMATION_CONSENT,
    /** 제3자 정보 제공 동의. */
    THIRD_PARTY_PROVISION_CONSENT,
    /** 국외 이전 고지·동의. */
    CROSS_BORDER_TRANSFER_CONSENT,
    /** 위치기반서비스 이용약관. */
    LOCATION_BASED_SERVICE_TERMS
}
