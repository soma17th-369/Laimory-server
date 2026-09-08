package com.laimory.server.admin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.appconfig.AppConfig;
import com.laimory.server.appconfig.AppConfigController;
import com.laimory.server.appconfig.AppConfigRepository;
import com.laimory.server.appconfig.AppConfigService;
import com.laimory.server.auth.security.ApiErrorResponseWriter;
import com.laimory.server.common.error.GlobalExceptionHandler;
import com.laimory.server.common.logging.TrustedEdgeRequestFilter;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.repository.TermDocumentRepository;
import com.laimory.server.terms.service.TermDocumentRegistrationService;
import com.laimory.server.terms.service.TermDocumentService;
import java.net.CookieManager;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.session.SessionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** DB를 mock하되 실제 Tomcat connector·filter·CSRF session·MVC 경계는 모두 실 HTTP로 검증한다. */
@SpringBootTest(classes = AdminHttpTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"APP_ENV=local", "APP_ADMIN_PORT=0", "management.server.port=0",
                "management.endpoint.health.group.readiness.include=readinessState", "SWAGGER_ENABLED=true"})
@DirtiesContext
class AdminHttpTest {
    @LocalServerPort int mainPort;
    @LocalManagementPort int managementPort;
    @Autowired AdminServer server;
    @Autowired ObjectMapper mapper;
    @MockitoBean TermDocumentService documents;
    @MockitoBean TermDocumentRepository repository;
    @MockitoBean AppConfigRepository configs;
    private HttpClient client;
    private JsonNode csrf;

