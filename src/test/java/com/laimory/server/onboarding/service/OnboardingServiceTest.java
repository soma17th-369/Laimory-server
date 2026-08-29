package com.laimory.server.onboarding.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.laimory.server.push.service.SubjectPreferenceService;
import com.laimory.server.testsupport.TestSubjects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 온보딩 완료 orchestration 검증 — 완료 writer에만 위임하고, leaf 예외를 성공으로 삼키지 않는 계약을
 * 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(91L);

    @Mock
    private SubjectPreferenceService subjectPreferenceService;

    private OnboardingService service() {
        return new OnboardingService(subjectPreferenceService);
    }

    @Test
    void completeOnboarding_delegatesPrincipalSubjectToWriter() {
        assertThatCode(() -> service().completeOnboarding("v1", SUBJECT_ID)).doesNotThrowAnyException();

        verify(subjectPreferenceService).completeOnboarding(SUBJECT_ID);
    }

    @Test
    void completeOnboarding_doesNotSwallowMissingRowIntoSuccess() {
        // 행 부재를 200으로 삼키면 앱은 완료했다고 믿지만 다음 initializer 조회는 여전히 실패한다.
        doThrow(new IllegalStateException("subject preference row is missing"))
                .when(subjectPreferenceService).completeOnboarding(SUBJECT_ID);

        assertThatThrownBy(() -> service().completeOnboarding("v1", SUBJECT_ID))
                .isInstanceOf(IllegalStateException.class);
    }
}
