package com.laimory.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.ServerApplication;
import com.laimory.server.appconfig.AppConfigRepository;
import com.laimory.server.appconfig.AppConfigService;
import com.laimory.server.notice.entity.Notice;
import com.laimory.server.notice.repository.NoticeRepository;
import com.laimory.server.notice.service.NoticeService;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocumentId;
import com.laimory.server.terms.repository.TermDocumentRepository;
import com.laimory.server.terms.service.TermCatalogService;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
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
    @Autowired NoticeRepository notices;
    // fixture 원복·삭제는 관리자 쓰기(evict)를 거치지 않으므로 해당 캐시(#491)를 직접 비운다 — Redis 캐시는 context를 넘어 남는다.
    @Autowired @Qualifier("redisCacheManager") CacheManager caches;
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
            caches.getCache(AppConfigService.CACHE_NAME).clear();
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
            // 관리자 쓰기는 결과를 싣지 않는다(#528) — 이력 조회(단일 원천)로 새 current를 확인한다.
            assertThat(mapper.readTree(result.body()).get("body").isNull()).isTrue();
            JsonNode privacy = null;
            for (JsonNode group : mapper.readTree(request("GET", "/admin/api/terms", null).body()).path("body")) {
                if (group.path("termType").asText().equals("PRIVACY_POLICY")) privacy = group;
            }
            assertThat(privacy).isNotNull();
            assertThat(privacy.path("current").path("version").asText()).isEqualTo(version);
            var saved = documents.findById(id).orElseThrow();
            assertThat(saved.getTitle()).isEqualTo("admin integration fixture");
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getModifiedBy()).isNull();
            assertThat(request("POST", "/admin/api/terms", json.replace("admin integration fixture", "replacement")).statusCode())
                    .isEqualTo(409);
            assertThat(documents.findById(id).orElseThrow().getTitle()).isEqualTo(saved.getTitle());
        } finally {
            documents.deleteById(id);
            caches.getCache(TermCatalogService.CACHE_NAME).clear();
        }
    }

    @Test
    void registeredNoticeIsPublicWithContentUrlUntilHidden() throws Exception {
        Long noticeId = null;
        try {
            var created = request("POST", "/admin/api/notices", mapper.writeValueAsString(Map.of(
                    "title", "admin integration notice", "contentUrl", "https://example.com/admin-notice-fixture")));
            assertThat(created.statusCode()).isEqualTo(201);
            assertThat(mapper.readTree(created.body()).get("body").isNull()).isTrue();
            // 등록 응답은 ID를 싣지 않는다(#528) — 관리자 목록(최신 순, 숨김 포함)의 첫 항목이 방금 등록한 공지다.
            JsonNode adminListed = mapper.readTree(request("GET", "/admin/api/notices", null).body()).path("body");
            assertThat(adminListed.get(0).path("title").asText()).isEqualTo("admin integration notice");
            noticeId = adminListed.get(0).path("noticeId").asLong();
            assertThat(notices.findByNoticeId(noticeId).orElseThrow().getCreatedAt()).isNotNull();

            JsonNode listed = mapper.readTree(request("GET", "/api/v1/notices", null).body()).path("body").path("notices");
            // 최신 순 목록이라 방금 등록한 공지가 첫 항목이고, 원문 대신 page URL만 실린다.
            assertThat(listed.get(0).path("noticeId").asLong()).isEqualTo(noticeId);
            assertThat(listed.get(0).path("contentUrl").asText()).isEqualTo("https://example.com/admin-notice-fixture");
            assertThat(listed.get(0).has("body")).isFalse();
            assertThat(listed.get(0).path("publishedAt").asText()).isNotBlank();

            assertThat(request("PUT", "/admin/api/notices/" + noticeId + "/visibility", "{\"hidden\":true}").statusCode())
                    .isEqualTo(200);
            JsonNode afterHiding = mapper.readTree(request("GET", "/api/v1/notices", null).body()).path("body").path("notices");
            assertThat(afterHiding.findValues("noticeId")).extracting(JsonNode::asLong).doesNotContain(noticeId);
            assertThat(notices.findByNoticeId(noticeId).orElseThrow().isHidden()).isTrue();
        } finally {
            if (noticeId != null) notices.deleteById(noticeId);
            caches.getCache(NoticeService.POPUP_CACHE_NAME).clear();
        }
    }

    @Test
    void popupNoticeIsSelectedUntilHiddenAndSingleLookupFollowsVisibility() throws Exception {
        Long noticeId = null;
        try {
            assertThat(request("POST", "/admin/api/notices", mapper.writeValueAsString(Map.of(
                    "title", "admin popup notice", "contentUrl", "https://example.com/admin-popup-fixture"))).statusCode())
                    .isEqualTo(201);
            noticeId = mapper.readTree(request("GET", "/admin/api/notices", null).body()).path("body").get(0)
                    .path("noticeId").asLong();
            assertThat(notices.findByNoticeId(noticeId).orElseThrow().isPopup()).isFalse();

            // 팝업 지정은 썸네일이 있어야 한다(#560) — 저장된 파일명이 V9 컬럼으로 왕복한다.
            assertThat(request("PUT", "/admin/api/notices/" + noticeId + "/popup", "{\"popup\":true}").statusCode())
                    .isEqualTo(400);
            assertThat(request("PUT", "/admin/api/notices/" + noticeId + "/thumbnail",
                    "{\"filename\":\"0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a6.webp\"}").statusCode()).isEqualTo(200);
            assertThat(notices.findByNoticeId(noticeId).orElseThrow().getThumbnailFilename())
                    .isEqualTo("0199a1b2-c3d4-7e5f-8a90-b1c2d3e4f5a6.webp");
            assertThat(request("PUT", "/admin/api/notices/" + noticeId + "/popup", "{\"popup\":true}").statusCode())
                    .isEqualTo(200);
            assertThat(notices.findByPopupTrueAndHiddenFalseOrderByNoticeIdDesc()).extracting(Notice::getNoticeId)
                    .contains(noticeId);
            JsonNode single = mapper.readTree(request("GET", "/api/v1/notices/" + noticeId, null).body()).path("body");
            assertThat(single.path("title").asText()).isEqualTo("admin popup notice");
            assertThat(single.path("contentUrl").asText()).isEqualTo("https://example.com/admin-popup-fixture");

            // 숨기면 지정은 남은 채 팝업·단건 조회에서 빠지고, 다시 노출하면 팝업으로 복귀한다.
            assertThat(request("PUT", "/admin/api/notices/" + noticeId + "/visibility", "{\"hidden\":true}").statusCode())
                    .isEqualTo(200);
            assertThat(notices.findByNoticeId(noticeId).orElseThrow().isPopup()).isTrue();
            assertThat(notices.findByPopupTrueAndHiddenFalseOrderByNoticeIdDesc()).extracting(Notice::getNoticeId)
                    .doesNotContain(noticeId);
            assertThat(request("GET", "/api/v1/notices/" + noticeId, null).statusCode()).isEqualTo(404);

            assertThat(request("PUT", "/admin/api/notices/" + noticeId + "/visibility", "{\"hidden\":false}").statusCode())
                    .isEqualTo(200);
            assertThat(notices.findByPopupTrueAndHiddenFalseOrderByNoticeIdDesc()).extracting(Notice::getNoticeId)
                    .contains(noticeId);
        } finally {
            if (noticeId != null) notices.deleteById(noticeId);
            caches.getCache(NoticeService.POPUP_CACHE_NAME).clear();
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