    @BeforeEach
    void setUp() throws Exception {
        client = HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(5)).build();
        AppConfig config = new AppConfig();
        config.updateVersions(1L, 2L);
        when(configs.findTop2ByOrderByAppConfigIdAsc()).thenReturn(List.of(config));
        when(documents.findAllDocuments()).thenReturn(List.of());
        when(documents.findCurrentDocuments(any())).thenReturn(List.of());
        HttpResponse<String> bootstrap = request("GET", "/admin/api/csrf", null, null, false);
        assertThat(bootstrap.statusCode()).isEqualTo(200);
        csrf = mapper.readTree(bootstrap.body()).path("body");
        assertThat(csrf.path("token").asText()).isNotBlank();
    }

    @Test
    void onlyActualAdminPortServesPage_andPublicManagementContinue() throws Exception {
        assertThat(server.boundPort()).isPositive().isNotEqualTo(mainPort).isNotEqualTo(managementPort);
        for (String path : List.of("/admin", "/admin/", "/admin/index.html", "/admin/admin.js", "/admin/admin.css", "/admin/api/terms")) {
            assertThat(request("GET", path, null, null, false).statusCode()).as(path).isEqualTo(200);
            assertThat(raw(mainPort, path, "Host: localhost:" + server.boundPort())).as(path).startsWith("HTTP/1.1 404");
            assertThat(raw(managementPort, path, "Host: localhost:" + server.boundPort())).as(path).startsWith("HTTP/1.1 404");
        }
        assertThat(raw(mainPort, "/api/v1/intro", "Host: localhost:" + mainPort)).startsWith("HTTP/1.1 200");
        assertThat(raw(mainPort, "/readyz", "Host: localhost:" + mainPort)).startsWith("HTTP/1.1 200");
        assertThat(raw(managementPort, "/actuator/health", "Host: localhost:" + managementPort)).startsWith("HTTP/1.1 200");
        String openApi = raw(mainPort, "/v3/api-docs", "Host: localhost:" + mainPort);
        assertThat(openApi).startsWith("HTTP/1.1 200").doesNotContain("/admin");
    }

    @Test
    void spoofedAuthorityXffAndEncodedPathsDoNotBypassPortGuard() throws Exception {
        int port = server.boundPort();
        for (String host : List.of("evil.example:" + port, "localhost.evil:" + port, "localhost", "localhost:0", "127.0.0.2:" + port)) {
            assertThat(raw(port, "/admin/api/csrf", "Host: " + host)).as(host).startsWith("HTTP/1.1 404");
        }
        assertThat(raw(port, "/admin/api/csrf", "Host: 127.0.0.1:" + port)).startsWith("HTTP/1.1 200");
        assertThat(raw(port, "/admin/api/csrf", "Host: localhost:" + port, "X-Forwarded-For: 127.0.0.1")).startsWith("HTTP/1.1 404");
        assertThat(raw(port, "/admin/api/csrf", "Host: localhost:" + port, "Host: localhost:" + port)).startsWith("HTTP/1.1 400");
        assertThat(raw(port, "/admin/api/csrf")).startsWith("HTTP/1.1 400");
        for (String path : List.of("/%61dmin/api/csrf", "/admin;ignored/api/csrf", "/a/../admin/api/csrf")) {
            assertThat(raw(mainPort, path, "Host: localhost:" + port)).as(path).startsWith("HTTP/1.1 404");
        }
        // 기존 trusted-edge wrapper가 serverPort를 443으로 바꿔도 local port 판정은 유지된다.
        assertThat(raw(port, "/admin/api/csrf", "Host: localhost:" + port, "X-Forwarded-Proto: https"))
                .startsWith("HTTP/1.1 200");
        assertThat(raw(mainPort, "/admin/api/csrf", "Host: localhost:" + port, "X-Forwarded-Proto: https"))
                .startsWith("HTTP/1.1 404");
    }

    @Test
    void unsafeRequestsRequirePairedOriginCsrfAndJson() throws Exception {
        String json = "{\"minAppVersion\":3,\"recommendAppVersion\":5}";
        String cookie = ((CookieManager) client.cookieHandler().orElseThrow()).getCookieStore().getCookies()
                .stream().map(Object::toString).collect(java.util.stream.Collectors.joining("; "));
        assertThat(rawRequest(server.boundPort(), "PUT", "/admin/api/app-config", json,
                "Host: evil.example:" + server.boundPort(), "Origin: " + origin(), "Cookie: " + cookie,
                csrf.path("headerName").asText() + ": " + csrf.path("token").asText(),
                "Content-Type: application/json")).startsWith("HTTP/1.1 404");
        for (String origin : new String[] {null, "null", "https://evil.example", "http://127.0.0.1:" + server.boundPort()}) {
            assertThat(request("PUT", "/admin/api/app-config", json, origin, true).statusCode()).isEqualTo(403);
        }
        assertThat(request("PUT", "/admin/api/app-config", json, origin(), false).statusCode()).isEqualTo(403);
        String token = csrf.path("token").asText();
        ((com.fasterxml.jackson.databind.node.ObjectNode) csrf).put("token", "invalid");
        assertThat(request("PUT", "/admin/api/app-config", json, origin(), true).statusCode()).isEqualTo(403);
        ((com.fasterxml.jackson.databind.node.ObjectNode) csrf).put("token", token);
        verifyNoInteractions(configs);
        assertThat(request("PUT", "/admin/api/app-config", json, origin(), true).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/v1/intro", null, null, false).body()).contains("\"minAppVersion\":3");
        HttpRequest form = HttpRequest.newBuilder(URI.create(origin() + "/admin/api/app-config"))
                .header("Origin", origin()).header(csrf.path("headerName").asText(), token)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .PUT(HttpRequest.BodyPublishers.ofString("minAppVersion=8&recommendAppVersion=9")).build();
        assertThat(client.send(form, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(415);
        HttpResponse<String> preflight = request("OPTIONS", "/admin/api/app-config", null, "https://evil.example", false);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void inputValidation_andInsertDuplicateMapToHttpStatus() throws Exception {
        String type = TermType.values()[0].name();
        String json = "{\"termType\":\"" + type + "\",\"version\":\"2.0\",\"title\":\"new\",\"contentUrl\":\"https://example.com/doc\",\"publicationConfirmed\":true}";
        for (String bad : List.of(json.replace("2.0", "01.0"), json.replace("https:", "http:"), json.replace("true", "false"), json.replace(type, "UNSUPPORTED"))) {
            assertThat(request("POST", "/admin/api/terms", bad, origin(), true).statusCode()).isEqualTo(400);
        }
        verifyNoInteractions(repository);
        doThrow(new DataIntegrityViolationException("PK", new SQLException("duplicate", "23000", 1062)))
                .when(repository).insert(any(), any(), any(), any(), any());
        HttpResponse<String> duplicate = request("POST", "/admin/api/terms", json, origin(), true);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("-3003");
        for (String versions : List.of("{}", "{\"minAppVersion\":0,\"recommendAppVersion\":1}",
                "{\"minAppVersion\":3,\"recommendAppVersion\":2}", "{\"minAppVersion\":1.5,\"recommendAppVersion\":2}",
                "{\"minAppVersion\":1,\"recommendAppVersion\":9223372036854775808}")) {
            assertThat(request("PUT", "/admin/api/app-config", versions, origin(), true).statusCode()).isEqualTo(400);
        }
    }

    private String origin() { return "http://localhost:" + server.boundPort(); }

    private HttpResponse<String> request(String method, String path, String body, String origin, boolean token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin() + path)).timeout(Duration.ofSeconds(10));
        if (origin != null) request.header("Origin", origin);
        if (token) request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static String raw(int port, String path, String... headers) throws Exception {
        return rawRequest(port, "GET", path, "", headers);
    }

    private static String rawRequest(int port, String method, String path, String body, String... headers) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write((method + " " + path + " HTTP/1.1\r\n" + String.join("\r\n", headers)
                    + "\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                    + "\r\nConnection: close\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class, SessionAutoConfiguration.class},
            excludeName = "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration")
    @Import({AdminWebConfiguration.class, AdminPageController.class, AdminApiController.class,
            TermDocumentRegistrationService.class, AppConfigService.class, AppConfigController.class,
            GlobalExceptionHandler.class, TrustedEdgeRequestFilter.class})
    static class TestApplication {
        @Bean ApiErrorResponseWriter errors(MessageSource messages, ObjectMapper mapper) {
            return new ApiErrorResponseWriter(messages, mapper);
        }
        @Bean @Order(200) SecurityFilterChain otherRequests(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
    }
}
