package com.laimory.server.push.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.laimory.server.push.PushTimes;
import com.laimory.server.push.entity.DailyNotificationPreference;
import com.laimory.server.push.service.DailyNotificationPreferenceService;
import com.laimory.server.push.service.PushSettingService;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.testsupport.TestSubjects;
import java.time.Instant;
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
 *       않는지, 그리고 claim이 다음 예정 시각을 SQL 한 문장으로 정확히 전진시키는지
 *       실제 unique key·index 위에서 확인한다.</li>
 *   <li>FK RESTRICT 순서(일일 알림 → 마스터)도 실제 제약으로 확인한다.</li>
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

    private static final List<UUID> SUBJECTS = List.of(
            TestSubjects.id(92_001L), TestSubjects.id(92_002L), TestSubjects.id(92_003L),
            TestSubjects.id(92_004L), TestSubjects.id(92_005L), TestSubjects.id(92_006L));

    @Autowired
    private SubjectPreferenceRepository subjectPreferenceRepository;

    @Autowired
    private DailyNotificationPreferenceRepository dailyNotificationPreferenceRepository;

    @Autowired
    private DailyNotificationPreferenceService dailyNotificationPreferenceService;

    @Autowired
    private PushSettingService pushSettingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        for (UUID subjectId : SUBJECTS) {
            jdbcTemplate.update("DELETE FROM daily_notification_preferences WHERE subject_id = ?",
                    subjectId.toString());
            jdbcTemplate.update("DELETE FROM subject_preferences WHERE subject_id = ?", subjectId.toString());
        }
    }

    private void givenSubject(UUID subjectId) {
        SubjectMappingFixtures.ensureExists(jdbcTemplate, subjectId);
        subjectPreferenceRepository.insertIfAbsent(subjectId.toString(), true, LocalDateTime.now());
    }

    private void givenDueSchedule(UUID subjectId, LocalDateTime nextDueAt) {
        givenSubject(subjectId);
        dailyNotificationPreferenceRepository.insertIfAbsent(
                subjectId.toString(), true, nextDueAt, LocalDateTime.now());
    }

    /** 테스트 fixture의 "지금" — JVM 기본 timezone이 아니라 서비스와 같은 KST 벽시계를 쓴다. */
    private static LocalDateTime nowKst() {
        return PushTimes.kstWallClock(Instant.now()).withNano(0);
    }

    private DailyNotificationPreference reload(UUID subjectId) {
        return dailyNotificationPreferenceRepository.findById(subjectId).orElseThrow();
    }

    // --- 기본 행 생성 ---

    @Test
    void insertIfAbsent_isIdempotentAcrossBothTables() {
        UUID subjectId = SUBJECTS.get(0);
        SubjectMappingFixtures.ensureExists(jdbcTemplate, subjectId);
        LocalDateTime now = LocalDateTime.of(2026, 7, 21, 12, 0);

        assertThat(subjectPreferenceRepository.insertIfAbsent(subjectId.toString(), true, now)).isEqualTo(1);
        assertThat(subjectPreferenceRepository.insertIfAbsent(subjectId.toString(), false, now)).isZero();
        assertThat(dailyNotificationPreferenceRepository.insertIfAbsent(subjectId.toString(),
                false, LocalDateTime.of(2026, 7, 21, 21, 0), now)).isEqualTo(1);
        assertThat(dailyNotificationPreferenceRepository.insertIfAbsent(subjectId.toString(),
                true, LocalDateTime.of(2026, 7, 21, 9, 0), now)).isZero();

        // 재실행이 기존 값을 덮지 않는다 — 두 단계 rollout backfill을 몇 번 돌려도 안전하다.
        assertThat(subjectPreferenceRepository.findById(subjectId).orElseThrow().isPushEnabled()).isTrue();
        assertThat(reload(subjectId).isEnabled()).isFalse();
        assertThat(reload(subjectId).getNextDueAt()).isEqualTo(LocalDateTime.of(2026, 7, 21, 21, 0));
    }

    // --- claim 전진 규칙 ---

    @Test
    void advance_writesGivenNextDueAtVerbatim() {
        UUID subjectId = SUBJECTS.get(1);
        // 며칠 밀린 행도 한 문장으로 현재 이후 첫 occurrence에 도달한다(날짜별 반복 claim 없음).
        givenDueSchedule(subjectId, LocalDateTime.of(2026, 7, 18, 21, 0));

        int advanced = dailyNotificationPreferenceRepository.advanceNextDueAt(
                List.of(subjectId), LocalDateTime.of(2026, 7, 22, 21, 0));

        assertThat(advanced).isEqualTo(1);
        // JVM timezone과 무관하게 저장한 값 그대로 돌아와야 한다(UTC CI가 이 회귀를 잡는다).
        assertThat(reload(subjectId).getNextDueAt()).isEqualTo(LocalDateTime.of(2026, 7, 22, 21, 0));
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
                    List<DailyNotificationPreference> due =
                            dailyNotificationPreferenceService.claimDue(2);
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
        // JDBC의 DATETIME 변환 때문에 UTC JVM에서 날짜가 어긋난다 — Java가 KST로 계산해 넘겨야 한다.
        UUID subjectId = SUBJECTS.get(2);
        givenDueSchedule(subjectId, nowKst().minusMinutes(1));

        List<DailyNotificationPreference> claimed = dailyNotificationPreferenceService.claimDue(100);

        assertThat(claimed).extracting(DailyNotificationPreference::getSubjectId).contains(subjectId);
        // 서버 고정 시각의 다음 미래 occurrence로 간다(행이 시각을 들고 있지 않다).
        LocalDateTime advanced = reload(subjectId).getNextDueAt();
        assertThat(advanced).isAfter(nowKst());
        assertThat(advanced.toLocalTime()).isEqualTo(LocalTime.of(21, 0));
    }

    @Test
    void claim_skipsDisabledAndFutureRows() {
        UUID enabled = SUBJECTS.get(4);
        UUID disabled = SUBJECTS.get(5);
        LocalDateTime dueAt = nowKst().minusMinutes(1);
        givenDueSchedule(enabled, dueAt);
        givenSubject(disabled);
        dailyNotificationPreferenceRepository.insertIfAbsent(
                disabled.toString(), false, dueAt, LocalDateTime.now());

        List<DailyNotificationPreference> claimed = dailyNotificationPreferenceService.claimDue(100);

        assertThat(claimed).extracting(DailyNotificationPreference::getSubjectId)
                .contains(enabled)
                .doesNotContain(disabled);
    }

    @Test
    void writeWithoutRow_failsLoudlyInsteadOfSilentNoOp() {
        // backfill 공백 재현: subject mapping만 있고 설정 행이 없다. 쓰기 경로는 행을 만들지 않으므로
        // 조용한 no-op 200이 아니라 예외로 크게 실패해야 한다(운영 신호 → backfill 재실행).
        UUID subjectId = SUBJECTS.get(3);
        SubjectMappingFixtures.ensureExists(jdbcTemplate, subjectId);

        assertThatThrownBy(() -> pushSettingService.updateDailyReminderEnabled("v1", subjectId, true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(subjectPreferenceRepository.findById(subjectId)).isEmpty();
    }

    @Test
    void enablingReminder_rearmsStaleScheduleToFutureOccurrence() {
        // 꺼둔 사이 과거로 굳은 예정 시각을 그대로 켜면 허용 지연(30분) 안쪽이라 켠 직후 tick이
        // 예정에 없던 알림을 보낸다. 켤 때 다음 미래 occurrence로 재장전해야 한다.
        UUID subjectId = SUBJECTS.get(2);
        givenSubject(subjectId);
        dailyNotificationPreferenceRepository.insertIfAbsent(
                subjectId.toString(), false, nowKst().minusMinutes(5), LocalDateTime.now());

        pushSettingService.updateDailyReminderEnabled("v1", subjectId, true);

        DailyNotificationPreference reloaded = reload(subjectId);
        assertThat(reloaded.isEnabled()).isTrue();
        // 서버 고정 시각의 다음 미래 occurrence로 재장전된다 — 전진 값을 Java가 KST로 계산해 넘기므로
        // JVM timezone과 무관해야 한다(UTC CI가 이 회귀를 잡는다).
        assertThat(reloaded.getNextDueAt()).isAfter(nowKst());
        assertThat(reloaded.getNextDueAt().toLocalTime()).isEqualTo(LocalTime.of(21, 0));

        // 같은 값 재요청도 멱등 성공이다 — 0행 판정이 matched가 아니라 changed 기준으로 바뀌면 깨진다.
        assertThatCode(() -> pushSettingService.updateDailyReminderEnabled("v1", subjectId, true))
                .doesNotThrowAnyException();
    }

    // --- FK 계약 ---

    @Test
    void masterRowCannotBeDeletedWhileDailyRowExists() {
        UUID subjectId = SUBJECTS.get(0);
        givenDueSchedule(subjectId, LocalDateTime.of(2026, 7, 21, 21, 0));

        // RESTRICT — 일일 알림 행 정리를 빠뜨린 삭제가 조용히 통과하지 않는다.
        assertThat(catchDeleteFailure(subjectId)).isTrue();

        assertThat(dailyNotificationPreferenceRepository.deleteBySubjectId(subjectId)).isEqualTo(1);
        assertThat(subjectPreferenceRepository.deleteBySubjectId(subjectId)).isEqualTo(1);
    }

    private boolean catchDeleteFailure(UUID subjectId) {
        try {
            subjectPreferenceRepository.deleteBySubjectId(subjectId);
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }
}
