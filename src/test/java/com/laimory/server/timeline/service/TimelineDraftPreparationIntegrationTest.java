package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * draft 선생성 트랜잭션({@link TimelineDraftPreparationService#prepareDraft})의 실제 MySQL rollback 검증.
 * SAVED 재확인 거절과 source unique 위반이 record 생성·metadata 갱신·source 저장을 all-or-nothing으로
 * 되돌리는지 커밋 경계 밖(새 트랜잭션 조회)에서 관찰한다 — 그래서 테스트 클래스에 @Transactional을 붙이지
 * 않는다(붙이면 서비스 rollback이 테스트 tx 롤백에 가려 관찰할 수 없다).
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineDraftPreparationIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2000, 1, 7);
    private static final LocalDateTime ORIGINAL_AT = DATE.atTime(21, 30);
    private static final String ORIGINAL_ZONE = "Asia/Seoul";

    @Autowired
    private TimelineDraftPreparationService timelineDraftPreparationService;
    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private TimelineDraftSourceItemRepository timelineDraftSourceItemRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private long userId;
    private String taskId;

    @BeforeEach
    void setUp() {
        userId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
        taskId = UUID.randomUUID().toString();
    }

    @AfterEach
    void cleanUp() {
        timelineDraftSourceItemRepository.deleteByTaskId(taskId);
        dailyRecordRepository.findByUserIdAndRecordDate(userId, DATE)
                .ifPresent(record -> dailyRecordRepository.deleteById(record.getDailyRecordId()));
    }

    @Test
    void savedRecordRecheck_rollsBackMetadataUpdate_andKeepsSourcesEmpty() {
        // AI final write가 SAVED로 만든 record를 재현한다(서버 경로에는 SAVED 전이가 없어 reflection으로 구성).
        DailyRecord saved = DailyRecord.createDraft(userId, DATE, ORIGINAL_AT, ORIGINAL_ZONE);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        long recordId = dailyRecordRepository.save(saved).getDailyRecordId();

        assertThatThrownBy(() -> timelineDraftPreparationService.prepareDraft(
                userId, DATE, DATE.atTime(23, 59), "UTC", List.of(sourceRow("raw-1"))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getExceptionType())
                                .isEqualTo(ExceptionType.DAILY_RECORD_ALREADY_SAVED));

        // findOrCreateDraft가 같은 tx 안에서 갱신한 recordAt/recordTimezone이 rollback으로 폐기돼야 한다.
        DailyRecord reloaded = dailyRecordRepository.findById(recordId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DailyRecordStatus.SAVED);
        assertThat(reloaded.getRecordAt()).isEqualTo(ORIGINAL_AT);
        assertThat(reloaded.getRecordTimezone()).isEqualTo(ORIGINAL_ZONE);
        assertThat(timelineDraftSourceItemRepository.findByTaskId(taskId)).isEmpty();
    }

    @Test
    void duplicateSourceRawId_rollsBackNewRecordAndAllSources() {
        // (task_id, raw_id) unique 위반이 source 저장 중간에 터지면 신규 record와 먼저 INSERT된 source까지
        // 전부 원복돼야 한다(부분 저장 금지).
        assertThatThrownBy(() -> timelineDraftPreparationService.prepareDraft(
                userId, DATE, ORIGINAL_AT, ORIGINAL_ZONE,
                List.of(sourceRow("raw-dup"), sourceRow("raw-dup"))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(dailyRecordRepository.findByUserIdAndRecordDate(userId, DATE)).isEmpty();
        assertThat(timelineDraftSourceItemRepository.findByTaskId(taskId)).isEmpty();
    }

    private TimelineDraftSourceItem sourceRow(String rawId) {
        return TimelineDraftSourceItem.of(taskId, userId, ItemType.CALENDAR, rawId,
                DATE.atTime(9, 0), null,
                objectMapper.valueToTree(new CalendarPayload("회의", null, null, null)));
    }
}
