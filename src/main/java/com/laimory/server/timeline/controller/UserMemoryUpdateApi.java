package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiResponse;
import com.laimory.server.common.ApiUrls;
import com.laimory.server.timeline.dto.AiUserMemoryUpdateResultRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * User Memory 갱신 결과 저장(서버간 통신)의 문서·계약(구현은 {@link UserMemoryUpdateController}).
 *
 * <p>draft 흐름과 달리 <b>endpoint가 이것 하나뿐이다</b> — 입력 조회가 없고(접수 body에 전부 실어 보낸다),
 * 폴링도 없고(저장은 이미 동기로 끝났다), 별도 callback도 없다(이 호출이 결과 전달과 종료 통보를 겸한다).
 * 그래서 token 재발급 지점도 없다(작업당 token 하나).
 */
@Tag(name = "User Memory Update (서버간)",
        description = "AI 서버 → API 서버 User Memory 갱신 결과(/s/api) — Task-Token 헤더로 인증")
@RequestMapping(ApiUrls.SERVER_API_URL + "/user-memory/updates")
public interface UserMemoryUpdateApi {

    @Operation(summary = "User Memory 갱신 결과 저장",
            description = "AI가 만든 새 User Memory 문서를 사용자 문서 전체와 교체한다(부분 병합 없음). "
                    + "성공·실패 모두 이 endpoint로 오며 `status`가 갈래를 정한다. FAILED는 DB를 바꾸지 않고 "
                    + "작업만 종결한다. 서버는 `userMemory`의 스키마를 파싱·정규화·검증하지 않는다 — "
                    + "스키마를 소유하고 검증하는 쪽이 AI 서버다. 다만 저장 직전에 textual leaf만 v1 개인정보 "
                    + "치환을 거친다(구조·필드명·숫자는 그대로). **하루 기록(`daily_records`)은 건드리지 "
                    + "않는다** — 저장 API가 이미 SAVED로 커밋했고 이 시점에 SAVED인 것이 정상이다. "
                    + "4xx는 전부 재시도해도 달라지지 않는 실패이므로 AI는 재시도 없이 중단한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "반영 완료(body=null). FAILED 통보 수신도 200이다", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — 결과 계약 위반(status 누락·미지원 값, SUCCESS인데 userMemory 없음)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "`-1002` — 토큰 누락·불일치"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-1001` — 작업 없음(만료·이미 종결·중복 도착 포함)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "`-1017` — 접수 이후 다른 날짜의 갱신이 User Memory를 교체함(결과 폐기)")
    })
    @PostMapping("/{taskId}/result")
    ResponseEntity<ApiResponse<Void>> result(
            @Parameter(description = "API 버전", example = "v1") @PathVariable String applicationVersion,
            @Parameter(description = "갱신 작업 ID") @PathVariable String taskId,
            @Parameter(description = "task 인증 토큰")
            @RequestHeader(value = "Task-Token", required = false) String taskToken,
            @RequestBody AiUserMemoryUpdateResultRequest request);
}
