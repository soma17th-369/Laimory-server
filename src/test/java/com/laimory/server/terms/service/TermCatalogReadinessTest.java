package com.laimory.server.terms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.laimory.server.terms.TermType;
import com.laimory.server.terms.repository.TermDocumentRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

/** raw catalog 한 번으로 seed를 검사하고 기동을 막지 않는 로그 계약을 검증한다. */
@ExtendWith(MockitoExtension.class)
class TermCatalogReadinessTest {

    @Mock
    private TermDocumentRepository termDocumentRepository;

    private TermCatalogReadiness readiness;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        readiness = new TermCatalogReadiness(termDocumentRepository);
        logger = (Logger) LoggerFactory.getLogger(TermCatalogReadiness.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
        verify(termDocumentRepository).findCatalogRows();
        verifyNoMoreInteractions(termDocumentRepository);
    }

    @Test
    void emptyCatalog_logsOneWarningWithoutBlockingStartup() {
        when(termDocumentRepository.findCatalogRows()).thenReturn(List.of());

        assertThatCode(readiness::verifyCatalogOnStartup).doesNotThrowAnyException();

        assertSingleLog(Level.WARN, "not seeded yet");
    }

    @Test
    void fullySeededCatalog_logsSuccess() {
        when(termDocumentRepository.findCatalogRows()).thenReturn(allTypes());

        readiness.verifyCatalogOnStartup();

        assertSingleLog(Level.INFO, "all " + TermType.values().length + " term types seeded");
    }

    @ParameterizedTest
    @EnumSource(TermType.class)
    void missingType_includingLocationAndPrivacy_logsOneError(TermType missingType) {
        when(termDocumentRepository.findCatalogRows()).thenReturn(allTypes().stream()
                .filter(row -> !row.getTermType().equals(missingType.name()))
                .toList());

        assertThatCode(readiness::verifyCatalogOnStartup).doesNotThrowAnyException();

        assertSingleLog(Level.ERROR, "missing seed for termType=" + missingType.name());
    }

    @Test
    void unknownLiteral_isReportedWithoutEnumHydration() {
        List<TermDocumentRepository.TermCatalogRow> rows = new ArrayList<>(allTypes());
        rows.add(catalogRow("BOGUS_TYPE", "https://example.test/terms/1.0"));
        when(termDocumentRepository.findCatalogRows()).thenReturn(rows);

        assertThatCode(readiness::verifyCatalogOnStartup).doesNotThrowAnyException();

        assertSingleLog(Level.ERROR, "unknown termType literal in term_documents: BOGUS_TYPE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "http://example.test/terms", "/terms/1.0", "https://", "https://bad host/terms"})
    void malformedContentUrl_logsOneErrorWithoutBlockingStartup(String contentUrl) {
        when(termDocumentRepository.findCatalogRows()).thenReturn(allTypes().stream()
                .map(row -> row.getTermType().equals(TermType.TERMS_OF_SERVICE.name())
                        ? catalogRow(row.getTermType(), contentUrl) : row)
                .toList());

        assertThatCode(readiness::verifyCatalogOnStartup).doesNotThrowAnyException();

        assertSingleLog(Level.ERROR, "invalid contentUrl for termType=TERMS_OF_SERVICE");
    }

    @Test
    void multipleVersionsOfOneType_doNotReplaceMissingTypes() {
        when(termDocumentRepository.findCatalogRows()).thenReturn(List.of(
                catalogRow("TERMS_OF_SERVICE", "https://example.test/terms/1.0"),
                catalogRow("TERMS_OF_SERVICE", "https://example.test/terms/1.1")));

        readiness.verifyCatalogOnStartup();

        assertSingleLog(Level.ERROR, "missing seed for termType=PRIVACY_POLICY");
        assertThat(logAppender.list.getFirst().getFormattedMessage())
                .doesNotContain("missing seed for termType=TERMS_OF_SERVICE");
    }

    @Test
    void repositoryFailure_logsErrorWithoutBlockingStartup() {
        when(termDocumentRepository.findCatalogRows())
                .thenThrow(new DataAccessResourceFailureException("test database unavailable"));

        assertThatCode(readiness::verifyCatalogOnStartup).doesNotThrowAnyException();

        assertSingleLog(Level.ERROR, "term catalog startup verification failed");
        assertThat(logAppender.list.getFirst().getThrowableProxy()).isNotNull();
    }

    private void assertSingleLog(Level level, String message) {
        assertThat(logAppender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(level);
            assertThat(event.getFormattedMessage()).contains(message);
        });
    }

    private static List<TermDocumentRepository.TermCatalogRow> allTypes() {
        return Arrays.stream(TermType.values())
                .map(type -> catalogRow(type.name(), "https://example.test/terms/" + type.name() + "/1.0"))
                .toList();
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
