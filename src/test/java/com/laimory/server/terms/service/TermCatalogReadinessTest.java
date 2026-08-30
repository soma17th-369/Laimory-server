package com.laimory.server.terms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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

/** 단일 enforcement catalog의 current 커버리지, 기동 정합성, bounded log와 metric을 검증한다. */
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
    void allFiveRequiredCurrentDocuments_isReady() {
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(enforcedDocuments());

        TermCatalogReadiness.Catalog catalog = readiness.check(NOW_KST);

        assertThat(catalog.ready()).isTrue();
        assertThat(catalog.currentEnforcedDocuments()).hasSize(5);
        assertThat(readyGauge()).isEqualTo(1.0);
        verify(termDocumentService).findCurrentSummaries(TermCatalogReadiness.ENFORCED_TYPES, NOW_KST);
    }

    @Test
    void privacyPolicy_isNotPartOfAgreementEnforcementCatalog() {
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(enforcedDocuments());

        readiness.check(NOW_KST);

        verify(termDocumentService).findCurrentSummaries(TermCatalogReadiness.ENFORCED_TYPES, NOW_KST);
        assertThat(TermCatalogReadiness.ENFORCED_TYPES).doesNotContain(TermType.PRIVACY_POLICY);
    }

    @Test
    void missingAnyRequiredCurrentDocument_marksCatalogNotReady() {
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(enforcedDocuments().subList(0, 4));

        TermCatalogReadiness.Catalog catalog = readiness.check(NOW_KST);

        assertThat(catalog.ready()).isFalse();
        assertThat(readyGauge()).isEqualTo(0.0);
    }

    @Test
    void emptyCatalogTransition_logsWarnOnce_notError() {
        when(termDocumentService.findCurrentSummaries(anyCollection(), any())).thenReturn(List.of());
        when(termDocumentRepository.count()).thenReturn(0L);

        readiness.check(NOW_KST);
        readiness.check(NOW_KST);

        assertThat(logs(Level.ERROR)).isEmpty();
        assertThat(logs(Level.WARN).stream().filter(message -> message.contains("not seeded"))).hasSize(1);
        assertThat(readyGauge()).isEqualTo(0.0);
    }

    @Test
    void seededButBroken_logsErrorOnceUntilRecovery() {
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(enforcedDocuments().subList(0, 4));

        readiness.check(NOW_KST);
        readiness.check(NOW_KST);
        assertThat(logs(Level.ERROR)).hasSize(1);

        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(enforcedDocuments());
        readiness.check(NOW_KST);
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(enforcedDocuments().subList(0, 4));
        readiness.check(NOW_KST);

        assertThat(logs(Level.ERROR)).hasSize(2);
        assertThat(logs(Level.INFO)).anyMatch(message -> message.contains("recovered"));
    }

    @Test
    void recordFailOpen_incrementsSingleCounter() {
        readiness.recordFailOpen();
        readiness.recordFailOpen();

        assertThat(meterRegistry.get(TermCatalogReadiness.GATE_FAIL_OPEN_COUNTER)
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    void startupCheck_reportsMissingSeedUnknownLiteralAndEnforcementReadiness() {
        when(termDocumentRepository.findCatalogRows()).thenReturn(List.of(
                catalogRow("TERMS_OF_SERVICE"),
                catalogRow("BOGUS_TYPE")));
        when(termDocumentService.findCurrentSummaries(anyCollection(), any())).thenReturn(List.of());
        when(termDocumentRepository.count()).thenReturn(2L);

        readiness.verifyCatalogOnStartup();

        String problems = String.join("", logs(Level.ERROR));
        assertThat(problems)
                .contains("missing seed for termType=SENSITIVE_INFORMATION_CONSENT")
                .contains("unknown termType literal in term_documents: BOGUS_TYPE")
                .contains("enforcement catalog not ready");
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

        String problems = String.join("", logs(Level.ERROR));
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

        assertThat(logs(Level.ERROR)).isEmpty();
        assertThat(logs(Level.WARN)).anyMatch(message -> message.contains("not seeded yet"));
        assertThat(readyGauge()).isEqualTo(0.0);
    }

    @Test
    void startupCheck_fullySeededAndCurrentCatalog_logsNoError() {
        when(termDocumentRepository.findCatalogRows()).thenReturn(List.of(
                catalogRow("TERMS_OF_SERVICE"),
                catalogRow("SENSITIVE_INFORMATION_CONSENT"),
                catalogRow("THIRD_PARTY_PROVISION_CONSENT"),
                catalogRow("CROSS_BORDER_TRANSFER_CONSENT"),
                catalogRow("LOCATION_BASED_SERVICE_TERMS"),
                catalogRow("PRIVACY_POLICY")));
        when(termDocumentService.findCurrentSummaries(anyCollection(), any()))
                .thenReturn(enforcedDocuments());

        readiness.verifyCatalogOnStartup();

        assertThat(logs(Level.ERROR)).isEmpty();
        assertThat(readyGauge()).isEqualTo(1.0);
    }

    private List<String> logs(Level level) {
        return logAppender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private double readyGauge() {
        return meterRegistry.get(TermCatalogReadiness.CATALOG_READY_GAUGE).gauge().value();
    }

    private static List<TermDocumentSummary> enforcedDocuments() {
        return List.of(
                document(11L, TermType.TERMS_OF_SERVICE),
                document(12L, TermType.SENSITIVE_INFORMATION_CONSENT),
                document(13L, TermType.THIRD_PARTY_PROVISION_CONSENT),
                document(14L, TermType.CROSS_BORDER_TRANSFER_CONSENT),
                document(15L, TermType.LOCATION_BASED_SERVICE_TERMS));
    }

    private static TermDocumentSummary document(long id, TermType type) {
        return new TermDocumentSummary(id, type, "1.0");
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
