package com.laimory.server.appconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 배포 health gate가 의존하는 AppConfig 조회 서비스 단위 검증: entity→response 세 필드 mapping과
 * config row 부재 시 임의 default 없이 fail-closed(IllegalStateException). 인프라 0.
 */
@ExtendWith(MockitoExtension.class)
class AppConfigServiceTest {

    @Mock
    private AppConfigRepository appConfigRepository;

    private AppConfigService service;

    @BeforeEach
    void setUp() {
        service = new AppConfigService(appConfigRepository);
    }

    @Test
    void getAppConfig_mapsEntityFieldsToResponse() {
        // debugTestMessage는 읽기 전용이므로 fixture에서만 reflection을 사용한다.
        AppConfig config = new AppConfig();
        ReflectionTestUtils.setField(config, "minAppVersion", 3L);
        ReflectionTestUtils.setField(config, "recommendAppVersion", 5L);
        ReflectionTestUtils.setField(config, "debugTestMessage", "hello");
        when(appConfigRepository.findTop2ByOrderByAppConfigIdAsc()).thenReturn(List.of(config));

        AppConfigResponse response = service.getAppConfig("v1");

        assertThat(response.getMinAppVersion()).isEqualTo(3L);
        assertThat(response.getRecommendAppVersion()).isEqualTo(5L);
        assertThat(response.getDebugTestMessage()).isEqualTo("hello");
    }

    @Test
    void getAppConfig_emptyTable_failsClosedWithoutDefaults() {
        when(appConfigRepository.findTop2ByOrderByAppConfigIdAsc()).thenReturn(List.of());

        // 임의 default로 응답하면 배포 gate가 seed 누락을 통과시킨다 — 예외로 fail-closed해야 한다.
        assertThatThrownBy(() -> service.getAppConfig("v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AppConfig must contain exactly one row");
    }

    @Test
    void multipleRows_failClosedForReadAndWrite() {
        AppConfig first = new AppConfig();
        when(appConfigRepository.findTop2ByOrderByAppConfigIdAsc()).thenReturn(List.of(first, new AppConfig()));
        assertThatThrownBy(() -> service.getAppConfig("v1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.updateVersions(1L, 2L)).isInstanceOf(IllegalStateException.class);
        assertThat(first.getMinAppVersion()).isNull();
    }

    @Test
    void updateVersions_preservesReadOnlyMessage_andIntroUsesSameRow() {
        AppConfig row = new AppConfig();
        ReflectionTestUtils.setField(row, "debugTestMessage", "unchanged");
        when(appConfigRepository.findTop2ByOrderByAppConfigIdAsc()).thenReturn(List.of(row));
        service.updateVersions(5L, Long.MAX_VALUE);
        assertThat(service.getAppConfig("v1").getMinAppVersion()).isEqualTo(5L);
        assertThat(row.getRecommendAppVersion()).isEqualTo(Long.MAX_VALUE);
        assertThat(row.getDebugTestMessage()).isEqualTo("unchanged");
        assertThatThrownBy(() -> service.updateVersions(6L, 5L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateVersions(0L, 5L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateVersions(null, 5L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateVersions(5L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(row.getMinAppVersion()).isEqualTo(5L);
    }
}
