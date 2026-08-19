package com.laimory.server.push.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.push.PushTimes;
import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.push.entity.ScheduledNotificationPreference;
import com.laimory.server.push.entity.ScheduledNotificationPreferenceId;
import com.laimory.server.push.service.ScheduledNotificationPreferenceService;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.testsupport.TestSubjects;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * 푸시 설정·스케줄 테이블의 실 MySQL 왕복 검증.
 *
 * <ul>
 *   <li>{@code ddl-auto=validate}이므로 컨텍스트 기동 자체가 엔티티↔DDL 정합을 검증한다(감사 컬럼 포함).</li>
 *   <li>{@code FOR UPDATE SKIP LOCKED} claim이 여러 worker에서 같은 subject/occurrence를 중복 선택하지
 *       않는지, 그리고 claim이 occurrence 날짜와 다음 예정 시각을 SQL 한 문장으로 정확히 전진시키는지
 *       실제 unique key·index 위에서 확인한다.</li>
 *   <li>FK RESTRICT 순서(종류별 → 마스터)도 실제 제약으로 확인한다.</li>
 * </ul>
 *
 * <p>동시성 검증이 필요해 클래스 수준 {@code @Transactional}을 쓰지 않는다 — 각 테스트가 자기 데이터를
 * 직접 정리한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest (스키마 변경 직후엔 볼륨 재생성 필요)
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class PushNotificationPreferencePersistenceIntegrationTest {

    private static final ScheduledNotificationType TYPE = ScheduledNotificationType.DAILY_REMINDER;
    private static final List<UUID> SUBJECTS = List.of(
            TestSubjects.id(92_001L), TestSubjects.id(92_002L), TestSubjects.id(92_003L),
            TestSubjects.id(92_004L), TestSubjects.id(92_005L), TestSubjects.id(92_006L));

    @Autowired
    private PushPreferenceRepository pushPreferenceRepository;

    @Autowired
    private ScheduledNotificationPreferenceRepository scheduledNotificationPreferenceRepository;

    @Autowired
    private ScheduledNotificationPreferenceService scheduledNotificationPreferenceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        for (UUID subjectId : SUBJECTS) {
            jdbcTemplate.update("DELETE FROM scheduled_notification_preferences WHERE subject_id = ?",
                    subjectId.toString());
            jdbcTemplate.update("DELETE FROM push_preferences WHERE subject_id = ?", subjectId.toString());
        }
    }

    private void givenSubject(UUID subjectId) {
        SubjectMappingFixtures.ensureExists(jdbcTemplate, subjectId);
        pushPreferenceRepository.insertIfAbsent(subjectId.toString(), true, LocalDateTime.now());
    }

    private void givenDueSchedule(UUID subjectId, LocalDateTime nextDueAt) {
        givenSubject(subjectId);
        scheduledNotificationPreferenceRepository.insertIfAbsent(subjectId.toString(), TYPE.name(), true,
                nextDueAt.toLocalTime(), nextDueAt, LocalDateTime.now());
    }

    /** 테스트 fixture의 "지금" — JVM 기본 timezone이 아니라 서비스와 같은 KST 벽시계를 쓴다. */
    private static LocalDateTime nowKst() {
        return PushTimes.kstWallClock(Instant.now()).withNano(0);
    }

    private ScheduledNotificationPreference reload(UUID subjectId) {
        return scheduledNotificationPreferenceRepository
                .findById(new ScheduledNotificationPreferenceId(subjectId, TYPE))
                .orElseThrow();
    }

    // --- 기본 행 생성 ---

    @Test
    void insertIfAbsent_isIdempotentAcrossBothTables() {
        UUID subjectId = SUBJECTS.get(0);
        SubjectMappingFixtures.ensureExists(jdbcTemplate, subjectId);
        LocalDateTime now = LocalDateTime.of(2026, 7, 21, 12, 0);

        assertThat(pushPreferenceRepository.insertIfAbsent(subjectId.toString(), true, now)).isEqualTo(1);
        assertThat(pushPreferenceRepository.insertIfAbsent(subjectId.toString(), false, now)).isZero();
        assertThat(scheduledNotificationPreferenceRepository.insertIfAbsent(subjectId.toString(), TYPE.name(),
                false, LocalTime.of(21, 0), LocalDateTime.of(2026, 7, 21, 21, 0), now)).isEqualTo(1);
        assertThat(scheduledNotificationPreferenceRepository.insertIfAbsent(subjectId.toString(), TYPE.name(),
                true, LocalTime.of(9, 0), LocalDateTime.of(2026, 7, 21, 9, 0), now)).isZero();

        // 재실행이 기존 값을 덮지 않는다 — 두 단계 rollout backfill을 몇 번 돌려도 안전하다.
        assertThat(pushPreferenceRepository.findById(subjectId).orElseThrow().isPushEnabled()).isTrue();
        assertThat(reload(subjectId).isEnabled()).isFalse();
        assertThat(reload(subjectId).getNotificationTime()).isEqualTo(LocalTime.of(21, 0));
    }

    // --- claim 전진 규칙 ---

    @Test
    void claim_recordsProcessedOccurrenceDate_andMovesToNextFutureOccurrence() {
        UUID subjectId = SUBJECTS.get(1);
        // D일 21:00 예정을 D+1 03:00에 복구한 상황을 재현한다.
        givenDueSchedule(subjectId, LocalDateTime.of(2026, 7, 21, 21, 0));

        int advanced = scheduledNotificationPreferenceRepository.markProcessedAndAdvance(
                TYPE, List.of(subjectId), LocalDate.of(2026, 7, 21),
                LocalDateTime.of(2026, 7, 22, 21, 0));

        assertThat(advanced).isEqualTo(1);
        ScheduledNotificationPreference reloaded = reload(subjectId);
        // 기록되는 날짜는 claim 시각(D+1)이 아니라 처리한 occurrence(D)의 날짜다.
        assertThat(reloaded.getLastProcessedOccurrenceDate()).isEqualTo(LocalDate.of(2026, 7, 21));
        // 그래서 당일(D+1) 21:00 알림이 그대로 남는다.
        assertThat(reloaded.getNextDueAt()).isEqualTo(LocalDateTime.of(2026, 7, 22, 21, 0));
    }

    @Test
    void claim_whenTodaysTimeAlreadyPassed_movesToTomorrow() {
        UUID subjectId = SUBJECTS.get(2);
        givenDueSchedule(subjectId, LocalDateTime.of(2026, 7, 21, 21, 0));

        scheduledNotificationPreferenceRepository.markProcessedAndAdvance(
                TYPE, List.of(subjectId), LocalDate.of(2026, 7, 21),
                LocalDateTime.of(2026, 7, 22, 21, 0));

        // JVM timezone과 무관하게 저장한 값 그대로 돌아와야 한다(UTC CI가 이 회귀를 잡는다).
        assertThat(reload(subjectId).getNextDueAt()).isEqualTo(LocalDateTime.of(2026, 7, 22, 21, 0));
        assertThat(reload(subjectId).getLastProcessedOccurrenceDate()).isEqualTo(LocalDate.of(2026, 7, 21));
    }

    @Test
    void claim_afterLongOutage_jumpsStraightToFirstFutureOccurrence() {
        UUID subjectId = SUBJECTS.get(3);
        // 며칠 밀린 행도 한 문장으로 현재 이후 첫 occurrence에 도달한다(날짜별 반복 claim 없음).
        givenDueSchedule(subjectId, LocalDateTime.of(2026, 7, 18, 21, 0));

        scheduledNotificationPreferenceRepository.markProcessedAndAdvance(
                TYPE, List.of(subjectId), LocalDate.of(2026, 7, 18),
                LocalDateTime.of(2026, 7, 22, 21, 0));

        ScheduledNotificationPreference reloaded = reload(subjectId);
        assertThat(reloaded.getLastProcessedOccurrenceDate()).isEqualTo(LocalDate.of(2026, 7, 18));
        assertThat(reloaded.getNextDueAt()).isEqualTo(LocalDateTime.of(2026, 7, 22, 21, 0));
    }

    // --- 멀티 worker claim ---

    @Test
    void concurrentWorkers_claimEachSubjectExactlyOnce() throws Exception {
        LocalDateTime dueAt = nowKst().minusMinutes(1);
        for (UUID subjectId : SUBJECTS) {
            givenDueSchedule(subjectId, dueAt);
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<List<UUID>> worker = () -> {
                List<UUID> claimed = new ArrayList<>();
                for (int batch = 0; batch < SUBJECTS.size(); batch++) {
                    List<ScheduledNotificationPreference> due =
                            scheduledNotificationPreferenceService.claimDue(TYPE, 2);
                    if (due.isEmpty()) {
                        break;
                    }
                    due.forEach(preference -> claimed.add(preference.getSubjectId()));
                }
                return claimed;
            };
            Future<List<UUID>> first = executor.submit(worker);
            Future<List<UUID>> second = executor.submit(worker);

            List<UUID> allClaimed = new ArrayList<>(first.get(30, TimeUnit.SECONDS));
            allClaimed.addAll(second.get(30, TimeUnit.SECONDS));

            // 같은 subject/occurrence를 두 worker가 함께 잡지 않는다(중복 발송 방지의 권위).
            assertThat(allClaimed).doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(SUBJECTS);
        } finally {
            executor.shutdownNow();
        }

        // 모든 행이 다음 날로 전진해 같은 날짜에 다시 선택되지 않는다.
        for (UUID subjectId : SUBJECTS) {
            assertThat(reload(subjectId).getNextDueAt()).isAfter(nowKst());
        }
    }

    @Test
    void claimThroughService_writesKstOccurrenceValues_regardlessOfJvmTimezone() {
        // claim 경로 전체(조회 → 전진 계산 → UPDATE → 재조회)를 태운다. 전진 값을 SQL 안에서 파생하면
        // JDBC의 DATETIME 변환과 TIME 컬럼이 섞여 UTC JVM에서 날짜 +1일·시각 -9시간으로 어긋난다.
        UUID subjectId = SUBJECTS.get(2);
        LocalDateTime dueAt = nowKst().minusMinutes(1);
        givenDueSchedule(subjectId, dueAt);

        List<ScheduledNotificationPreference> claimed =
                scheduledNotificationPreferenceService.claimDue(TYPE, 100);

        assertThat(claimed).extracting(ScheduledNotificationPreference::getSubjectId).contains(subjectId);
        ScheduledNotificationPreference reloaded = reload(subjectId);
        // 처리한 occurrence의 KST 날짜와, 오늘 시각이 이미 지났으므로 다음 날 같은 시각.
        assertThat(reloaded.getLastProcessedOccurrenceDate()).isEqualTo(dueAt.toLocalDate());
        assertThat(reloaded.getNextDueAt()).isEqualTo(dueAt.plusDays(1));
    }

    @Test
    void claim_skipsDisabledAndFutureRows() {
        UUID enabled = SUBJECTS.get(4);
        UUID disabled = SUBJECTS.get(5);
        LocalDateTime dueAt = nowKst().minusMinutes(1);
        givenDueSchedule(enabled, dueAt);
        givenSubject(disabled);
        scheduledNotificationPreferenceRepository.insertIfAbsent(disabled.toString(), TYPE.name(), false,
                dueAt.toLocalTime(), dueAt, LocalDateTime.now());

        List<ScheduledNotificationPreference> claimed =
                scheduledNotificationPreferenceService.claimDue(TYPE, 100);

        assertThat(claimed).extracting(ScheduledNotificationPreference::getSubjectId)
                .contains(enabled)
                .doesNotContain(disabled);
    }

    // --- FK 계약 ---

    @Test
    void masterRowCannotBeDeletedWhileTypeRowExists() {
        UUID subjectId = SUBJECTS.get(0);
        givenDueSchedule(subjectId, LocalDateTime.of(2026, 7, 21, 21, 0));

        // RESTRICT — 종류별 정리를 빠뜨린 삭제가 조용히 통과하지 않는다.
        assertThat(catchDeleteFailure(subjectId)).isTrue();

        assertThat(scheduledNotificationPreferenceRepository.deleteAllBySubjectId(subjectId)).isEqualTo(1);
        assertThat(pushPreferenceRepository.deleteBySubjectId(subjectId)).isEqualTo(1);
    }

    private boolean catchDeleteFailure(UUID subjectId) {
        try {
            pushPreferenceRepository.deleteBySubjectId(subjectId);
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }
}
