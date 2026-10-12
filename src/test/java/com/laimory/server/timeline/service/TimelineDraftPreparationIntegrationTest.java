package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.testsupport.SubjectMappingFixtures;
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
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * draft 선생성 트랜잭션({@link TimelineDraftPreparationService#prepareDraft})의 실제 MySQL rollback 검증.
 * SAVED 재확인 거절·source unique 위반·잔액 부족 차감 거절(#565)이 record 생성·metadata 갱신·source 저장·
 * 크레딧 차감을 all-or-nothing으로
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
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID subjectId;
    private String taskId;

    @BeforeEach
    void setUp() {
        subjectId = UUID.randomUUID();
        ensureExists(jdbcTemplate, subjectId);
        // 가입 transaction이 만드는 크레딧 행 — 선생성 transaction이 차감한다(#565).
        SubjectMappingFixtures.ensureCredits(jdbcTemplate, subjectId, 60);
        taskId = UUID.randomUUID().toString();
    }

    @AfterEach
    void cleanUp() {
        timelineDraftSourceItemRepository.deleteByTaskId(taskId);
        // soft delete된 record까지 지운다(활성 조회는 삭제 행을 못 본다) — 계정 삭제용 무필터 id 조회를 쓴다.
        dailyRecordRepository.findIdsBySubjectIdAfterId(subjectId, 0L, PageRequest.of(0, 100))
                .forEach(dailyRecordRepository::deleteById);
        // 가입 transaction이 만든 subject 축 push 행(#314)이 남아 있으면 mapping 삭제가 FK RESTRICT에 막힌다.
        SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, subjectId);
        jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?", subjectId.toString());
    }

    @Test
    void savedRecordRecheck_rollsBackMetadataUpdate_andKeepsSourcesEmpty() {
        // AI final write가 SAVED로 만든 record를 재현한다(서버 경로에는 SAVED 전이가 없어 reflection으로 구성).
        DailyRecord saved = DailyRecord.createDraft(subjectId, DATE, ORIGINAL_AT, ORIGINAL_ZONE);
        ReflectionTestUtils.setField(saved, "status", DailyRecordStatus.SAVED);
        long recordId = dailyRecordRepository.save(saved).getDailyRecordId();

        assertThatThrownBy(() -> timelineDraftPreparationService.prepareDraft(
                taskId, subjectId, DATE, DATE.atTime(23, 59), "UTC", List.of(sourceRow("raw-1"))))
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
    void savesSixtyEightSourcesInInputOrder_withPayloadAndAuditColumns() {
        List<String> rawIds = IntStream.range(0, 68)
                .mapToObj(index -> "raw-" + (67 - index))
                .toList();
        List<TimelineDraftSourceItem> rows = rawIds.stream().map(this::sourceRow).toList();

        long dailyRecordId = timelineDraftPreparationService.prepareDraft(
                taskId, subjectId, DATE, ORIGINAL_AT, ORIGINAL_ZONE, rows);

        assertThat(dailyRecordId).isPositive();
        List<TimelineDraftSourceItem> saved = timelineDraftSourceItemRepository.findByTaskId(taskId);
        assertThat(saved).hasSize(68);
        assertThat(saved).extracting(TimelineDraftSourceItem::getRawId)
                .containsExactlyElementsOf(rawIds);
        assertThat(saved).extracting(TimelineDraftSourceItem::getTimelineDraftSourceItemId).isSorted();
        assertThat(saved).allSatisfy(row -> {
            assertThat(row.getSubjectId()).isEqualTo(subjectId);
            assertThat(row.getItemType()).isEqualTo(ItemType.CALENDAR);
            assertThat(row.getStartAt()).isEqualTo(DATE.atTime(9, 0));
            assertThat(row.getEndAt()).isNull();
            assertThat(row.getPayload().get("title").asText()).isEqualTo("회의");
            assertThat(row.getCreatedAt()).isNotNull();
            assertThat(row.getUpdatedAt()).isNotNull();
            assertThat(row.getModifiedBy()).isNull();
        });
    }

    @Test
    void duplicateSourceRawId_rollsBackNewRecordAndAllSources() {
        // (task_id, raw_id) unique 위반이 source 저장 중간에 터지면 신규 record와 먼저 INSERT된 source까지
        // 전부 원복돼야 한다(부분 저장 금지).
        assertThatThrownBy(() -> timelineDraftPreparationService.prepareDraft(
                taskId, subjectId, DATE, ORIGINAL_AT, ORIGINAL_ZONE,
                List.of(sourceRow("raw-dup"), sourceRow("raw-dup"))))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(dailyRecordRepository.findBySubjectIdAndRecordDateAndDeletedAtIsNull(subjectId, DATE)).isEmpty();
        assertThat(timelineDraftSourceItemRepository.findByTaskId(taskId)).isEmpty();
    }

    @Test
    void insufficientCredits_rollsBackNewRecordAndAllSources_withoutCharging() {
        // 조건부 차감 거절(403)이 같은 transaction의 record·source 저장까지 되돌려야 한다 — 차감 없이 생성이
        // 시작되거나, 생성 없이 빈 DRAFT·staging만 남지 않는다.
        jdbcTemplate.update("UPDATE subject_credits SET remaining = 0 WHERE subject_id = ?", subjectId.toString());

        assertThatThrownBy(() -> timelineDraftPreparationService.prepareDraft(
                taskId, subjectId, DATE, ORIGINAL_AT, ORIGINAL_ZONE, List.of(sourceRow("raw-1"))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.INSUFFICIENT_CREDIT));

        assertThat(dailyRecordRepository.findBySubjectIdAndRecordDateAndDeletedAtIsNull(subjectId, DATE)).isEmpty();
        assertThat(timelineDraftSourceItemRepository.findByTaskId(taskId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM credit_histories WHERE reference_id = ?",
                Integer.class, taskId)).isZero();
    }

    @Test
    void preparedDraft_chargesCreditAndRecordsTaskAsCharged() {
        long dailyRecordId = timelineDraftPreparationService.prepareDraft(
                taskId, subjectId, DATE, ORIGINAL_AT, ORIGINAL_ZONE, List.of(sourceRow("raw-1")));

        assertThat(dailyRecordId).isPositive();
        int cost = jdbcTemplate.queryForObject(
                "SELECT cost FROM credit_costs WHERE type = 'TIMELINE_CREATION'", Integer.class);
        assertThat(jdbcTemplate.queryForObject("SELECT remaining FROM subject_credits WHERE subject_id = ?",
                Integer.class, subjectId.toString())).isEqualTo(60 - cost);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM credit_histories WHERE cost_type = 'TIMELINE_CREATION' AND reference_id = ?",
                String.class, taskId)).isEqualTo("CHARGED");
    }

    private TimelineDraftSourceItem sourceRow(String rawId) {
        return TimelineDraftSourceItem.of(taskId, subjectId, ItemType.CALENDAR, rawId,
                DATE.atTime(9, 0), null,
                objectMapper.valueToTree(new CalendarPayload("회의", null, null, null)));
    }
}
