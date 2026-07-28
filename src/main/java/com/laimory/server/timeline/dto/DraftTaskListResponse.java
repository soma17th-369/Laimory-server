package com.laimory.server.timeline.dto;

import java.util.List;

/**
 * 진행 중 draft 작업 목록 응답. 인증 사용자가 소유한 현재 PROCESSING taskId만 생성 최신순으로 담는다.
 * 진행 작업이 없으면 빈 배열이다(null·key 생략 아님). 각 taskId의 상태·결과 상세는 단건 폴링으로 조회한다.
 */
public record DraftTaskListResponse(
        List<String> taskIds
) {
}
