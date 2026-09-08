package com.laimory.server.onboarding.temporary;

import com.laimory.server.user.service.SubjectMappingService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TemporaryOnboardingResetService {

    private final SubjectMappingService subjectMappingService;
    private final TemporaryOnboardingResetRepository temporaryOnboardingResetRepository;

    public void resetOnboarding(String applicationVersion, long userId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전).
        UUID subjectId = subjectMappingService.getRequired(userId);
        if (temporaryOnboardingResetRepository.resetOnboarding(subjectId) == 0) {
            throw new IllegalStateException("subject preference row is missing");
        }
    }
}
