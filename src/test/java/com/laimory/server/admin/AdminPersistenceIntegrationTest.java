package com.laimory.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.ServerApplication;
import com.laimory.server.appconfig.AppConfigRepository;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocumentId;
import com.laimory.server.terms.repository.TermDocumentRepository;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** production Security chain + Redis CSRF session + MySQL commit + 공개 API read-back. */
@Tag("integration")
@ActiveProfiles("docker")
@SpringBootTest(classes = ServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"APP_ENV=local", "APP_ADMIN_PORT=0", "management.server.port=0"})
@DirtiesContext
class AdminPersistenceIntegrationTest {
    @Autowired AdminServer server;
    @Autowired ObjectMapper mapper;
    @Autowired AppConfigRepository configs;
    @Autowired TermDocumentRepository documents;
    @Autowired JdbcTemplate jdbc;
    private HttpClient client;
    private JsonNode csrf;

    @BeforeEach
    void bootstrap() throws Exception {
        client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        var result = request("GET", "/admin/api/csrf", null);
        assertThat(result.statusCode()).isEqualTo(200);
        csrf = mapper.readTree(result.body()).path("body");
    }

    @Test
    void versionUpdateCommitsAndIntroImmediatelyReadsIt_preservingDebugMessage() throws Exception {
        var rows = configs.findAll();
        assertThat(rows).hasSize(1);
        var original = rows.getFirst();
        try {
            var result = request("PUT", "/admin/api/app-config", "{\"minAppVersion\":4,\"recommendAppVersion\":9223372036854775807}");
            assertThat(result.statusCode()).isEqualTo(200);
            var introResponse = request("GET", "/api/v1/intro", null);
            assertThat(introResponse.statusCode()).isEqualTo(200);
            JsonNode intro = mapper.readTree(introResponse.body()).path("body");
            assertThat(intro.path("minAppVersion").asLong()).isEqualTo(4);
            assertThat(intro.path("recommendAppVersion").asLong()).isEqualTo(Long.MAX_VALUE);
            assertThat(configs.findById(original.getAppConfigId()).orElseThrow().getDebugTestMessage())
                    .isEqualTo(original.getDebugTestMessage());
        } finally {
            jdbc.update("UPDATE app_config SET min_app_version = ?, recommend_app_version = ? WHERE app_config_id = ?",
                    original.getMinAppVersion(), original.getRecommendAppVersion(), original.getAppConfigId());
        }
    }

    @Test
    void publishedDocumentIsCommittedAndBecomesCurrentWithoutUpdatingEarlierRow() throws Exception {
        String version = "946100000000000000000.10";
        var id = new TermDocumentId(TermType.PRIVACY_POLICY, version);
        assertThat(documents.existsById(id)).isFalse();
        try {
            String json = mapper.writeValueAsString(Map.of("termType", "PRIVACY_POLICY", "version", version,
                    "title", "admin integration fixture", "contentUrl", "https://example.com/admin-fixture",
                    "publicationConfirmed", true));
            var result = request("POST", "/admin/api/terms", json);
            assertThat(result.statusCode()).isEqualTo(201);
            var body = mapper.readTree(result.body()).path("body");
            assertThat(body.path("saved").path("version").asText()).isEqualTo(version);
            assertThat(body.path("current").path("version").asText()).isEqualTo(version);
            var saved = documents.findById(id).orElseThrow();
            assertThat(saved.getTitle()).isEqualTo("admin integration fixture");
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getModifiedBy()).isNull();
            assertThat(request("POST", "/admin/api/terms", json.replace("admin integration fixture", "replacement")).statusCode())
                    .isEqualTo(409);
            assertThat(documents.findById(id).orElseThrow().getTitle()).isEqualTo(saved.getTitle());
        } finally {
            documents.deleteById(id);
        }
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        String origin = "http://localhost:" + server.boundPort();
        var request = HttpRequest.newBuilder(URI.create(origin + path));
        if (body != null) {
            request.header("Origin", origin).header("Content-Type", "application/json")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText());
        }
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
