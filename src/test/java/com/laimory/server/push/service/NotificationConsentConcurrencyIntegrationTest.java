package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.push.NotificationConsentAction;
import com.laimory.server.push.NotificationConsentProcessingResult;
import com.laimory.server.push.NotificationConsentSource;
import com.laimory.server.push.NotificationConsentType;
import com.laimory.server.push.entity.NotificationConsentEvent;
import com.laimory.server.push.repository.NotificationConsentEventRepository;
import com.laimory.server.push.repository.NotificationConsentRepository;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.testsupport.TestSubjects;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 동의 상태 전이의 실 MySQL 동시성 검증.
 *
 * <p>지키려는 성질: <b>처리 결과는 조건부 UPDATE가 실제로 바꾼 행 수에서 나온다.</b> 직전 상태를 미리
 * 읽어 판단하면 읽기와 쓰기 사이에 낀 동시 요청 때문에 실제로는 아무것도 바꾸지 않은 요청이
 * {@code APPLIED}로, 또는 상태를 되돌린 요청이 {@code ALREADY_IN_STATE}로 기록될 수 있다. 수신거부
 * 증적이 실제 상태와 어긋나는 방향이라 값 하나가 아니라 기록의 신뢰성이 걸린다.
 *
 * <p>동시성 검증이 필요해 클래스 수준 {@code @Transactional}을 쓰지 않는다 — 각 테스트가 직접 정리한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class NotificationConsentConcurrencyIntegrationTest {

    private static final UUID SUBJECT_ID = TestSubjects.id(93_001L);
    private static final String TERM_VERSION = "consent-concurrency-it";

    @Autowired
    private NotificationConsentService notificationConsentService;

    @Autowired
    private NotificationConsentRepository notificationConsentRepository;

    @Autowired
    private NotificationConsentEventRepository notificationConsentEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long termDocumentId;

    @BeforeEach
    void setUp() {
        cleanUp();
        SubjectMappingFixtures.ensureExists(jdbcTemplate, SUBJECT_ID);
        jdbcTemplate.update("INSERT INTO term_documents (term_type, stage, version, title, content, required, "
                        + "display_order, effective_at, created_at, updated_at) "
                        + "VALUES ('ADVERTISING_PUSH_CONSENT', 'PUSH_SETTINGS', ?, 'IT', 'IT', false, 6, "
                        + "'2020-01-01 00:00:00', now(6), now(6))", TERM_VERSION);
        termDocumentId = jdbcTemplate.queryForObject(
                "SELECT term_document_id FROM term_documents WHERE version = ?", Long.class, TERM_VERSION);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM notification_consent_events WHERE subject_id = ?", SUBJECT_ID.toString());
        jdbcTemplate.update("DELETE FROM notification_consents WHERE subject_id = ?", SUBJECT_ID.toString());
        jdbcTemplate.update("DELETE FROM term_documents WHERE version = ?", TERM_VERSION);
        SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, SUBJECT_ID);
        jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?", SUBJECT_ID.toString());
    }

    private void givenAdvertisingConsented() {
        notificationConsentRepository.insertIfAbsent(SUBJECT_ID.toString(), LocalDateTime.now());
        notificationConsentRepository.consentAdvertising(SUBJECT_ID, termDocumentId, LocalDateTime.now());
    }

    private List<NotificationConsentEvent> allEvents() {
        return notificationConsentEventRepository.findRecent(SUBJECT_ID, LocalDateTime.now().minusDays(1));
    }

    private boolean advertisingConsented() {
        return notificationConsentRepository.findById(SUBJECT_ID).orElseThrow().isAdvertisingPushConsented();
    }

    private <T> List<T> runConcurrently(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier startLine = new CyclicBarrier(2);
        try {
            Future<T> a = executor.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return first.call();
            });
            Future<T> b = executor.submit(() -> {
                startLine.await(10, TimeUnit.SECONDS);
                return second.call();
            });
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private Callable<List<NotificationConsentEvent>> withdraw() {
        return () -> notificationConsentService.apply(SUBJECT_ID,
                NotificationConsentType.ADVERTISING_PUSH, false, null,
                NotificationConsentSource.INSTALLATION_OPT_OUT);
    }

    private Callable<List<NotificationConsentEvent>> consent() {
        return () -> notificationConsentService.apply(SUBJECT_ID,
                NotificationConsentType.ADVERTISING_PUSH, true, TERM_VERSION,
                NotificationConsentSource.PUSH_SETTINGS);
    }

    @Test
    void concurrentWithdrawals_exactlyOneReportsApplied() throws Exception {
        // 실제 상태 전이는 한 번뿐이다. 판정을 미리 읽으면 둘 다 ON을 보고 둘 다 APPLIED를 기록할 수 있다.
        givenAdvertisingConsented();

        runConcurrently(withdraw(), withdraw());

        assertThat(advertisingConsented()).isFalse();
        assertThat(allEvents())
                .hasSize(2)
                .allSatisfy(event ->
                        assertThat(event.getAction()).isEqualTo(NotificationConsentAction.WITHDRAW))
                .filteredOn(event -> event.getProcessingResult() == NotificationConsentProcessingResult.APPLIED)
                .hasSize(1);
    }

    @Test
    void concurrentConsentAndWithdrawal_neverRecordsAlreadyInStateWhileFlippingTheOtherWay() throws Exception {
        // 순서는 경합이 정하지만, 각 event는 자기가 실제로 바꿨는지를 정직하게 기록해야 한다.
        // 최종 상태가 OFF인데 철회가 "이미 OFF였다"고 남으면 아무도 끄지 않은 게 되어 모순이다.
        runConcurrently(consent(), withdraw());

        boolean consented = advertisingConsented();
        List<NotificationConsentEvent> events = allEvents();
        assertThat(events).hasSize(2);

        NotificationConsentEvent withdrawal = events.stream()
                .filter(event -> event.getAction() == NotificationConsentAction.WITHDRAW)
                .findFirst()
                .orElseThrow();
        if (!consented) {
            assertThat(withdrawal.getProcessingResult())
                    .isEqualTo(NotificationConsentProcessingResult.APPLIED);
        }
    }

    @Test
    void sequentialWithdrawals_secondIsAlreadyInState() {
        givenAdvertisingConsented();

        List<NotificationConsentEvent> first = notificationConsentService.apply(SUBJECT_ID,
                NotificationConsentType.ADVERTISING_PUSH, false, null, NotificationConsentSource.PUSH_SETTINGS);
        List<NotificationConsentEvent> second = notificationConsentService.apply(SUBJECT_ID,
                NotificationConsentType.ADVERTISING_PUSH, false, null, NotificationConsentSource.PUSH_SETTINGS);

        assertThat(first).singleElement().satisfies(event -> assertThat(event.getProcessingResult())
                .isEqualTo(NotificationConsentProcessingResult.APPLIED));
        assertThat(second).singleElement().satisfies(event -> assertThat(event.getProcessingResult())
                .isEqualTo(NotificationConsentProcessingResult.ALREADY_IN_STATE));
        assertThat(advertisingConsented()).isFalse();
    }
}
