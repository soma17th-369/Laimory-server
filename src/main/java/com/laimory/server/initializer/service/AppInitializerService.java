package com.laimory.server.initializer.service;

import com.laimory.server.initializer.dto.InitializerResponse;
import com.laimory.server.push.service.SubjectPreferenceService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 앱 초기화 조회의 use case orchestration — 초기 상태를 소유한 leaf service를 합성한다.
 * repository를 직접 주입하지 않으며 저장소 경계는 leaf service가 소유한다.
 *
 * <p>초기 상태가 늘어도 provider를 병렬 호출하는 aggregation framework를 만들지 않는다 — 필요한 leaf
 * service를 여기서 직접 부른다.
 */
@Service
@RequiredArgsConstructor
public class AppInitializerService {

    private final SubjectPreferenceService subjectPreferenceService;

    /**
     * 앱 시작 상태 조회 — 순수 읽기다. 설정 행이 없으면 leaf service의 예외를 기본값으로 삼키지 않고
     * 그대로 전파한다(조회가 행을 만들지 않는다).
     */
    public InitializerResponse getInitialState(String applicationVersion, UUID subjectId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return new InitializerResponse(subjectPreferenceService.findOnboardingCompleted(subjectId));
    }
}
