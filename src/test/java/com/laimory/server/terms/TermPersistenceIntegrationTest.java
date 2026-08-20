package com.laimory.server.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.terms.entity.TermAgreement;
import com.laimory.server.terms.entity.TermDocument;
import com.laimory.server.terms.repository.TermAgreementRepository;
import com.laimory.server.terms.repository.TermDocumentRepository;
import com.laimory.server.terms.service.TermAgreementTransactionService;
import com.laimory.server.terms.service.TermCatalogReadiness;
import com.laimory.server.terms.service.TermDocumentService;
import com.laimory.server.terms.service.TermDocumentSummary;
import com.laimory.server.terms.service.TermsEnforcementService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * term_documents/term_agreements ↔ MySQL 실 왕복 검증(#303).
 * - ddl-auto=validate이므로 컨텍스트 기동 자체가 VARCHAR/DATETIME(6) ↔ 엔티티 매핑 정합을 검증한다.
 * - unique 제약(버전 식별·동시 최신 모호성 차단·동의 1행)과 current selection 결정성, INSERT IGNORE의
 *   멱등 수렴(수락 시각 불변·동시 동일 batch)은 실 DB에서만 성립하므로 여기서 검증한다.
 * - version 컬럼의 binary collation이 Java equals(대소문자 구분)와 같은 비교 의미인지 고정한다.
 *
 * 실행: docker compose up -d --wait 후 ./gradlew integrationTest
 * (schema.sql은 빈 데이터 볼륨 첫 기동에만 적용 — 신규 테이블 DDL 반영에 fresh 볼륨 또는 수동 DDL 필요)
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TermPersistenceIntegrationTest {

    private static final AtomicLong USER_SEQ = new AtomicLong(930_300_000L);

    @Autowired
    private TermDocumentRepository termDocumentRepository;

    @Autowired
    private TermAgreementRepository termAgreementRepository;

    @Autowired
    private TermAgreementTransactionService termAgreementTransactionService;

    @Autowired
    private TermDocumentService termDocumentService;

    @Autowired
    private TermCatalogReadiness termCatalogReadiness;

    @Autowired
    private TermsEnforcementService termsEnforcementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdDocumentIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // raw SQL로 주입한 오타 seed(엔티티 경로로는 만들 수 없는 행) 정리.
        jdbcTemplate.update("DELETE FROM term_documents WHERE version LIKE 'it-lc-%'");
        // FK RESTRICT 순서: 동의 이력 → 문서.
        for (Long userId : createdUserIds) {
            termAgreementRepository.deleteAll(termAgreementRepository.findAll().stream()
                    .filter(agreement -> agreement.getUserId().equals(userId))
                    .toList());
        }
        termDocumentRepository.deleteAllById(createdDocumentIds);
        createdDocumentIds.clear();
        createdUserIds.clear();
    }

    @Test
    void schema_hasNoContentOrDenormalizedColumns_andNoStageIndex() {
        // #320 최종 shape 고정 — 원문·enum 사본 컬럼과 stage 조회 index가 되살아나면 여기서 깨진다.
        assertThat(columnNames()).containsExactlyInAnyOrder("term_document_id", "term_type", "version",
                "title", "effective_at", "created_at", "updated_at", "modified_by");
        assertThat(indexNames()).containsExactlyInAnyOrder("PRIMARY",
                "uq_term_documents_type_version", "uq_term_documents_type_effective");
    }

    @Test
    void allFiveTypes_insertAtVersion1_0_withFinalColumnSet() {
        // 활성화 시 넣을 seed와 같은 shape — 제거된 네 컬럼 없이 5종이 저장·조회된다.
        for (TermType type : TermType.values()) {
            saveDocument(type, "1.0", "2026-09-0" + type.displayOrder() + "T00:00:00");
        }

        assertThat(termDocumentRepository.findCurrentDocuments(List.of(TermType.values()),
                LocalDateTime.parse("2026-12-01T00:00:00")))
                .extracting(TermDocument::getTermType)
                .containsExactlyInAnyOrder(TermType.values());
    }

    @Test
    void uniqueConstraints_rejectDuplicateVersionAndDuplicateEffectiveAt() {
        saveDocument(TermType.TERMS_OF_SERVICE, "it-1.0", "2026-01-01T00:00:00");

        // 같은 (termType, version) — 효력일이 달라도 거절(버전 문자열은 종류 안에서 유일).
        assertThatThrownBy(() -> saveDocument(TermType.TERMS_OF_SERVICE, "it-1.0", "2026-02-01T00:00:00"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 같은 (termType, effectiveAt) — 동시 최신 모호성을 DB가 차단.
        assertThatThrownBy(() -> saveDocument(TermType.TERMS_OF_SERVICE, "it-1.1", "2026-01-01T00:00:00"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 다른 종류는 같은 버전 문자열·효력일을 쓸 수 있다.
        saveDocument(TermType.PRIVACY_POLICY, "it-1.0", "2026-01-01T00:00:00");
    }

    @Test
    void currentSelection_picksLatestEffectivePerType_excludingFutureVersions() {
        saveDocument(TermType.TERMS_OF_SERVICE, "it-old", "2026-01-01T00:00:00");
        TermDocument current = saveDocument(TermType.TERMS_OF_SERVICE, "it-current", "2026-03-01T00:00:00");
        saveDocument(TermType.TERMS_OF_SERVICE, "it-future", "2027-01-01T00:00:00");

        List<TermDocument> result = termDocumentRepository.findCurrentDocuments(
                List.of(TermType.TERMS_OF_SERVICE), LocalDateTime.parse("2026-08-16T00:00:00"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTermDocumentId()).isEqualTo(current.getTermDocumentId());
        assertThat(result.get(0).getVersion()).isEqualTo("it-current");
    }

    @Test
    void insertIfAbsent_isIdempotent_andPreservesFirstAcceptedAt() {
        TermDocument document = saveDocument(TermType.PRIVACY_POLICY, "it-2.0", "2026-01-02T00:00:00");
        Long userId = newUserId();
        LocalDateTime firstAcceptedAt = LocalDateTime.parse("2026-08-16T09:00:00");
        LocalDateTime retryAcceptedAt = LocalDateTime.parse("2026-08-16T10:00:00");
        LocalDateTime auditNow = LocalDateTime.parse("2026-08-16T09:00:00");

        int first = termAgreementRepository.insertIfAbsent(
                userId, document.getTermDocumentId(), firstAcceptedAt, auditNow);
        int second = termAgreementRepository.insertIfAbsent(
                userId, document.getTermDocumentId(), retryAcceptedAt, auditNow);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero(); // unique 예외가 아니라 원자 no-op — rollback-only 오염 없음
        List<TermAgreement> rows = findAgreements(userId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAcceptedAt()).isEqualTo(firstAcceptedAt); // 최초 수락 시각 보존
    }

    @Test
    void concurrentSameBatch_convergesToSingleHistoryWithoutErrors() throws Exception {
        TermDocument terms = saveDocument(TermType.TERMS_OF_SERVICE, "it-3.0", "2026-01-03T00:00:00");
        TermDocument privacy = saveDocument(TermType.PRIVACY_POLICY, "it-3.0", "2026-01-03T00:00:00");
        Long userId = newUserId();
        List<Long> documentIds = List.of(terms.getTermDocumentId(), privacy.getTermDocumentId());
        LocalDateTime acceptedAtKst = LocalDateTime.parse("2026-08-16T09:30:00");

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Void> batch = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            termAgreementTransactionService.recordAgreements(userId, documentIds, acceptedAtKst);
            return null;
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> futures = executor.invokeAll(List.of(batch, batch));
            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS); // 예외 없이 완료 — 패자도 성공으로 수렴
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(findAgreements(userId)).hasSize(2); // 문서당 정확히 1행
    }

    @Test
    void documentFkRestrict_preventsDeletingAgreedDocument() {
        TermDocument document = saveDocument(TermType.SENSITIVE_INFORMATION_CONSENT, "it-4.0",
                "2026-01-04T00:00:00");
        Long userId = newUserId();
        termAgreementRepository.insertIfAbsent(userId, document.getTermDocumentId(),
                LocalDateTime.parse("2026-08-16T09:00:00"), LocalDateTime.parse("2026-08-16T09:00:00"));

        // 동의가 남아 있는 문서 행 삭제는 RESTRICT — 이력 재구성 권위가 소실되지 않는다.
        assertThatThrownBy(() -> {
            termDocumentRepository.deleteById(document.getTermDocumentId());
            termDocumentRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void versionColumn_isCaseSensitiveLikeJavaEquals() {
        // binary collation — "IT-5.0"과 "it-5.0"은 다른 버전이다(Java equals와 같은 비교 의미).
        saveDocument(TermType.THIRD_PARTY_PROVISION_CONSENT, "IT-5.0", "2026-01-05T00:00:00");
        saveDocument(TermType.THIRD_PARTY_PROVISION_CONSENT, "it-5.0", "2026-01-06T00:00:00");
    }

    @Test
    void lowercaseRawSeed_convergesToNotReadyFailOpen_insteadOf500() {
        // 소문자 오타 seed — term_type이 binary collation이 아니라면 IN(enum literal)에 case-insensitive
        // 매칭돼 @Enumerated hydration이 공개 조회·gate를 500으로 깨뜨렸을 상태를 raw SQL로 재현한다.
        jdbcTemplate.update("INSERT INTO term_documents"
                + " (term_type, version, title, effective_at, created_at, updated_at)"
                + " VALUES ('terms_of_service', 'it-lc-1', '이용약관',"
                + "  '2026-01-01 00:00:00', NOW(6), NOW(6))");
        // 나머지 LOGIN 필수 종류는 정상 seed — 오타 행 하나만으로 stage가 미준비여야 한다.
        saveDocument(TermType.PRIVACY_POLICY, "it-lc-ok", "2026-01-02T00:00:00");

        // 1) binary collation — 소문자 행은 enum literal 조회에 매칭되지 않아 hydration 예외가 없다.
        List<TermDocument> current = termDocumentService.findCurrentDocuments("v1", TermStage.LOGIN);
        assertThat(current)
                .extracting(TermDocument::getTermType)
                .containsExactly(TermType.PRIVACY_POLICY); // 공개 조회는 정상 문서만 — 500 아님
        List<TermDocumentSummary> summaries = termDocumentService.findCurrentSummaries(
                TermType.typesOf(TermStage.LOGIN), LocalDateTime.parse("2026-08-16T00:00:00"));
        assertThat(summaries)
                .extracting(TermDocumentSummary::termType)
                .containsExactly(TermType.PRIVACY_POLICY);

        // 2) readiness — TERMS_OF_SERVICE의 current 문서가 없으므로 stage는 not-ready로 수렴한다.
        assertThat(termCatalogReadiness.checkStage(TermStage.LOGIN).ready()).isFalse();

        // 3) gate — 미준비 stage는 fail-open이라 예외 없이 통과한다(5xx가 아니라 경보 metric).
        termsEnforcementService.requireAgreements(TermStage.LOGIN, newUserId());
    }

    private TermDocument saveDocument(TermType type, String version, String effectiveAt) {
        TermDocument document = termDocumentRepository.saveAndFlush(TermDocument.of(
                type, version, "통합 테스트 제목", LocalDateTime.parse(effectiveAt)));
        createdDocumentIds.add(document.getTermDocumentId());
        return document;
    }

    private List<String> columnNames() {
        return jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'term_documents'",
                String.class);
    }

    private List<String> indexNames() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'term_documents'",
                String.class);
    }

    private Long newUserId() {
        Long userId = USER_SEQ.incrementAndGet();
        createdUserIds.add(userId);
        return userId;
    }

    private List<TermAgreement> findAgreements(Long userId) {
        return termAgreementRepository.findAll().stream()
                .filter(agreement -> agreement.getUserId().equals(userId))
                .toList();
    }
}
