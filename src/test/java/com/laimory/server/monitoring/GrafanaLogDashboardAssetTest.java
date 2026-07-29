package com.laimory.server.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class GrafanaLogDashboardAssetTest {

    private static final Path DASHBOARD = Path.of(
            "deploy",
            "monitoring",
            "grafana",
            "provisioning",
            "dashboards",
            "json",
            "laimory-logs.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void errorAndWarnPanelLinksClickedPointToFilteredKibanaWindow() throws IOException {
        JsonNode dashboard = objectMapper.readTree(DASHBOARD.toFile());
        JsonNode panel = StreamSupport.stream(dashboard.path("panels").spliterator(), false)
                .filter(candidate -> "ERROR & WARN Logs".equals(candidate.path("title").asText()))
                .findFirst()
                .orElseThrow();
        JsonNode links = panel.path("fieldConfig").path("defaults").path("links");

        assertThat(links).hasSize(1);
        JsonNode link = links.get(0);
        assertThat(link.path("targetBlank").asBoolean()).isTrue();
        assertThat(link.path("title").asText()).isEqualTo("Kibana에서 ${__series.name} 로그 보기");
        assertThat(link.path("url").asText())
                .startsWith("/kibana/app/discover#/")
                .contains("${__value.time:date:iso}%7C%7C-5m")
                .contains("${__value.time:date:iso}%7C%7C%2B5m")
                .contains("environment%3A%22${environment}%22")
                .contains("level%3A%22${__series.name}%22")
                .contains("8e3c574e-45cc-430f-ae74-b91c277b8249")
                .contains("message%2Clevel%2CerrorCode%2Cpath%2CexceptionType");
    }
}
