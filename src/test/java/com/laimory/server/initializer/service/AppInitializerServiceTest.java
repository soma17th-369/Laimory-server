package com.laimory.server.initializer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.initializer.dto.InitializerResponse;
import com.laimory.server.push.service.SubjectPreferenceService;
import com.laimory.server.testsupport.TestSubjects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 앱 초기화 orchestration 검증 — 저장값을 그대로 응답으로 옮기고, leaf 예외를 기본값으로 삼키지 않는
 * 계약을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class AppInitializerServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(91L);

    @Mock
    private SubjectPreferenceService subjectPreferenceService;

    private AppInitializerService service() {
        return new AppInitializerService(subjectPreferenceService);
    }

    @Test
    void getInitialState_returnsStoredCompletedValue() {
        when(subjectPreferenceService.findOnboardingCompleted(SUBJECT_ID)).thenReturn(true);

        InitializerResponse response = service().getInitialState("v1", SUBJECT_ID);

        assertThat(response.onboardingCompleted()).isTrue();
        verify(subjectPreferenceService).findOnboardingCompleted(SUBJECT_ID);
    }

    @Test
    void getInitialState_returnsStoredIncompleteValue() {
        when(subjectPreferenceService.findOnboardingCompleted(SUBJECT_ID)).thenReturn(false);

        assertThat(service().getInitialState("v1", SUBJECT_ID).onboardingCompleted()).isFalse();
    }

    @Test
    void getInitialState_doesNotSwallowMissingRowIntoDefault() {
        // 행 부재를 false로 바꿔 답하면 앱은 온보딩을 다시 태우고, 그 완료 요청은 다시 실패한다.
        when(subjectPreferenceService.findOnboardingCompleted(SUBJECT_ID))
                .thenThrow(new IllegalStateException("subject preference row is missing"));

        assertThatThrownBy(() -> service().getInitialState("v1", SUBJECT_ID))
                .isInstanceOf(IllegalStateException.class);
    }
}
