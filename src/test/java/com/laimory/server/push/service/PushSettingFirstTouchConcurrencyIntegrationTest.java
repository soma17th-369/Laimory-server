package com.laimory.server.push.service;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.laimory.server.push.ScheduledNotificationType;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.testsupport.TestSubjects;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 설정 행이 <b>아직 없는</b> subject에 동시 요청이 들어올 때의 실 MySQL 검증.
 *
 * <p>get-or-create는 있는 행에 쓰기를 남기지 않으려고 비잠금 읽기를 먼저 한다. 그런데 그 읽기가
 * REPEATABLE READ 스냅샷을 고정하기 때문에, 그 사이 다른 transaction이 행을 만들어 commit하면
 * {@code INSERT IGNORE}는 최신을 보고 no-op이 되는데 <b>재조회만 과거를 본다</b>. 그대로 두면 동시
 * 최초 진입 하나가 "insert 직후인데 행이 없다"로 500이 된다 — 실제로 첫 라운드에서 재현됐다.
 *
 * <p>지금은 행이 없던 경로의 재조회를 잠금 읽기로 해서 최신 커밋을 본다. 이 테스트는 그 창이 다시
 * 열리는 것을 막는다(rollout 공백기의 신규 사용자가 설정 화면을 두 번 두드리는 상황).
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class PushSettingFirstTouchConcurrencyIntegrationTest {

    private static final int ROUNDS = 25;

    @Autowired
    private ScheduledNotificationPreferenceService scheduledNotificationPreferenceService;

    @Autowired
    private PushPreferenceService pushPreferenceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        created.forEach(id -> {
            SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, id);
            jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?", id.toString());
        });
        created.clear();
    }

    @Test
    void concurrentFirstTouch_createsRowWithoutFailingEitherRequest() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                UUID subjectId = TestSubjects.id(95_000L + round);
                SubjectMappingFixtures.ensureExists(jdbcTemplate, subjectId);
                created.add(subjectId);
                pushPreferenceService.createDefaultIfAbsent(subjectId);

                CyclicBarrier line = new CyclicBarrier(2);
                List<Future<?>> futures = List.of(
                        executor.submit(() -> {
                            line.await(10, TimeUnit.SECONDS);
                            return scheduledNotificationPreferenceService.getOrCreate(
                                    subjectId, ScheduledNotificationType.DAILY_REMINDER);
                        }),
                        executor.submit(() -> {
                            line.await(10, TimeUnit.SECONDS);
                            return scheduledNotificationPreferenceService.getOrCreate(
                                    subjectId, ScheduledNotificationType.DAILY_REMINDER);
                        }));
                for (Future<?> future : futures) {
                    int currentRound = round;
                    assertThatCode(() -> future.get(20, TimeUnit.SECONDS))
                            .as("round %d", currentRound)
                            .doesNotThrowAnyException();
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
