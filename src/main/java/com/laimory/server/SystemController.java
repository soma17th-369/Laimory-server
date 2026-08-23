package com.laimory.server;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

/**
 * 운영 확인용 API 구현. HTTP 문서·계약은 {@link SystemApi}.
 */
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
            result.put("status", "DOWN");
            result.put("db", "disconnected");
            result.put("error", e.getMessage());
            return ResponseEntity.status(503).body(result);
        }
    }
}
