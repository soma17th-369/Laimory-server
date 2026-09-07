package com.laimory.server.terms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
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
        readiness = new TermCatalogReadiness(termDocumentRepository, termDocumentService, meterRegistry);
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
        when(termDocumentService.findCurrentSummaries(anyCollection()))
                .thenReturn(List.of(document(TermType.TERMS_OF_SERVICE)));

        TermCatalogReadiness.StageCatalog catalog = readiness.checkStage(TermStage.LOGIN);

        assertThat(catalog.ready()).isTrue();
        assertThat(catalog.currentEnforcedDocuments()).hasSize(1);
        assertThat(readyGauge(TermStage.LOGIN)).isEqualTo(1.0);
        // snapshot은 전 종류를 한 쿼리로 뜨고 stage 판정은 메모리 필터다(#428).
        verify(termDocumentService).findCurrentSummaries(List.of(TermType.values()));
    }

    @Test
    void timelineStageWithoutConditionalLocationDocument_keepsRequiredStageReady() {
        when(termDocumentService.findCurrentSummaries(anyCollection()))
                .thenReturn(List.of(document(TermType.SENSITIVE_INFORMATION_CONSENT),
                        document(TermType.THIRD_PARTY_PROVISION_CONSENT),
                        document(TermType.CROSS_BORDER_TRANSFER_CONSENT)));

        TermCatalogReadiness.StageCatalog catalog =
                readiness.checkStage(TermStage.TIMELINE_FIRST_CREATE);

        assertThat(catalog.ready()).isTrue();
        assertThat(catalog.currentEnforcedDocuments()).hasSize(3);
        assertThat(readyGauge(TermStage.TIMELINE_FIRST_CREATE)).isEqualTo(1.0);
    }

    @Test
    void conditionalDocumentReadiness_publishesSeparateGauge() {
        when(termDocumentService.findCurrentSummaries(anyCollection()))
                .thenReturn(List.of());
        when(termDocumentRepository.count()).thenReturn(4L);

        TermCatalogReadiness.ConditionalTermCatalog missing = readiness.checkConditionalTerm(
                TermType.LOCATION_BASED_SERVICE_TERMS);

        assertThat(missing.ready()).isFalse();
        assertThat(conditionalReadyGauge(TermType.LOCATION_BASED_SERVICE_TERMS)).isEqualTo(0.0);

        when(termDocumentService.findCurrentSummaries(anyCollection()))
                .thenReturn(List.of(document(TermType.LOCATION_BASED_SERVICE_TERMS)));
        TermCatalogReadiness.ConditionalTermCatalog recovered = readiness.checkConditionalTerm(
                TermType.LOCATION_BASED_SERVICE_TERMS);

        assertThat(recovered.ready()).isTrue();
        assertThat(recovered.currentDocument()).contains(document(TermType.LOCATION_BASED_SERVICE_TERMS));
        assertThat(conditionalReadyGauge(TermType.LOCATION_BASED_SERVICE_TERMS)).isEqualTo(1.0);
    }

    @Test
    void missingRequiredCurrentDocument_marksStageNotReady() {
        when(termDocumentService.findCurrentSummaries(anyCollection())).thenReturn(List.of());

        TermCatalogReadiness.StageCatalog catalog = readiness.checkStage(TermStage.LOGIN);

        assertThat(catalog.ready()).isFalse();
        assertThat(readyGauge(TermStage.LOGIN)).isEqualTo(0.0);
    }

    @Test
    void emptyCatalogTransition_logsWarnOnce_notError() {
        when(termDocumentService.findCurrentSummaries(anyCollection())).thenReturn(List.of());
        when(termDocumentRepository.count()).thenReturn(0L);

        readiness.checkStage(TermStage.LOGIN);
        readiness.checkStage(TermStage.LOGIN);

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
        when(termDocumentService.findCurrentSummaries(anyCollection())).thenReturn(List.of());
        when(termDocumentRepository.count()).thenReturn(4L);

        readiness.checkStage(TermStage.LOGIN);

        assertThat(logAppender.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(1);
    }

    @Test
    void seededButBrokenTransition_logsErrorOnceUntilRecovery() {
        when(termDocumentService.findCurrentSummaries(anyCollection()))
                .thenReturn(List.of(document(TermType.SENSITIVE_INFORMATION_CONSENT),
                        document(TermType.THIRD_PARTY_PROVISION_CONSENT)));

        readiness.checkStage(TermStage.TIMELINE_FIRST_CREATE);
        readiness.checkStage(TermStage.TIMELINE_FIRST_CREATE);
        long errorCount = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .count();
        assertThat(errorCount).isEqualTo(1);

        when(termDocumentService.findCurrentSummaries(anyCollection()))
                .thenReturn(List.of(document(TermType.SENSITIVE_INFORMATION_CONSENT),
                        document(TermType.THIRD_PARTY_PROVISION_CONSENT),
                        document(TermType.CROSS_BORDER_TRANSFER_CONSENT)));
        readiness.checkStage(TermStage.TIMELINE_FIRST_CREATE);
        when(termDocumentService.findCurrentSummaries(anyCollection()))
                .thenReturn(List.of(document(TermType.SENSITIVE_INFORMATION_CONSENT),
                        document(TermType.THIRD_PARTY_PROVISION_CONSENT)));
        readiness.checkStage(TermStage.TIMELINE_FIRST_CREATE);
        assertThat(logAppender.list.stream().filter(event -> event.getLevel() == Level.ERROR)).hasSize(2);
    }

    @Test
    void startupCheck_reportsMissingSeedAndUnknownLiteral() {
        when(termDocumentRepository.findCatalogRows()).thenReturn(List.of(
                catalogRow("TERMS_OF_SERVICE"),
                catalogRow("BOGUS_TYPE")));
        when(termDocumentService.findCurrentSummaries(anyCollection())).thenReturn(List.of());
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
                catalogRow("CROSS_BORDER_TRANSFER_CONSENT", "https://www.laimory.app/terms/x/1.0")));
        when(termDocumentService.findCurrentSummaries(anyCollection())).thenReturn(List.of());
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
    void startupCheck_reportsNonCanonicalVersion() {
        when(termDocumentRepository.findCatalogRows()).thenReturn(List.of(
                catalogRow("TERMS_OF_SERVICE", "1.01", "https://www.laimory.app/terms/page/1.01")));
        when(termDocumentService.findCurrentSummaries(anyCollection())).thenReturn(List.of());
        when(termDocumentRepository.count()).thenReturn(1L);

        readiness.verifyCatalogOnStartup();

        assertThat(logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage))
                .anyMatch(message -> message.contains("invalid version for termType=TERMS_OF_SERVICE"));
    }

    @Test
    void startupCheck_emptyCatalog_logsWarnNotError() {
        when(termDocumentRepository.findCatalogRows()).thenReturn(List.of());
        when(termDocumentService.findCurrentSummaries(anyCollection())).thenReturn(List.of());
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
        when(termDocumentService.findCurrentSummaries(anyCollection()))
                .thenReturn(List.of(document(TermType.TERMS_OF_SERVICE),
                        document(TermType.PRIVACY_POLICY),
                        document(TermType.SENSITIVE_INFORMATION_CONSENT),
                        document(TermType.THIRD_PARTY_PROVISION_CONSENT),
                        document(TermType.CROSS_BORDER_TRANSFER_CONSENT),
                        document(TermType.LOCATION_BASED_SERVICE_TERMS)));

        readiness.verifyCatalogOnStartup();

        assertThat(logAppender.list.stream().filter(event -> event.getLevel() == Level.ERROR)).isEmpty();
        assertThat(readyGauge(TermStage.LOGIN)).isEqualTo(1.0);
        assertThat(readyGauge(TermStage.TIMELINE_FIRST_CREATE)).isEqualTo(1.0);
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
        return new TermDocumentSummary(type, "1.0");
    }

    private static TermDocumentRepository.TermCatalogRow catalogRow(String termType) {
        return catalogRow(termType, "https://www.laimory.app/terms/page/1.0");
    }

    private static TermDocumentRepository.TermCatalogRow catalogRow(String termType, String contentUrl) {
        return catalogRow(termType, "1.0", contentUrl);
    }

    private static TermDocumentRepository.TermCatalogRow catalogRow(String termType, String version,
                                                                     String contentUrl) {
        return new TermDocumentRepository.TermCatalogRow() {
            @Override
            public String getTermType() {
                return termType;
            }

            @Override
            public String getVersion() {
                return version;
            }

            @Override
            public String getContentUrl() {
                return contentUrl;
            }
        };
    }
}
