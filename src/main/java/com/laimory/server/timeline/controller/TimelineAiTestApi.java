package com.laimory.server.timeline.controller;

import com.laimory.server.common.ApiUrls;
import com.laimory.server.timeline.dto.TimelineAiTestRequest;
import com.laimory.server.timeline.dto.TimelineAiTestResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * dev 전용 타임라인 AI 동기 테스트 API의 문서·계약(구현은 {@link TimelineAiTestController}).
 *
 * <p><b>테스트 전용이며 dev에서만 열린다.</b> 활성화 property가 없으면 controller 빈 자체가 없어 이
 * operation은 OpenAPI 문서에도, 라우팅 테이블에도 존재하지 않는다(호출하면 없는 경로와 같은 404).
 *
 * <p>운영 draft 흐름과 <b>완전히 분리</b>된다 — MySQL·Redis에 읽지도 쓰지도 않고, {@code TimelineDraftTask}·
 * staging·{@code ProcessStage}·결과 저장 transaction·callback·polling 어느 것도 거치지 않는다. 성공
 * 응답은 DB에 저장된 Daily Timeline이 아니라 <b>AI가 방금 만든 추론 결과</b>이며 어디에도 저장되지 않는다.
 *
 * <p>호출자 인증은 이 endpoint의 책임이 아니다 — {@code /t/api} 경로의 Bearer token 검증은 security
 * 계층이 소유한다(회원 access JWT가 아닌 별도 계약).
 *
 * <p>AI 추론이 끝날 때까지 <b>동기로 대기</b>하므로 응답에 수십 초~수 분이 걸리고 실제 LLM 토큰 비용이
 * 발생한다. 실패해도 자동 재시도하지 않는다(중복 추론 방지).
 */
@Tag(name = "Timeline AI Test (dev 전용)",
        description = "AI Timeline Input을 AI 동기 endpoint로 전달하고 추론 결과를 그대로 반환한다 — "
                + "DB 미저장·동기 대기. dev에서만 활성화된다")
@RequestMapping(ApiUrls.TEST_API_URL + "/timeline")
public interface TimelineAiTestApi {

    @Operation(summary = "AI 타임라인 추론 동기 실행(dev 테스트 전용)",
            description = "AI Timeline Input JSON을 그대로 받아 AI 서버의 테스트 전용 동기 endpoint로 "
                    + "전달하고, 추론 결과를 app `ApiResponse` envelope 없이 반환한다. 회원·Daily Record·"
                    + "Draft Task가 없어도 동작하며 MySQL·Redis에 아무것도 저장하지 않는다.\n\n"
                    + "요청 shape는 서버간 AI 입력 조회 응답과 같고 `window`가 필수라는 점만 다르다"
                    + "(이 경로에는 시간 창을 줄 다른 통로가 없다). `taskId`는 서버가 발행해 AI 요청과 "
                    + "응답에 함께 싣는 상관키이므로 요청에 담지 않는다. `taskToken`도 계약에 없다 — "
                    + "AI가 App Server를 되부르지 않는다.\n\n"
                    + "AI로 나가는 텍스트 값은 운영 경로와 같은 개인정보 치환을 거친다(`photoUrl`·"
                    + "`filename`은 AI가 이미지를 직접 받아야 해서 원문 유지).\n\n"
                    + "AI가 제한 시간 안에 마지막 확정본을 돌려주면 응답 헤더 `X-Timeline-Timed-Out: true`가 "
                    + "붙는다 — 실패가 아니라 비동기 경로가 저장하는 값과 같은 결과다. AI가 오류를 반환한 "
                    + "경우에는 502와 함께 응답 헤더 `X-Ai-Error-Code`로 AI의 numeric 오류 코드를 전달한다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "AI 추론 결과. 제한 시간 내 마지막 확정본이면 `X-Timeline-Timed-Out: true` 동반"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "`-400` — `window` 누락·역전, `sourceItems` 0건, `rawId` 형식 오류·중복, "
                            + "요청 body 상한 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "`-404` — 이 환경에서 endpoint가 비활성(경로 자체가 존재하지 않음)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502",
                    description = "`-1009` — AI 오류 응답·타임아웃·전송 실패·비 JSON·결과 계약 불일치. "
                            + "AI가 응답한 경우 `X-Ai-Error-Code` 헤더 동반")
    })
    @PostMapping("/ai-results")
    ResponseEntity<TimelineAiTestResponse> generate(
            @Parameter(description = "API 버전", example = "v1")
            @PathVariable String applicationVersion,
            @RequestBody TimelineAiTestRequest request,
            @Parameter(hidden = true) HttpServletResponse response);
}
