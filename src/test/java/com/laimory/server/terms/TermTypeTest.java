package com.laimory.server.terms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * {@link TermType}의 기대 mapping 계약 고정 — 이 enum이 stage 소속·필수 여부·화면 순서·원문 page
 * slug의 단일 소유자다. mapping을 바꾸면 운영 seed·게시된 page URL도 함께 바꿔야 한다는 신호로
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
    void contentSlug_isFixedPerTypeAndUnique() {
        // slug는 한 번 공개 API로 나가면 불변 계약이다 — 과거 버전 URL이 동의 이력의 재현 근거라
        // 이름을 바꾸면 이미 게시된 page 주소가 깨진다.
        assertThat(TermType.TERMS_OF_SERVICE.contentSlug()).isEqualTo("terms-of-service");
        assertThat(TermType.PRIVACY_POLICY.contentSlug()).isEqualTo("privacy-policy");
        assertThat(TermType.SENSITIVE_INFORMATION_CONSENT.contentSlug())
                .isEqualTo("sensitive-information-consent");
        assertThat(TermType.THIRD_PARTY_PROVISION_CONSENT.contentSlug())
                .isEqualTo("third-party-provision-consent");
        assertThat(TermType.CROSS_BORDER_TRANSFER_CONSENT.contentSlug())
                .isEqualTo("cross-border-transfer-consent");
        assertThat(Stream.of(TermType.values()).map(TermType::contentSlug).distinct())
                .hasSize(TermType.values().length);
    }

    @Test
    void requiredTypes_filterByEnumRequiredFlag() {
        // 필수 판정은 enum required flag를 거친다 — flag가 false인 종류는 gate 판정에서 자동 제외되는
        // 메커니즘이다(현재 기본 mapping은 다섯 종류 모두 필수 — 제품·법무 확정 시 flag만 바꾼다).
        for (TermStage stage : TermStage.values()) {
            assertThat(TermType.requiredTypesOf(stage))
                    .containsExactlyElementsOf(TermType.typesOf(stage).stream()
                            .filter(TermType::required)
                            .toList());
        }
    }
}
