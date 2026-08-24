package com.laimory.server;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

/**
 * 운영 확인용 API의 문서·계약(구현은 {@link SystemController}). 공통 envelope 미적용(평문 JSON).
 */
@Tag(name = "System", description = "운영 확인용(공통 envelope 미적용)")
public interface SystemApi {

    @Operation(summary = "헬스체크", description = "DB 연결을 확인한다. 앱-facing envelope이 아닌 평문 JSON으로 응답한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "정상(status=UP)", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "503", description = "DB 연결 실패(status=DOWN)", useReturnTypeSchema = true)
    })
    @GetMapping("/status")
    ResponseEntity<Map<String, String>> status();
}
