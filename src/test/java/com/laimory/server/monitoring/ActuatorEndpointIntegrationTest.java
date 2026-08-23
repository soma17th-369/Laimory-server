package com.laimory.server.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.auth.token.JwtTokens;
import com.laimory.server.common.logging.TransactionIdFilter;
import com.laimory.server.common.monitoring.BuildInfoMetrics;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.push.PushMetrics;
import com.laimory.server.timeline.service.TimelineMetrics;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 실제 app/management random port를 띄워 Actuator 노출·보안·로그·Prometheus tag 계약을 검증한다.
 * DB/Redis/OAuth 인프라는 이 경계와 무관하므로 auto-configuration에서 제외한다.
 */
@SpringBootTest(
        classes = ActuatorEndpointIntegrationTest.TestApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "APP_ENV=test",
                "APP_COMMIT_SHA=abcdef1234567890abcdef1234567890abcdef12"
        })
@AutoConfigureObservability
class ActuatorEndpointIntegrationTest {

    @LocalServerPort
    private int applicationPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Autowired
    private TimelineMetrics timelineMetrics;

    @MockitoBean
    private JwtTokens jwtTokens;

    @Test
    void exposesOnlyHealthAndPrometheusOnManagementPort() throws Exception {
        assertThat(http.getForEntity(applicationUrl("/actuator/prometheus"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> health = http.getForEntity(managementUrl("/actuator/health"), String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode healthBody = objectMapper.readTree(health.getBody());
        // probes 활성화(#335)로 그룹 이름 목록(groups)이 본문에 추가된다 — 컴포넌트 상세는 여전히 없다.
        assertThat(healthBody.properties()).extracting(Map.Entry::getKey).containsOnly("status", "groups");
        assertThat(healthBody.path("status").asText()).isEqualTo("UP");

        ResponseEntity<String> prometheus =
                http.getForEntity(managementUrl("/actuator/prometheus"), String.class);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(prometheus.getBody()).contains("jvm_memory_used_bytes");

        assertThat(http.getForEntity(managementUrl("/actuator"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity(managementUrl("/actuator/env"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(http.getForEntity(applicationUrl("/probe/public"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity(applicationUrl("/a/api/probe"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void exposesReadinessGroupOnMainServerPortAtReadyz() throws Exception {
        // ALB 헬스체크 계약(#335): readiness 그룹은 메인 포트 /readyz로 노출된다 — 별도 관리
        // 컨텍스트는 메인 앱이 연결을 못 받아도 UP일 수 있어 실제 트래픽 경로를 검사해야 한다.
        ResponseEntity<String> readyz = http.getForEntity(applicationUrl("/readyz"), String.class);
        assertThat(readyz.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(readyz.getBody());
        assertThat(body.properties()).extracting(Map.Entry::getKey).containsOnly("status");
        assertThat(body.path("status").asText()).isEqualTo("UP");

        // 관리 포트의 그룹 하위 경로도 동작한다. liveness는 additional-path를 주지 않았으므로
        // 메인 포트에 /livez가 생기지 않는다(readyz만 노출 — YAGNI).
        assertThat(http.getForEntity(managementUrl("/actuator/health/readiness"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity(applicationUrl("/livez"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void prometheusContainsCommonTagsAndNeverContainsDynamicUriValues() {
        String taskId = "task-id-must-not-be-a-label";
        String latitude = "37.123456";
        String longitude = "127.654321";

        RestClient restClient = restClientBuilder.clone()
                .baseUrl(applicationUrl(""))
                .build();
        restClient.get()
                .uri("/probe/{id}", taskId)
                .retrieve()
                .toBodilessEntity();

        WebClient webClient = webClientBuilder.clone()
                .baseUrl(applicationUrl(""))
                .build();
        webClient.get()
                .uri(builder -> builder.path("/probe/public")
                        .queryParam("x", longitude)
                        .queryParam("y", latitude)
                        .build())
                .retrieve()
                .toBodilessEntity()
                .block();

        Timer.Sample callback = timelineMetrics.startCallback();
        timelineMetrics.recordCallback(callback);

        String metrics = http.getForObject(managementUrl("/actuator/prometheus"), String.class);

        assertThat(metrics)
                .contains("http_server_requests_seconds_count")
                .contains("http_client_requests_seconds_count")
                .contains("http_client_requests_seconds_bucket")
                .contains("application=\"laimory\"")
                .contains("environment=\"test\"")
                .contains("uri=\"/probe/{id}\"")
                .contains("laimory_timeline_draft_creation_total")
                .contains("laimory_timeline_task_terminal_total")
                .contains("laimory_timeline_callback_duration_seconds_count")
                .contains("laimory_timeline_callback_duration_seconds_bucket")
                .contains("laimory_push_delivery_total")
                .contains("laimory_build_info{application=\"laimory\",commit=\"abcdef123456\",environment=\"test\"} 1")
                .doesNotContain(taskId, latitude, longitude);
    }

    @Test
    void successfulManagementScrapesDoNotCreateApplicationAccessLogs() {
        Logger accessLogger = (Logger) LoggerFactory.getLogger("http.access");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        accessLogger.addAppender(appender);
        try {
            assertThat(http.getForEntity(managementUrl("/actuator/health"), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(http.getForEntity(managementUrl("/actuator/prometheus"), String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);

            assertThat(appender.list).isEmpty();
        } finally {
            accessLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private String applicationUrl(String path) {
        return "http://localhost:" + applicationPort + path;
    }

    private String managementUrl(String path) {
        return "http://localhost:" + managementPort + path;
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class,
            SessionAutoConfiguration.class,
            OAuth2ClientAutoConfiguration.class
    })
    @Import({
            SecurityConfig.class,
            TransactionIdFilter.class,
            BuildInfoMetrics.class,
            PushMetrics.class,
            TimelineMetrics.class,
            ProbeController.class
    })
    static class TestApplication {

        /** DB 없는 최소 앱 — JWT 필터의 active 검사(#305)는 항상 활성으로 stub한다(이 경계와 무관). */
        @org.springframework.context.annotation.Bean
        com.laimory.server.user.service.UserAccountAccessService userAccountAccessService() {
            return userId -> true;
        }

        /**
         * readiness 그룹(#335)이 include하는 db·redis contributor의 stub — 이 컨텍스트는 실제
         * DB/Redis autoconfig을 제외하므로, 없으면 그룹 멤버십 검증이 기동을 실패시킨다.
         */
        @org.springframework.context.annotation.Bean
        org.springframework.boot.actuate.health.HealthIndicator dbHealthIndicator() {
            return () -> org.springframework.boot.actuate.health.Health.up().build();
        }

        @org.springframework.context.annotation.Bean
        org.springframework.boot.actuate.health.HealthIndicator redisHealthIndicator() {
            return () -> org.springframework.boot.actuate.health.Health.up().build();
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/{id}")
        Map<String, String> probe(@PathVariable String id) {
            return Map.of("id", id);
        }

        @GetMapping("/a/api/probe")
        Map<String, String> protectedProbe() {
            return Map.of("status", "ok");
        }
    }
}
