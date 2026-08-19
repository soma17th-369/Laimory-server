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
 * 설정 행이 <b>아직 없는</b> subject에 서로 다른 설정 변경이 동시에 들어올 때의 실 MySQL 검증.
 *
 * <p>쓰기 경로는 UPDATE를 먼저 하고 0행일 때만 행을 만든 뒤 다시 시도한다. 두 요청이 동시에 "행 없음"을
 * 만나면 한쪽만 insert에 성공하는데, 그때도 양쪽 다 자기 변경을 반영하고 끝나야 한다. 읽고 계산하는
 * 구조였다면 여기서 REPEATABLE READ 스냅샷 때문에 후발 요청이 승자의 행을 못 보고 실패했다.
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
                            scheduledNotificationPreferenceService.updateEnabled(
                                    subjectId, ScheduledNotificationType.DAILY_REMINDER, true);
                            return null;
                        }),
                        executor.submit(() -> {
                            line.await(10, TimeUnit.SECONDS);
                            scheduledNotificationPreferenceService.updateNotificationTime(
                                    subjectId, ScheduledNotificationType.DAILY_REMINDER,
                                    java.time.LocalTime.of(9, 0));
                            return null;
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
