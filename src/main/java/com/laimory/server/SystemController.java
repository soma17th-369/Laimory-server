package com.laimory.server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

/**
 * 운영 확인용 API 구현. HTTP 문서·계약은 {@link SystemApi}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SystemController implements SystemApi {

    private final DataSource dataSource;

    @Override
    public ResponseEntity<Map<String, String>> status() {
        Map<String, String> result = new HashMap<>();

        try (Connection conn = dataSource.getConnection()) {
            result.put("status", "UP");
            result.put("db", "connected");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            // 공개 경로라 예외 원문(DB 계정·내부 주소 포함)을 응답에 싣지 않는다 — 진단은 이 로그가 담당한다.
            log.error("status probe db connection failed", e);
            result.put("status", "DOWN");
            result.put("db", "disconnected");
            return ResponseEntity.status(503).body(result);
        }
    }
}
