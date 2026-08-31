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
        when(termDocumentService.findCurrentSummaries(anyCollection(), any())).thenReturn(List.of());

        TermCatalogReadiness.StageCatalog catalog = readiness.checkStage(TermStage.LOGIN, NOW_KST);

        assertThat(catalog.ready()).isFalse();
        assertThat(readyGauge(TermStage.LOGIN)).isEqualTo(0.0);
    }

    @Test
    void emptyCatalogTransition_logsWarnOnce_notError() {
        when(termDocumentService.findCurrentSummaries(anyCollection(), any())).thenReturn(List.of());
        when(termDocumentRepository.count()).thenReturn(0L);

        readiness.checkStage(TermStage.LOGIN, NOW_KST);
        readiness.checkStage(TermStage.LOGIN, NOW_KST);

        assertThat(logAppender.list.stream().filter(event -> event.getLevel() == Level.ERROR)).isEmpty();
        assertThat(logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("not seeded")))
                .hasSize(1);
        assertThat(readyGauge(TermStage.LOGIN)).isEqualTo(0.0);
    }

    @Test
    void emptyCurrentWithSeededRows_logsErrorOnTransition() {
        when(termDocumentService.findCurrentSummaries(anyCollection(), any())).thenReturn(List.of());
        when(termDocumentRepository.count()).thenReturn(4L);

        readiness.checkStage(TermStage.LOGIN, NOW_KST);

        assertThat(logAppender.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(1);
    }

    @Test
    void seededButBrokenTransition_logsErrorOnceUntilRecovery() {
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(List.of(document(TermType.SENSITIVE_INFORMATION_CONSENT),
                        document(TermType.THIRD_PARTY_PROVISION_CONSENT)));

        readiness.checkStage(TermStage.TIMELINE_FIRST_CREATE, NOW_KST);
        readiness.checkStage(TermStage.TIMELINE_FIRST_CREATE, NOW_KST);
        long errorCount = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .count();
        assertThat(errorCount).isEqualTo(1);

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
        when(termDocumentRepository.findCatalogRows()).thenReturn(List.of(
                catalogRow("TERMS_OF_SERVICE"),
                catalogRow("BOGUS_TYPE")));
        when(termDocumentService.findCurrentSummaries(anyCollection(), any())).thenReturn(List.of());
        when(termDocumentRepository.count()).thenReturn(2L);

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
        when(termDocumentRepository.findCatalogRows()).thenReturn(List.of(
                catalogRow("TERMS_OF_SERVICE", "http://laimory.app/terms/terms-of-service/1.0"),
                catalogRow("SENSITIVE_INFORMATION_CONSENT", " "),
                catalogRow("THIRD_PARTY_PROVISION_CONSENT", "https://example.test/whatever"),
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
                .doesNotContain("invalid contentUrl for termType=THIRD_PARTY_PROVISION_CONSENT")
                .doesNotContain("invalid contentUrl for termType=CROSS_BORDER_TRANSFER_CONSENT");
    }

    @Test
    void startupCheck_emptyCatalog_logsWarnNotError() {
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
        return new TermDocumentSummary((long) type.ordinal() + 1L, type, "1.0");
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
