package com.laimory.server.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.auth.token.JwtTokens;
import com.laimory.server.common.logging.TransactionIdFilter;
import com.laimory.server.config.SecurityConfig;
import com.laimory.server.timeline.service.TimelineMetrics;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
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
                "APP_ENV=test"
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

    @MockitoBean
    private JwtTokens jwtTokens;

    @Test
    void exposesOnlyHealthAndPrometheusOnManagementPort() throws Exception {
        assertThat(http.getForEntity(applicationUrl("/actuator/prometheus"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<String> health = http.getForEntity(managementUrl("/actuator/health"), String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode healthBody = objectMapper.readTree(health.getBody());
        assertThat(healthBody.properties()).extracting(Map.Entry::getKey).containsOnly("status");
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

        String metrics = http.getForObject(managementUrl("/actuator/prometheus"), String.class);

        assertThat(metrics)
                .contains("http_server_requests_seconds_count")
                .contains("http_client_requests_seconds_count")
                .contains("application=\"laimory\"")
                .contains("environment=\"test\"")
                .contains("uri=\"/probe/{id}\"")
                .contains("laimory_timeline_draft_creation_total")
                .contains("laimory_timeline_task_terminal_total")
                .contains("laimory_timeline_callback_duration_seconds_count")
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
            TimelineMetrics.class,
            ProbeController.class
    })
    static class TestApplication {
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
