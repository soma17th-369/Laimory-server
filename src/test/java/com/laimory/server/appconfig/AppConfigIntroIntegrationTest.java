package com.laimory.server.appconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.logging.TransactionIds;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * 배포 health gate({@code /api/v1/intro})의 실제 MySQL {@code app_config} row → repository → service →
 * response mapper → controller 경로를 embedded Tomcat 실 HTTP로 검증한다(controller slice mock으로는
 * 배포 경로를 증명하지 못하는 구간).
 *
 * <p>테스트는 row를 삽입·삭제하지 않는 read-only 검증이다. 매 run fresh volume을 만드는 CI에서는
 * non-empty 전제 확인이 곧 schema.sql seed 실행 증명을 겸하고, 기존 local volume에서는 현재 row를
 * 그대로 사용한다(비어 있으면 실제 endpoint와 같이 실패).
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@Tag("integration")
@ActiveProfiles("docker")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // management 포트는 9090 고정이라 로컬에서 떠 있는 서버·병렬 컨텍스트와 충돌하지 않게 random으로 돌린다.
        properties = "management.server.port=0")
class AppConfigIntroIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private AppConfigRepository appConfigRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void intro_servesAppConfigRowFromRealMysqlOverHttp() throws Exception {
        List<AppConfig> snapshot = appConfigRepository.findAll();
        assertThat(snapshot).isNotEmpty();

        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/intro", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(TransactionIds.HEADER_NAME)).isNotBlank();

        JsonNode envelope = objectMapper.readTree(response.getBody());
        assertThat(envelope.path("header").path("code").asInt()).isEqualTo(0);
        JsonNode body = envelope.path("body");
        assertThat(body.isObject()).isTrue();

        // findFirstBy()는 복수 row에서 어느 row를 고를지 비결정이므로 특정 row를 강제하지 않는다 —
        // 응답이 snapshot의 "어느 한" row와 정확히 일치하면 DB-backed 응답임이 증명된다(flake 없음).
        Long minAppVersion = body.hasNonNull("minAppVersion") ? body.get("minAppVersion").asLong() : null;
        Long recommendAppVersion =
                body.hasNonNull("recommendAppVersion") ? body.get("recommendAppVersion").asLong() : null;
        String debugTestMessage = body.hasNonNull("debugTestMessage") ? body.get("debugTestMessage").asText() : null;
        assertThat(snapshot).anySatisfy(row -> {
            assertThat(minAppVersion).isEqualTo(row.getMinAppVersion());
            assertThat(recommendAppVersion).isEqualTo(row.getRecommendAppVersion());
            assertThat(debugTestMessage).isEqualTo(row.getDebugTestMessage());
        });
    }
}
