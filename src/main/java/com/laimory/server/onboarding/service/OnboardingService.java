package com.laimory.server.onboarding.service;

import com.laimory.server.push.service.SubjectPreferenceService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 온보딩 완료 기록의 use case orchestration — 상태를 소유한 leaf service에 위임한다.
 * repository를 직접 주입하지 않으며 transaction 경계는 leaf service의 조건 UPDATE가 소유한다.
 */
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final SubjectPreferenceService subjectPreferenceService;

    /**
     * 완료 기록 — {@code false → true} 단방향이며 반복 호출도 멱등 성공이다. 설정 행이 없으면 leaf
     * service의 예외를 성공으로 삼키지 않고 그대로 전파한다(쓰기가 행을 만들지 않는다).
     */
    public void completeOnboarding(String applicationVersion, UUID subjectId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        subjectPreferenceService.completeOnboarding(subjectId);
    }
}
