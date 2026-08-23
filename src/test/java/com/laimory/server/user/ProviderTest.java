package com.laimory.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** registrationId → Provider 매핑 계약: 설정 유래 값만 허용, 미지원은 내부 불변식 위반. */
class ProviderTest {

    @Test
    void fromRegistrationId_mapsKnownProviders() {
        assertThat(Provider.fromRegistrationId("google")).isEqualTo(Provider.GOOGLE);
        assertThat(Provider.fromRegistrationId("kakao")).isEqualTo(Provider.KAKAO);
    }

    @Test
    void fromRegistrationId_unknown_throwsIllegalState() {
        assertThatThrownBy(() -> Provider.fromRegistrationId("naver"))
                .isInstanceOf(IllegalStateException.class);
    }
}
