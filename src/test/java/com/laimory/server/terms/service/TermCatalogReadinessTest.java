package com.laimory.server.terms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.laimory.server.terms.TermStage;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.entity.TermDocument;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * catalog 준비 판정(필수 종류 current 커버리지 + denormalized mapping 일치)과 기동 정합성 검사,
 * bounded 전이 로그·metric 계약 검증.
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
    void stageWithAllRequiredCurrentAndConsistentMapping_isReady() {
        when(termDocumentService.findCurrentDocuments(anyCollection(), any()))
                .thenReturn(List.of(document(TermType.TERMS_OF_SERVICE), document(TermType.PRIVACY_POLICY)));

        TermCatalogReadiness.StageCatalog catalog = readiness.checkStage(TermStage.LOGIN, NOW_KST);

        assertThat(catalog.ready()).isTrue();
        assertThat(catalog.currentRequiredDocuments()).hasSize(2);
        assertThat(readyGauge(TermStage.LOGIN)).isEqualTo(1.0);
    }

    @Test
    void missingRequiredCurrentDocument_marksStageNotReady() {
        // PRIVACY_POLICY의 current 문서 없음(활성화 전) — 부분 강제 없이 stage 전체 미준비.
        when(termDocumentService.findCurrentDocuments(anyCollection(), any()))
                .thenReturn(List.of(document(TermType.TERMS_OF_SERVICE)));

        TermCatalogReadiness.StageCatalog catalog = readiness.checkStage(TermStage.LOGIN, NOW_KST);

        assertThat(catalog.ready()).isFalse();
        assertThat(readyGauge(TermStage.LOGIN)).isEqualTo(0.0);
    }

    @Test
    void denormalizedMappingMismatch_marksStageNotReady() {
        // stage 사본이 enum 기대와 어긋난 seed — 정상 seed로 취급하지 않는다.
        TermDocument wrongStage = document(TermType.TERMS_OF_SERVICE);
        ReflectionTestUtils.setField(wrongStage, "stage", TermStage.TIMELINE_FIRST_CREATE.name());
        when(termDocumentService.findCurrentDocuments(anyCollection(), any()))
                .thenReturn(List.of(wrongStage, document(TermType.PRIVACY_POLICY)));

        assertThat(readiness.checkStage(TermStage.LOGIN, NOW_KST).ready()).isFalse();
    }

    @Test
    void requiredFlagMismatch_marksStageNotReady() {
        TermDocument wrongRequired = document(TermType.PRIVACY_POLICY);
        ReflectionTestUtils.setField(wrongRequired, "required", Boolean.FALSE);
        when(termDocumentService.findCurrentDocuments(anyCollection(), any()))
                .thenReturn(List.of(document(TermType.TERMS_OF_SERVICE), wrongRequired));

        assertThat(readiness.checkStage(TermStage.LOGIN, NOW_KST).ready()).isFalse();
    }

    @Test
    void notReadyTransition_logsErrorOnceUntilRecovery() {
        when(termDocumentService.findCurrentDocuments(anyCollection(), any()))
                .thenReturn(List.of());

        readiness.checkStage(TermStage.LOGIN, NOW_KST);
        readiness.checkStage(TermStage.LOGIN, NOW_KST);
        long errorCount = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .count();
        assertThat(errorCount).isEqualTo(1); // bounded — 지속 미준비 중 반복 ERROR 없음

        // 회복 → INFO 1줄, 이후 다시 미준비면 새 ERROR 1줄.
        when(termDocumentService.findCurrentDocuments(anyCollection(), any()))
                .thenReturn(List.of(document(TermType.TERMS_OF_SERVICE), document(TermType.PRIVACY_POLICY)));
        readiness.checkStage(TermStage.LOGIN, NOW_KST);
        when(termDocumentService.findCurrentDocuments(anyCollection(), any())).thenReturn(List.of());
        readiness.checkStage(TermStage.LOGIN, NOW_KST);
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
    void startupCheck_reportsMissingSeedUnknownLiteralAndMappingMismatch() {
        // raw row 검사라 미지 literal도 예외 없이 관측된다.
        when(termDocumentRepository.findCatalogRows()).thenReturn(List.of(
                catalogRow("TERMS_OF_SERVICE", "LOGIN", true),
                catalogRow("PRIVACY_POLICY", "TIMELINE_FIRST_CREATE", true), // stage 사본 불일치
                catalogRow("BOGUS_TYPE", "LOGIN", true)));                   // 미지 literal
        when(termDocumentService.findCurrentDocuments(anyCollection(), any())).thenReturn(List.of());

        readiness.verifyCatalogOnStartup();

        String problems = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", String::concat);
        assertThat(problems)
                .contains("missing seed for termType=SENSITIVE_INFORMATION_CONSENT")
                .contains("mapping mismatch for termType=PRIVACY_POLICY")
                .contains("unknown termType literal in term_documents: BOGUS_TYPE")
                .contains("stage not ready");
    }

    @Test
    void startupCheck_fullyConsistentCatalog_logsNoError() {
        when(termDocumentRepository.findCatalogRows()).thenReturn(List.of(
                catalogRow("TERMS_OF_SERVICE", "LOGIN", true),
                catalogRow("PRIVACY_POLICY", "LOGIN", true),
                catalogRow("SENSITIVE_INFORMATION_CONSENT", "TIMELINE_FIRST_CREATE", true),
                catalogRow("THIRD_PARTY_PROVISION_CONSENT", "TIMELINE_FIRST_CREATE", true),
                catalogRow("CROSS_BORDER_TRANSFER_CONSENT", "TIMELINE_FIRST_CREATE", true)));
        when(termDocumentService.findCurrentDocuments(eqTypes(TermStage.LOGIN), any()))
                .thenReturn(List.of(document(TermType.TERMS_OF_SERVICE), document(TermType.PRIVACY_POLICY)));
        when(termDocumentService.findCurrentDocuments(eqTypes(TermStage.TIMELINE_FIRST_CREATE), any()))
                .thenReturn(List.of(document(TermType.SENSITIVE_INFORMATION_CONSENT),
                        document(TermType.THIRD_PARTY_PROVISION_CONSENT),
                        document(TermType.CROSS_BORDER_TRANSFER_CONSENT)));

        readiness.verifyCatalogOnStartup();

        assertThat(logAppender.list.stream().filter(event -> event.getLevel() == Level.ERROR)).isEmpty();
        assertThat(readyGauge(TermStage.LOGIN)).isEqualTo(1.0);
        assertThat(readyGauge(TermStage.TIMELINE_FIRST_CREATE)).isEqualTo(1.0);
    }

    private static List<TermType> eqTypes(TermStage stage) {
        return org.mockito.ArgumentMatchers.eq(TermType.typesOf(stage));
    }

    private double readyGauge(TermStage stage) {
        return meterRegistry.get(TermCatalogReadiness.CATALOG_READY_GAUGE)
                .tag("stage", stage.name()).gauge().value();
    }

    private static TermDocument document(TermType type) {
        return TermDocument.of(type, "2026-08-15", "제목", "fixture-content",
                LocalDateTime.parse("2026-08-01T00:00:00"));
    }

    private static TermDocumentRepository.TermCatalogRow catalogRow(String termType, String stage,
                                                                    Boolean required) {
        return new TermDocumentRepository.TermCatalogRow() {
            @Override
            public String getTermType() {
                return termType;
            }

            @Override
            public String getStage() {
                return stage;
            }

            @Override
            public Boolean getRequired() {
                return required;
            }
        };
    }
}
