package com.laimory.server.timeline.service;

import com.laimory.server.common.id.SubjectId;
import com.laimory.server.timeline.dto.DraftTaskListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 진행 중 draft 작업 목록 조회(GET) 오케스트레이터. 인증 사용자가 소유한 현재 PROCESSING taskId만
 * 생성 최신순으로 반환한다 — 앱 재진입 등으로 taskId를 잃은 클라이언트가 기존 단건 폴링으로 복귀하는
 * 재발견 용도다.
 *
 * <p>목록은 재진입 힌트지 lock이 아니다 — 반환 직후 작업이 종결·만료될 수 있고 각 taskId의 최신 권위는
 * 단건 폴링이다. 진행 작업이 없으면 빈 배열이다(null 아님).
 */
@Service
@RequiredArgsConstructor
public class TimelineDraftTaskListService {

    private final TimelineTaskService timelineTaskService;

    public DraftTaskListResponse list(String applicationVersion, SubjectId subjectId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        return new DraftTaskListResponse(timelineTaskService.findProcessingTaskIds(subjectId));
    }
}
