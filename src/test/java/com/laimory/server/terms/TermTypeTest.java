package com.laimory.server.terms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@link TermType}의 기대 mapping 계약 고정 — 이 enum이 stage 소속·화면 순서의 단일
 * 소유자다. mapping을 바꾸면 운영 seed도 함께 바꿔야 한다는 신호로 테스트가 깨진다.
 */
class TermTypeTest {

    @Test
    void loginStage_hasTermsOfServiceThenPrivacyPolicy() {
        assertThat(TermType.typesOf(TermStage.LOGIN))
                .containsExactly(TermType.TERMS_OF_SERVICE, TermType.PRIVACY_POLICY);
    }

    @Test
    void timelineFirstCreateStage_hasThreeConsentsThenLocationTerms() {
        assertThat(TermType.typesOf(TermStage.TIMELINE_FIRST_CREATE))
                .containsExactly(TermType.SENSITIVE_INFORMATION_CONSENT,
                        TermType.THIRD_PARTY_PROVISION_CONSENT,
                        TermType.CROSS_BORDER_TRANSFER_CONSENT,
                        TermType.LOCATION_BASED_SERVICE_TERMS);
    }

    @Test
    void everyType_belongsToExactlyOneStageAndHasUniqueDisplayOrder() {
        List<TermType> allByStage = Stream.of(TermStage.values())
                .flatMap(stage -> TermType.typesOf(stage).stream())
                .toList();

        assertThat(allByStage).containsExactlyInAnyOrder(TermType.values());
        assertThat(Stream.of(TermType.values()).map(TermType::displayOrder).distinct())
                .hasSize(TermType.values().length);
    }
}
