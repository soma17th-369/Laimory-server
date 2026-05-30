package com.laimory.server;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class StatusController {
    private final DataSource dataSource;

    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> health() {
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
