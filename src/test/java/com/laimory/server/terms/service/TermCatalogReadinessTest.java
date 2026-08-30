package com.laimory.server.terms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.laimory.server.terms.TermStage;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.repository.TermDocumentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * catalog 준비 판정(필수 종류 current 커버리지)과 기동 정합성 검사, bounded 전이 로그·metric 계약 검증.
 */
@ExtendWith(MockitoExtension.class)
class TermCatalogReadinessTest {

    private static final LocalDateTime NOW_KST = LocalDateTime.parse("2026-08-16T05:00:00");

    @Mock
    private TermDocumentRepository termDocumentRepository;
    @Mock
    private TermDocumentService termDocumentService;

    private SimpleMeterRegistry meterRegistry;
    private TermCatalogReadiness readiness;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        readiness = new TermCatalogReadiness(termDocumentRepository, termDocumentService,
                Clock.fixed(Instant.parse("2026-08-15T20:00:00Z"), ZoneOffset.UTC), meterRegistry);
        logger = (Logger) LoggerFactory.getLogger(TermCatalogReadiness.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @Test
    void stageWithAllRequiredCurrentDocuments_isReady() {
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(List.of(document(TermType.TERMS_OF_SERVICE)));

        TermCatalogReadiness.StageCatalog catalog = readiness.checkStage(TermStage.LOGIN, NOW_KST);

        assertThat(catalog.ready()).isTrue();
        assertThat(catalog.currentEnforcedDocuments()).hasSize(1);
        assertThat(readyGauge(TermStage.LOGIN)).isEqualTo(1.0);
        verify(termDocumentService).findCurrentSummaries(List.of(TermType.TERMS_OF_SERVICE), NOW_KST);
    }

    @Test
    void timelineStageWithoutConditionalLocationDocument_keepsRequiredStageReady() {
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(List.of(document(TermType.SENSITIVE_INFORMATION_CONSENT),
                        document(TermType.THIRD_PARTY_PROVISION_CONSENT),
                        document(TermType.CROSS_BORDER_TRANSFER_CONSENT)));

        TermCatalogReadiness.StageCatalog catalog =
                readiness.checkStage(TermStage.TIMELINE_FIRST_CREATE, NOW_KST);

        assertThat(catalog.ready()).isTrue();
        assertThat(catalog.currentEnforcedDocuments()).hasSize(3);
        assertThat(readyGauge(TermStage.TIMELINE_FIRST_CREATE)).isEqualTo(1.0);
    }

    @Test
    void conditionalDocumentReadinessAndFailOpen_haveSeparateMetrics() {
        when(termDocumentService.findCurrentSummaries(
                org.mockito.ArgumentMatchers.eq(List.of(TermType.LOCATION_BASED_SERVICE_TERMS)), any()))
                .thenReturn(List.of());
        when(termDocumentRepository.count()).thenReturn(4L);

        TermCatalogReadiness.ConditionalTermCatalog missing = readiness.checkConditionalTerm(
                TermType.LOCATION_BASED_SERVICE_TERMS, NOW_KST);
        readiness.recordConditionalFailOpen(TermType.LOCATION_BASED_SERVICE_TERMS);

        assertThat(missing.ready()).isFalse();
        assertThat(conditionalReadyGauge(TermType.LOCATION_BASED_SERVICE_TERMS)).isEqualTo(0.0);
        assertThat(meterRegistry.get(TermCatalogReadiness.CONDITIONAL_GATE_FAIL_OPEN_COUNTER)
                .tag("term_type", TermType.LOCATION_BASED_SERVICE_TERMS.name())
                .counter().count()).isEqualTo(1.0);

        when(termDocumentService.findCurrentSummaries(
                org.mockito.ArgumentMatchers.eq(List.of(TermType.LOCATION_BASED_SERVICE_TERMS)), any()))
                .thenReturn(List.of(document(TermType.LOCATION_BASED_SERVICE_TERMS)));
        TermCatalogReadiness.ConditionalTermCatalog recovered = readiness.checkConditionalTerm(
                TermType.LOCATION_BASED_SERVICE_TERMS, NOW_KST);

        assertThat(recovered.ready()).isTrue();
        assertThat(recovered.currentDocument()).contains(document(TermType.LOCATION_BASED_SERVICE_TERMS));
        assertThat(conditionalReadyGauge(TermType.LOCATION_BASED_SERVICE_TERMS)).isEqualTo(1.0);
    }

    @Test
    void missingRequiredCurrentDocument_marksStageNotReady() {
        // TERMS_OF_SERVICE의 current 문서 없음(활성화 전) — stage 전체 미준비.
        when(termDocumentService.findCurrentSummaries(anyCollection(), any())).thenReturn(List.of());

        TermCatalogReadiness.StageCatalog catalog = readiness.checkStage(TermStage.LOGIN, NOW_KST);

        assertThat(catalog.ready()).isFalse();
        assertThat(readyGauge(TermStage.LOGIN)).isEqualTo(0.0);
    }

    @Test
    void emptyCatalogTransition_logsWarnOnce_notError() {
        // seed 전(테이블 완전 비어있음)은 예정된 pre-activation fail-open — 경보(ERROR)가 아니라 WARN 1회다.
        when(termDocumentService.findCurrentSummaries(anyCollection(), any())).thenReturn(List.of());
        when(termDocumentRepository.count()).thenReturn(0L);

        readiness.checkStage(TermStage.LOGIN, NOW_KST);
        readiness.checkStage(TermStage.LOGIN, NOW_KST);

        assertThat(logAppender.list.stream().filter(event -> event.getLevel() == Level.ERROR)).isEmpty();
        assertThat(logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("not seeded")))
                .hasSize(1); // bounded — 지속 미준비 중 반복 없음
        assertThat(readyGauge(TermStage.LOGIN)).isEqualTo(0.0); // gauge는 수위와 무관하게 0
    }

