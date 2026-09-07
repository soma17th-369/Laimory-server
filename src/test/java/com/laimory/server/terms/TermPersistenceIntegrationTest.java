package com.laimory.server.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.terms.entity.TermAgreement;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.entity.TermDocumentId;
import com.laimory.server.terms.repository.TermAgreementRepository;
import com.laimory.server.terms.repository.TermDocumentRepository;
import com.laimory.server.terms.service.TermAgreementService;
import com.laimory.server.terms.service.TermAgreementTransactionService;
import com.laimory.server.terms.service.TermDocumentService;
import com.laimory.server.terms.service.TermDocumentSummary;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** final composite-key schema와 semantic current/동의 이력의 MySQL 실 왕복을 검증한다. */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TermPersistenceIntegrationTest {

    private static final AtomicLong USER_SEQ = new AtomicLong(930_300_000L);
    private static final AtomicLong VERSION_SEQ = new AtomicLong(930_300_000L);

    @Autowired
    private TermDocumentRepository termDocumentRepository;
    @Autowired
    private TermAgreementRepository termAgreementRepository;
    @Autowired
    private TermAgreementTransactionService termAgreementTransactionService;
    @Autowired
    private TermDocumentService termDocumentService;
    @Autowired
    private TermAgreementService termAgreementService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<TermDocumentId> createdDocumentIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<String> rawLowercaseVersions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long userId : createdUserIds) {
            termAgreementRepository.deleteAllByUserId(userId);
        }
        termDocumentRepository.deleteAllById(createdDocumentIds);
        for (String version : rawLowercaseVersions) {
            jdbcTemplate.update("DELETE FROM term_documents WHERE term_type = 'terms_of_service' AND version = ?",
                    version);
        }
        createdDocumentIds.clear();
        createdUserIds.clear();
        rawLowercaseVersions.clear();
    }

    @Test
    void schema_matchesCompositeKeyForeignKeyAndCanonicalCheckContract() {
        assertThat(columnNames("term_documents")).containsExactlyInAnyOrder(
                "term_type", "version", "title", "content_url", "created_at", "updated_at", "modified_by");
        assertThat(indexNames("term_documents")).containsExactly("PRIMARY");

        assertThat(columnNames("term_agreements")).containsExactlyInAnyOrder(
                "user_id", "term_type", "version", "accepted_at", "created_at", "updated_at", "modified_by");
        assertThat(indexNames("term_agreements")).containsExactlyInAnyOrder(
                "PRIMARY", "idx_term_agreements_user_history", "idx_term_agreements_document");
        assertThat(foreignKeyColumns()).containsExactly("term_type->term_type", "version->version");
        assertThat(checkClause()).contains("regexp").contains("[1-9]");
    }

    @Test
    void allDeclaredTypes_insertAndSemanticCurrentReturnsEveryType() {
        String version = nextMajor() + ".0";
        for (TermType type : TermType.values()) {
            saveDocument(type, version);
        }

        assertThat(termDocumentService.findCurrentDocuments("v1", List.of(TermType.values())))
                .extracting(TermDocument::getTermType)
                .containsExactly(TermType.values());
    }

    @Test
    void compositePrimaryKey_rejectsDuplicatePair_butAllowsSameVersionForAnotherType() {
        String version = nextMajor() + ".0";
        saveDocument(TermType.TERMS_OF_SERVICE, version);

        assertThatThrownBy(() -> insertDocumentRaw("TERMS_OF_SERVICE", version))
                .isInstanceOf(DataIntegrityViolationException.class);
        saveDocument(TermType.SENSITIVE_INFORMATION_CONSENT, version);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "01.0", "1.01", "1.0.0", "1.10\n", "1.10\r", "1.10\r\n",
            "1.10\u0085", "1.10\u2028", "1.10\u2029", "1.10\f", "1.10\u000B"})
    @Transactional // CHECK가 퇴행해 INSERT가 성공하더라도 잘못된 fixture를 rollback한다.
    void canonicalVersionCheck_rejectsNonCanonicalDocuments(String version) {
        assertThat(TermVersion.isCanonical(version)).isFalse();
        assertThatThrownBy(() -> insertDocumentRaw("PRIVACY_POLICY", version))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(3819));
    }

    @Test
    void currentSelection_ordersMinorAndMajorNumerically_andNewHigherInsertWinsImmediately() {
        String major = nextMajor();
        saveDocument(TermType.TERMS_OF_SERVICE, major + ".9");
        saveDocument(TermType.TERMS_OF_SERVICE, major + ".10");

        assertThat(currentVersion(TermType.TERMS_OF_SERVICE)).isEqualTo(major + ".10");

        String nextMajor = Long.toString(Long.parseLong(major) + 1L);
        saveDocument(TermType.TERMS_OF_SERVICE, nextMajor + ".0");
        assertThat(currentVersion(TermType.TERMS_OF_SERVICE)).isEqualTo(nextMajor + ".0");
    }

    @Test
    void insertIfAbsent_isIdempotent_andPreservesFirstAcceptedAt() {
        TermDocument document = saveDocument(TermType.SENSITIVE_INFORMATION_CONSENT, nextMajor() + ".0");
        Long userId = newUserId();
        LocalDateTime firstAcceptedAt = LocalDateTime.parse("2026-08-16T09:00:00");
        LocalDateTime retryAcceptedAt = LocalDateTime.parse("2026-08-16T10:00:00");
        LocalDateTime auditNow = LocalDateTime.parse("2026-08-16T09:00:00");

        int first = insertIfAbsent(userId, document, firstAcceptedAt, auditNow);
        int second = insertIfAbsent(userId, document, retryAcceptedAt, auditNow);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(findAgreements(userId)).singleElement()
                .extracting(TermAgreement::getAcceptedAt).isEqualTo(firstAcceptedAt);
    }

    @Test
    void concurrentSameBatch_convergesToOneAgreementPerDocument() throws Exception {
        String version = nextMajor() + ".0";
        TermDocument terms = saveDocument(TermType.TERMS_OF_SERVICE, version);
        TermDocument sensitive = saveDocument(TermType.SENSITIVE_INFORMATION_CONSENT, version);
        Long userId = newUserId();
        List<TermDocumentId> documentIds = List.of(terms.getId(), sensitive.getId());
        LocalDateTime acceptedAt = LocalDateTime.parse("2026-08-16T09:30:00");

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Void> batch = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            termAgreementTransactionService.recordAgreements(userId, documentIds, acceptedAt);
            return null;
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> futures = executor.invokeAll(List.of(batch, batch));
            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(findAgreements(userId)).hasSize(2);
    }

    @Test
    void compositeForeignKey_rejectsMissingDocument_andRestrictsDeletingAgreedDocument() {
        Long userId = newUserId();
        String missingVersion = nextMajor() + ".0";
        assertThatThrownBy(() -> insertAgreementRaw(userId, "TERMS_OF_SERVICE", missingVersion,
                LocalDateTime.parse("2026-08-16T09:00:00")))
                .isInstanceOf(DataIntegrityViolationException.class);

        TermDocument document = saveDocument(TermType.SENSITIVE_INFORMATION_CONSENT, nextMajor() + ".0");
        insertIfAbsent(userId, document, LocalDateTime.parse("2026-08-16T09:00:00"),
                LocalDateTime.parse("2026-08-16T09:00:00"));
        assertThatThrownBy(() -> {
            termDocumentRepository.deleteById(document.getId());
            termDocumentRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void history_hasDeterministicCompositeKeyTieBreaker() {
        String major = nextMajor();
        TermDocument version110 = saveDocument(TermType.TERMS_OF_SERVICE, major + ".10");
        TermDocument version19 = saveDocument(TermType.TERMS_OF_SERVICE, major + ".9");
        TermDocument sensitive = saveDocument(TermType.SENSITIVE_INFORMATION_CONSENT, major + ".0");
        Long userId = newUserId();
        LocalDateTime acceptedAt = LocalDateTime.parse("2026-08-16T09:00:00");
        insertIfAbsent(userId, sensitive, acceptedAt, acceptedAt);
        insertIfAbsent(userId, version110, acceptedAt, acceptedAt);
        insertIfAbsent(userId, version19, acceptedAt, acceptedAt);

        assertThat(termAgreementRepository.findHistoryByUserId(userId))
                .extracting(entry -> entry.document().getTermType().name() + ":" + entry.document().getVersion())
                .containsExactly("TERMS_OF_SERVICE:" + major + ".9",
                        "TERMS_OF_SERVICE:" + major + ".10",
                        "SENSITIVE_INFORMATION_CONSENT:" + major + ".0");
    }

    @Test
    void agreementRequired_revisionCycle_tracksCurrentCompositeKey() {
        Long userId = newUserId();
        TermType type = TermType.CROSS_BORDER_TRANSFER_CONSENT;
        String major = nextMajor();
        TermDocument v1 = saveDocument(type, major + ".0");
        LocalDateTime now = LocalDateTime.parse("2026-08-16T09:00:00");
        insertIfAbsent(userId, v1, now, now);
        assertThat(agreementRequiredTypes(userId)).doesNotContain(type);

        TermDocument v11 = saveDocument(type, major + ".1");
        TermDocumentSummary required = termAgreementService.findAgreementRequiredTerms(userId).stream()
                .filter(document -> document.termType() == type)
                .findFirst()
                .orElseThrow();
        assertThat(required.version()).isEqualTo(v11.getVersion());

        insertIfAbsent(userId, v11, now, now);
        assertThat(agreementRequiredTypes(userId)).doesNotContain(type);
    }

    @Test
    void lowercaseRawTermType_doesNotHydrateAsEnumCandidate() {
        String version = nextMajor() + ".0";
        rawLowercaseVersions.add(version);
        insertDocumentRaw("terms_of_service", version);

        assertThat(termDocumentRepository.findDocumentCandidates(List.of(TermType.TERMS_OF_SERVICE)))
                .extracting(TermDocument::getVersion)
                .doesNotContain(version);
        assertThat(termDocumentRepository.findCatalogRows())
                .anyMatch(row -> row.getTermType().equals("terms_of_service") && row.getVersion().equals(version));
    }

    @Test
    void deleteAllByUserId_removesEveryAgreementForEmbeddedOwnerKey() {
        Long userId = newUserId();
        String version = nextMajor() + ".0";
        insertIfAbsent(userId, saveDocument(TermType.TERMS_OF_SERVICE, version),
                LocalDateTime.parse("2026-08-16T09:00:00"), LocalDateTime.parse("2026-08-16T09:00:00"));
        insertIfAbsent(userId, saveDocument(TermType.SENSITIVE_INFORMATION_CONSENT, version),
                LocalDateTime.parse("2026-08-16T09:00:00"), LocalDateTime.parse("2026-08-16T09:00:00"));

        termAgreementService.deleteAllByUserId(userId);

        assertThat(findAgreements(userId)).isEmpty();
    }

    private TermDocument saveDocument(TermType type, String version) {
        TermDocument document = termDocumentRepository.saveAndFlush(TermDocument.of(
                type, version, "통합 테스트 제목",
                "https://www.laimory.app/terms/" + type.name().toLowerCase().replace('_', '-') + "/" + version));
        createdDocumentIds.add(document.getId());
        return document;
    }

    private void insertDocumentRaw(String termType, String version) {
        jdbcTemplate.update("INSERT INTO term_documents"
                + " (term_type, version, title, content_url, created_at, updated_at)"
                + " VALUES (?, ?, '통합 테스트 제목', 'https://www.laimory.app/terms/integration', NOW(6), NOW(6))",
                termType, version);
    }

    private int insertIfAbsent(Long userId, TermDocument document, LocalDateTime acceptedAt,
                               LocalDateTime auditNow) {
        return termAgreementRepository.insertIfAbsent(userId, document.getTermType().name(),
                document.getVersion(), acceptedAt, auditNow);
    }

    private void insertAgreementRaw(Long userId, String termType, String version, LocalDateTime acceptedAt) {
        jdbcTemplate.update("INSERT INTO term_agreements"
                + " (user_id, term_type, version, accepted_at, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?)", userId, termType, version, acceptedAt, acceptedAt, acceptedAt);
    }

    private String currentVersion(TermType type) {
        return termDocumentService.findCurrentDocuments("v1", List.of(type)).getFirst().getVersion();
    }

    private List<String> columnNames(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
                String.class, tableName);
    }

    private List<String> indexNames(String tableName) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY INDEX_NAME",
                String.class, tableName);
    }

    private List<String> foreignKeyColumns() {
        return jdbcTemplate.queryForList(
                "SELECT CONCAT(COLUMN_NAME, '->', REFERENCED_COLUMN_NAME)"
                        + " FROM information_schema.KEY_COLUMN_USAGE"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'term_agreements'"
                        + " AND CONSTRAINT_NAME = 'fk_term_agreements_document' ORDER BY ORDINAL_POSITION",
                String.class);
    }

    private String checkClause() {
        return jdbcTemplate.queryForObject(
                "SELECT LOWER(CHECK_CLAUSE) FROM information_schema.CHECK_CONSTRAINTS"
                        + " WHERE CONSTRAINT_SCHEMA = DATABASE()"
                        + " AND CONSTRAINT_NAME = 'chk_term_documents_version_canonical'",
                String.class);
    }

    private Long newUserId() {
        Long userId = USER_SEQ.incrementAndGet();
        createdUserIds.add(userId);
        return userId;
    }

    private String nextMajor() {
        return Long.toString(VERSION_SEQ.incrementAndGet());
    }

    private List<TermAgreement> findAgreements(Long userId) {
        return termAgreementRepository.findAll().stream()
                .filter(agreement -> agreement.getUserId().equals(userId))
                .toList();
    }

    private List<TermType> agreementRequiredTypes(Long userId) {
        return termAgreementService.findAgreementRequiredTerms(userId).stream()
                .map(TermDocumentSummary::termType)
                .toList();
    }
}
