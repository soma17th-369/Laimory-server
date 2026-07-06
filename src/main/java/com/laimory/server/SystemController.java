package com.laimory.server;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

@Tag(name = "System", description = "운영 확인용(공통 envelope 미적용)")
@RestController
@RequiredArgsConstructor
public class SystemController {

    private final DataSource dataSource;

    @Operation(summary = "헬스체크", description = "DB 연결을 확인한다. 앱-facing envelope이 아닌 평문 JSON으로 응답한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "정상(status=UP)", useReturnTypeSchema = true),
            @ApiResponse(responseCode = "503", description = "DB 연결 실패(status=DOWN, error 포함)", useReturnTypeSchema = true)
    })
    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> status() {
        Map<String, String> result = new HashMap<>();

        try (Connection conn = dataSource.getConnection()) {
            result.put("status", "UP");
            result.put("db", "connected");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("status", "DOWN");
            result.put("db", "disconnected");
            result.put("error", e.getMessage());
            return ResponseEntity.status(503).body(result);
        }
    }
}