    @Test
    void emptyCurrentWithSeededRows_logsErrorOnTransition() {
        // 행이 존재하는데(예: 전부 소문자 오타·다른 stage) current 후보가 0건 — seed 실수라 ERROR다.
        when(termDocumentService.findCurrentSummaries(anyCollection(), any())).thenReturn(List.of());
        when(termDocumentRepository.count()).thenReturn(4L);

        readiness.checkStage(TermStage.LOGIN, NOW_KST);

        assertThat(logAppender.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(1);
    }

    @Test
    void seededButBrokenTransition_logsErrorOnceUntilRecovery() {
        // 필수 종류 하나가 빠진 seed(행은 존재) — 예정 상태가 아니라 ERROR 경보 대상이다.
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(List.of(document(TermType.SENSITIVE_INFORMATION_CONSENT),
                        document(TermType.THIRD_PARTY_PROVISION_CONSENT)));

        readiness.checkStage(TermStage.TIMELINE_FIRST_CREATE, NOW_KST);
        readiness.checkStage(TermStage.TIMELINE_FIRST_CREATE, NOW_KST);
        long errorCount = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .count();
        assertThat(errorCount).isEqualTo(1); // bounded — 지속 미준비 중 반복 ERROR 없음

        // 회복 → INFO 1줄, 이후 다시 퇴행하면 새 ERROR 1줄.
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(List.of(document(TermType.SENSITIVE_INFORMATION_CONSENT),
                        document(TermType.THIRD_PARTY_PROVISION_CONSENT),
                        document(TermType.CROSS_BORDER_TRANSFER_CONSENT)));
        readiness.checkStage(TermStage.TIMELINE_FIRST_CREATE, NOW_KST);
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(List.of(document(TermType.SENSITIVE_INFORMATION_CONSENT),
                        document(TermType.THIRD_PARTY_PROVISION_CONSENT)));
        readiness.checkStage(TermStage.TIMELINE_FIRST_CREATE, NOW_KST);
        assertThat(logAppender.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(2);
    }

    @Test
    void recordFailOpen_incrementsPerStageCounter() {
        readiness.recordFailOpen(TermStage.LOGIN);
        readiness.recordFailOpen(TermStage.LOGIN);

        assertThat(meterRegistry.get(TermCatalogReadiness.GATE_FAIL_OPEN_COUNTER)
                .tag("stage", TermStage.LOGIN.name()).counter().count()).isEqualTo(2.0);
    }

    @Test
    void startupCheck_reportsMissingSeedAndUnknownLiteral() {
        // raw row 검사라 미지 literal도 예외 없이 관측된다.
        when(termDocumentRepository.findCatalogRows()).thenReturn(List.of(
                catalogRow("TERMS_OF_SERVICE"),
                catalogRow("BOGUS_TYPE"))); // 미지 literal
        when(termDocumentService.findCurrentSummaries(anyCollection(), any())).thenReturn(List.of());
        when(termDocumentRepository.count()).thenReturn(2L); // 행이 존재하는 seed 실수 — ERROR 경로

        readiness.verifyCatalogOnStartup();

        String problems = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", String::concat);
        assertThat(problems)
                .contains("missing seed for termType=SENSITIVE_INFORMATION_CONSENT")
                .contains("unknown termType literal in term_documents: BOGUS_TYPE")
                .contains("stage not ready")
                .doesNotContain("mapping mismatch");
    }

    @Test
    void startupCheck_reportsMalformedContentUrl() {
        // 게시 URL은 운영 seed가 손으로 넣는 값이라 형식 오류가 조용히 통과하면 안 된다. host는 보지
        // 않는다 — 게시 위치는 정책이 아니라 운영 선택이다.
        when(termDocumentRepository.findCatalogRows()).thenReturn(List.of(
                catalogRow("TERMS_OF_SERVICE", "http://laimory.app/terms/terms-of-service/1.0"), // https 아님
                catalogRow("SENSITIVE_INFORMATION_CONSENT", " "),                                // blank
                catalogRow("THIRD_PARTY_PROVISION_CONSENT", "https://example.test/whatever"),    // 형식 OK
                catalogRow("CROSS_BORDER_TRANSFER_CONSENT", "https://laimory.app/terms/x/1.0")));
        when(termDocumentService.findCurrentSummaries(anyCollection(), any())).thenReturn(List.of());
        when(termDocumentRepository.count()).thenReturn(4L);

        readiness.verifyCatalogOnStartup();

        String problems = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", String::concat);
        assertThat(problems)
                .contains("invalid contentUrl for termType=TERMS_OF_SERVICE")
                .contains("invalid contentUrl for termType=SENSITIVE_INFORMATION_CONSENT")
                // host는 검사하지 않는다 — 형식만 맞으면 통과한다.
                .doesNotContain("invalid contentUrl for termType=THIRD_PARTY_PROVISION_CONSENT")
                .doesNotContain("invalid contentUrl for termType=CROSS_BORDER_TRANSFER_CONSENT");
    }

    @Test
    void startupCheck_emptyCatalog_logsWarnNotError() {
        // seed 전 반복 기동이 ERROR 경보(Discord)를 만들지 않는다 — WARN으로만 pre-activation을 알린다.
        when(termDocumentRepository.findCatalogRows()).thenReturn(List.of());
        when(termDocumentService.findCurrentSummaries(anyCollection(), any())).thenReturn(List.of());
        when(termDocumentRepository.count()).thenReturn(0L);

        readiness.verifyCatalogOnStartup();

        assertThat(logAppender.list.stream().filter(event -> event.getLevel() == Level.ERROR)).isEmpty();
        assertThat(logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage))
                .anyMatch(message -> message.contains("not seeded yet"));
        assertThat(readyGauge(TermStage.LOGIN)).isEqualTo(0.0);
        assertThat(readyGauge(TermStage.TIMELINE_FIRST_CREATE)).isEqualTo(0.0);
    }

    @Test
    void startupCheck_fullySeededCatalog_logsNoError() {
        when(termDocumentRepository.findCatalogRows()).thenReturn(List.of(
                catalogRow("TERMS_OF_SERVICE"),
                catalogRow("SENSITIVE_INFORMATION_CONSENT"),
                catalogRow("THIRD_PARTY_PROVISION_CONSENT"),
                catalogRow("CROSS_BORDER_TRANSFER_CONSENT"),
                catalogRow("LOCATION_BASED_SERVICE_TERMS"),
                catalogRow("PRIVACY_POLICY")));
        when(termDocumentService.findCurrentSummaries(eqEnforcedTypes(TermStage.LOGIN), any()))
                .thenReturn(List.of(document(TermType.TERMS_OF_SERVICE)));
        when(termDocumentService.findCurrentSummaries(eqEnforcedTypes(TermStage.TIMELINE_FIRST_CREATE), any()))
                .thenReturn(List.of(document(TermType.SENSITIVE_INFORMATION_CONSENT),
                        document(TermType.THIRD_PARTY_PROVISION_CONSENT),
                        document(TermType.CROSS_BORDER_TRANSFER_CONSENT)));
        when(termDocumentService.findCurrentSummaries(
                org.mockito.ArgumentMatchers.eq(List.of(TermType.LOCATION_BASED_SERVICE_TERMS)), any()))
                .thenReturn(List.of(document(TermType.LOCATION_BASED_SERVICE_TERMS)));

        readiness.verifyCatalogOnStartup();

        assertThat(logAppender.list.stream().filter(event -> event.getLevel() == Level.ERROR)).isEmpty();
        assertThat(readyGauge(TermStage.LOGIN)).isEqualTo(1.0);
        assertThat(readyGauge(TermStage.TIMELINE_FIRST_CREATE)).isEqualTo(1.0);
    }

    private static List<TermType> eqEnforcedTypes(TermStage stage) {
        return eq(switch (stage) {
            case LOGIN -> List.of(TermType.TERMS_OF_SERVICE);
            case TIMELINE_FIRST_CREATE -> List.of(
                    TermType.SENSITIVE_INFORMATION_CONSENT,
                    TermType.THIRD_PARTY_PROVISION_CONSENT,
                    TermType.CROSS_BORDER_TRANSFER_CONSENT);
        });
    }

    private double readyGauge(TermStage stage) {
        return meterRegistry.get(TermCatalogReadiness.CATALOG_READY_GAUGE)
                .tag("stage", stage.name()).gauge().value();
    }

    private double conditionalReadyGauge(TermType termType) {
        return meterRegistry.get(TermCatalogReadiness.CONDITIONAL_CATALOG_READY_GAUGE)
                .tag("term_type", termType.name()).gauge().value();
    }

    private static TermDocumentSummary document(TermType type) {
        return new TermDocumentSummary((long) type.displayOrder(), type, "1.0");
    }

    private static TermDocumentRepository.TermCatalogRow catalogRow(String termType) {
        return catalogRow(termType, "https://laimory.app/terms/page/1.0");
    }

    private static TermDocumentRepository.TermCatalogRow catalogRow(String termType, String contentUrl) {
        return new TermDocumentRepository.TermCatalogRow() {
            @Override
            public String getTermType() {
                return termType;
            }

            @Override
            public String getContentUrl() {
                return contentUrl;
            }
        };
    }
}
