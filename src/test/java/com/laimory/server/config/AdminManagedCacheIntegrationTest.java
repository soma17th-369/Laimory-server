package com.laimory.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.appconfig.AppConfig;
import com.laimory.server.appconfig.AppConfigRepository;
import com.laimory.server.appconfig.AppConfigResponse;
import com.laimory.server.appconfig.AppConfigService;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.notice.entity.Notice;
import com.laimory.server.notice.repository.NoticeRepository;
import com.laimory.server.notice.service.NoticeService;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.dto.TermResponse;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.repository.TermDocumentRepository;
import com.laimory.server.terms.service.TermCatalogService;
import com.laimory.server.terms.service.TermDocumentRegistrationService;
import com.laimory.server.terms.service.TermDocumentService;
import com.laimory.server.terms.service.TermDocumentSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 관리자 변경 값 캐시 3종(앱 설정·약관 current·팝업 공지 id, #491) ↔ 실 Redis·실 배선 검증.
 *
 * <p>여기서만 볼 수 있는 것: ① 캐시 값이 {@code GenericJackson2JsonRedisSerializer}로 Redis를 왕복해 같은 값으로
 * 복원되는지(역직렬화가 깨지면 error handler가 매 요청 miss로 강등해 캐시가 조용히 무력화된다) ② 키 모양과 TTL
 * 1시간 ③ 관리자 쓰기의 evict가 공유 키를 지워 다음 조회가 원본을 다시 읽는지 ④ 약관 등록의 상위 버전 검사가
 * 캐시가 아니라 DB를 보는지. 저장소는 실 Redis, DB 쪽은 repository mock이다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class AdminManagedCacheIntegrationTest {

    private static final long ONE_HOUR_MS = TimeUnit.HOURS.toMillis(1);

    @Autowired private AppConfigService appConfigService;
    @Autowired private TermDocumentService termDocumentService;
    @Autowired private TermDocumentRegistrationService termDocumentRegistrationService;
    @Autowired private NoticeService noticeService;

    @MockitoBean private AppConfigRepository appConfigRepository;
    @MockitoBean private TermDocumentRepository termDocumentRepository;
    @MockitoBean private NoticeRepository noticeRepository;

    @Autowired @Qualifier("redisCacheManager") private CacheManager caches;
    // TTL 검사는 gateway에 없는 PTTL이 필요하다 — 테스트는 arch 규칙 대상 밖이라 template 직접 사용.
    @Autowired private StringRedisTemplate stringRedisTemplate;
    @Autowired private MeterRegistry meterRegistry;

    @Value("${app.redis.key-prefix:}")
    private String keyPrefix;

    @BeforeEach
    @AfterEach
    void clearCaches() {
        caches.getCache(AppConfigService.CACHE_NAME).clear();
        caches.getCache(TermCatalogService.CACHE_NAME).clear();
        caches.getCache(NoticeService.POPUP_CACHE_NAME).clear();
    }

    @Test
    void appConfigIsServedFromRedisOnSecondReadWithOneHourTtl() {
        when(appConfigRepository.findTop2ByOrderByAppConfigIdAsc()).thenReturn(List.of(appConfig(3L, 5L)));

        appConfigService.getAppConfig("v1");
        AppConfigResponse cached = appConfigService.getAppConfig("v1");

        assertThat(cached.getMinAppVersion()).isEqualTo(3L);
        assertThat(cached.getRecommendAppVersion()).isEqualTo(5L);
        assertThat(cached.getDebugTestMessage()).isEqualTo("hello");
        verify(appConfigRepository, times(1)).findTop2ByOrderByAppConfigIdAsc();
        assertThat(stringRedisTemplate.getExpire(keyPrefix + AppConfigService.CACHE_NAME + ":all", TimeUnit.MILLISECONDS))
                .isGreaterThan(ONE_HOUR_MS - 30_000)
                .isLessThanOrEqualTo(ONE_HOUR_MS);
    }

    @Test
    void appConfigUpdateEvictsSoNextReadSeesNewVersions() {
        AppConfig row = appConfig(3L, 5L);
        when(appConfigRepository.findTop2ByOrderByAppConfigIdAsc()).thenReturn(List.of(row));
        appConfigService.getAppConfig("v1");

        appConfigService.updateVersions(7L, 9L);

        assertThat(appConfigService.getAppConfig("v1").getMinAppVersion()).isEqualTo(7L);
    }

    @Test
    void termCatalogIsLoadedOnceAndServesPublicAndSummaryReadsFromRedis() {
        when(termDocumentRepository.findDocumentCandidates(List.of(TermType.values()))).thenReturn(List.of(
                document(TermType.TERMS_OF_SERVICE, "1.10"),
                document(TermType.TERMS_OF_SERVICE, "1.9"),
                document(TermType.PRIVACY_POLICY, "2.0")));
        termDocumentService.findCurrentTerms("v1", List.of(TermType.TERMS_OF_SERVICE));

        List<TermResponse> publicTerms = termDocumentService.findCurrentTerms("v1",
                List.of(TermType.PRIVACY_POLICY, TermType.TERMS_OF_SERVICE));
        List<TermDocumentSummary> summaries = termDocumentService.findCurrentSummaries(List.of(TermType.TERMS_OF_SERVICE));

        assertThat(publicTerms).containsExactly(
                response(TermType.PRIVACY_POLICY, "2.0"),
                response(TermType.TERMS_OF_SERVICE, "1.10"));
        assertThat(summaries).containsExactly(new TermDocumentSummary(TermType.TERMS_OF_SERVICE, "1.10"));
        verify(termDocumentRepository, times(1)).findDocumentCandidates(List.of(TermType.values()));
        assertThat(stringRedisTemplate.getExpire(keyPrefix + TermCatalogService.CACHE_NAME + ":all", TimeUnit.MILLISECONDS))
                .isGreaterThan(ONE_HOUR_MS - 30_000)
                .isLessThanOrEqualTo(ONE_HOUR_MS);
    }

    @Test
    void termRegistrationComparesAgainstDatabaseCurrentEvenWhenCacheIsStale() {
        when(termDocumentRepository.findDocumentCandidates(List.of(TermType.values())))
                .thenReturn(List.of(document(TermType.TERMS_OF_SERVICE, "1.0")));
        termDocumentService.findCurrentTerms("v1", List.of(TermType.TERMS_OF_SERVICE));
        when(termDocumentRepository.findDocumentCandidates(List.of(TermType.TERMS_OF_SERVICE)))
                .thenReturn(List.of(document(TermType.TERMS_OF_SERVICE, "2.0")));

        assertThatThrownBy(() -> termDocumentRegistrationService.register(TermType.TERMS_OF_SERVICE, "1.5",
                "이용약관", "https://www.laimory.app/terms/terms-of-service/1.5", true))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getExceptionType())
                        .isEqualTo(ExceptionType.TERM_DOCUMENT_VERSION_CONFLICT));
        verify(termDocumentRepository, never()).insert(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void termRegistrationEvictsSoNextReadSeesNewCurrent() {
        when(termDocumentRepository.findDocumentCandidates(List.of(TermType.values())))
                .thenReturn(List.of(document(TermType.TERMS_OF_SERVICE, "1.0")))
                .thenReturn(List.of(document(TermType.TERMS_OF_SERVICE, "2.0")));
        when(termDocumentRepository.findDocumentCandidates(List.of(TermType.TERMS_OF_SERVICE)))
                .thenReturn(List.of(document(TermType.TERMS_OF_SERVICE, "1.0")));
        termDocumentService.findCurrentTerms("v1", List.of(TermType.TERMS_OF_SERVICE));

        termDocumentRegistrationService.register(TermType.TERMS_OF_SERVICE, "2.0",
                "이용약관", "https://www.laimory.app/terms/terms-of-service/2.0", true);

        assertThat(termDocumentService.findCurrentSummaries(List.of(TermType.TERMS_OF_SERVICE)))
                .containsExactly(new TermDocumentSummary(TermType.TERMS_OF_SERVICE, "2.0"));
    }

    @Test
    void popupNoticeIdsKeepLongElementsThroughRedisWithOneHourTtl() {
        when(noticeRepository.findByPopupTrueAndHiddenFalseOrderByNoticeIdDesc())
                .thenReturn(List.of(notice(15L), notice(12L)));
        noticeService.findPopupNoticeIds();

        List<Long> cached = noticeService.findPopupNoticeIds();

        assertThat(cached).containsExactly(15L, 12L);
        verify(noticeRepository, times(1)).findByPopupTrueAndHiddenFalseOrderByNoticeIdDesc();
        assertThat(stringRedisTemplate.getExpire(keyPrefix + NoticeService.POPUP_CACHE_NAME + ":all", TimeUnit.MILLISECONDS))
                .isGreaterThan(ONE_HOUR_MS - 30_000)
                .isLessThanOrEqualTo(ONE_HOUR_MS);
    }

    @Test
    void popupDesignationChangeEvictsPopupIds() {
        when(noticeRepository.findByPopupTrueAndHiddenFalseOrderByNoticeIdDesc())
                .thenReturn(List.of()).thenReturn(List.of(notice(15L)));
        when(noticeRepository.findByNoticeId(15L)).thenReturn(Optional.of(notice(15L)));
        noticeService.findPopupNoticeIds();

        noticeService.changePopup(15L, true);

        assertThat(noticeService.findPopupNoticeIds()).containsExactly(15L);
    }

    @Test
    void noticeVisibilityChangeEvictsPopupIds() {
        when(noticeRepository.findByPopupTrueAndHiddenFalseOrderByNoticeIdDesc())
                .thenReturn(List.of(notice(15L))).thenReturn(List.of());
        when(noticeRepository.findByNoticeId(15L)).thenReturn(Optional.of(notice(15L)));
        noticeService.findPopupNoticeIds();

        noticeService.changeVisibility(15L, true);

        assertThat(noticeService.findPopupNoticeIds()).isEmpty();
    }

    @Test
    void adminManagedCachesExposeStandardCacheMeters() {
        // 기동 시 캐시가 만들어져 있어야 Spring Boot가 meter를 바인딩한다(initial cache configuration).
        assertThat(meterRegistry.find("cache.gets").tag("cache", AppConfigService.CACHE_NAME).meters()).isNotEmpty();
        assertThat(meterRegistry.find("cache.gets").tag("cache", TermCatalogService.CACHE_NAME).meters()).isNotEmpty();
        assertThat(meterRegistry.find("cache.gets").tag("cache", NoticeService.POPUP_CACHE_NAME).meters()).isNotEmpty();
    }

    private static AppConfig appConfig(long minimum, long recommended) {
        AppConfig config = new AppConfig();
        ReflectionTestUtils.setField(config, "minAppVersion", minimum);
        ReflectionTestUtils.setField(config, "recommendAppVersion", recommended);
        ReflectionTestUtils.setField(config, "debugTestMessage", "hello");
        return config;
    }

    private static TermDocument document(TermType type, String version) {
        return TermDocument.of(type, version, type.name(), "https://www.laimory.app/terms/page/" + version);
    }

    private static TermResponse response(TermType type, String version) {
        return new TermResponse(type, version, type.name(), "https://www.laimory.app/terms/page/" + version);
    }

    private static Notice notice(long noticeId) {
        Notice notice = Notice.of("공지 " + noticeId, "https://www.laimory.app/notices/" + noticeId);
        ReflectionTestUtils.setField(notice, "noticeId", noticeId);
        return notice;
    }
}
