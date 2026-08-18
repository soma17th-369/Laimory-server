package com.laimory.server.terms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@link TermType}의 기대 mapping 계약 고정 — 이 enum이 stage 소속·필수 여부·화면 순서의 단일
 * 소유자다(DB denormalized 값은 사본). mapping을 바꾸면 운영 seed도 함께 바꿔야 한다는 신호로
 * 테스트가 깨진다.
 */
class TermTypeTest {

    @Test
    void loginStage_hasServiceAndPrivacyTermsInDisplayOrder() {
        assertThat(TermType.typesOf(TermStage.LOGIN))
                .containsExactly(TermType.TERMS_OF_SERVICE, TermType.PRIVACY_POLICY);
    }

    @Test
    void timelineFirstCreateStage_hasThreeConsentsInDisplayOrder() {
        assertThat(TermType.typesOf(TermStage.TIMELINE_FIRST_CREATE))
                .containsExactly(TermType.SENSITIVE_INFORMATION_CONSENT,
                        TermType.THIRD_PARTY_PROVISION_CONSENT,
                        TermType.CROSS_BORDER_TRANSFER_CONSENT);
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

    @Test
    void pushSettingsStage_hasTwoOptionalConsentsInDisplayOrder() {
        assertThat(TermType.typesOf(TermStage.PUSH_SETTINGS))
                .containsExactly(TermType.ADVERTISING_PUSH_CONSENT,
                        TermType.NIGHT_ADVERTISING_PUSH_CONSENT);
        // 선택 동의라 enforcement gate가 없다 — 미seed 상태에서도 stage 전체가 차단되지 않는다.
        assertThat(TermType.requiredTypesOf(TermStage.PUSH_SETTINGS)).isEmpty();
    }

    @Test
    void pushConsentTypes_areNotManagedByGenericAgreementApi() {
        // term_agreements는 수락만 표현할 수 있어 철회가 있는 알림 수신 동의의 상태 권위가 될 수 없다.
        assertThat(TermType.ADVERTISING_PUSH_CONSENT.isAgreementManaged()).isFalse();
        assertThat(TermType.NIGHT_ADVERTISING_PUSH_CONSENT.isAgreementManaged()).isFalse();
        assertThat(Stream.of(TermType.values()).filter(TermType::isAgreementManaged))
                .containsExactly(TermType.TERMS_OF_SERVICE, TermType.PRIVACY_POLICY,
                        TermType.SENSITIVE_INFORMATION_CONSENT, TermType.THIRD_PARTY_PROVISION_CONSENT,
                        TermType.CROSS_BORDER_TRANSFER_CONSENT);
    }

    @Test
    void requiredTypes_filterByEnumRequiredFlag() {
        // 필수 판정은 enum required flag를 거친다 — flag가 false인 종류(선택 동의)는 gate 판정에서
        // 자동 제외된다(LOGIN·TIMELINE 다섯 종류는 필수 — 제품·법무 확정 시 flag만 바꾼다).
        for (TermStage stage : TermStage.values()) {
            assertThat(TermType.requiredTypesOf(stage))
                    .containsExactlyElementsOf(TermType.typesOf(stage).stream()
                            .filter(TermType::required)
                            .toList());
        }
    }
}
