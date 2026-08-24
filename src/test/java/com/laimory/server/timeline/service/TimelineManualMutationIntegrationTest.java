package com.laimory.server.timeline.service;

import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.CreateTimelineEventRequest;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoPayloadRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoRequest;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineEventItemRepository;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 수동 mutation(#325 감정 수정·#326 Event 수동 생성·#361 사진 동시 추가) 통합 검증(MySQL).
 *
 * <p>고정하는 계약:
 * <ul>
 *   <li>SAVED 감정 수정은 감정과 {@code updated_at}만 바꾸고 status는 불변이다. DRAFT는 {@code -1020}으로
 *       거절되고 {@code emotion_type}은 null로 남는다.</li>
 *   <li>같은 값 재요청·순차 변경은 성공하고 마지막 감정이 조회 API에 보인다. 사전 조회 뒤 삭제된
 *       snapshot ID는 stale 성공/500이 아니라 404다.</li>
 *   <li>기존 save API는 여전히 {@code DRAFT→SAVED + 최초 감정}을 한 UPDATE로 처리한다.</li>
 *   <li>수동 Event는 DRAFT/SAVED 모두에 insert되며 question/place/address null이고, 날짜 조회에서
 *       startAt/ID 정렬대로 노출된다. 타인 subject·없는 날짜에는 행이 생기지 않는다.</li>
 *   <li>photosToAdd 포함 생성은 Event·PHOTO Item·junction을 한 commit으로 만들고(응답 == 직후 조회),
 *       기존 PHOTO 재사용·PENDING job 취소·PROCESSING 409는 PATCH와 같은 규칙이며, 사진 해석 실패는
 *       Event insert까지 전체 롤백된다(부분 상태 금지).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("docker")
@TestPropertySource(properties = "photo.cdn.domain=cdn.integration.test")
@Tag("integration")
class TimelineManualMutationIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2000, 2, 5);
    private static final LocalDate ABSENT_DATE = LocalDate.of(2000, 2, 6);
    private static final String ZONE = "Asia/Seoul";
    private static final String RAW_ID = "0190c1d2-0001-7000-8000-000000000001";
    private static final String FILENAME = "0190c1d2-0002-7000-8000-000000000002.jpg";
    private static final String OTHER_FILENAME = "0190c1d2-0003-7000-8000-000000000003.jpg";

    @Autowired
    private TimelineSaveService timelineSaveService;
    @Autowired
    private DailyRecordEmotionUpdateService dailyRecordEmotionUpdateService;
    @Autowired
    private DailyRecordEmotionUpdateTransactionService dailyRecordEmotionUpdateTransactionService;
    @Autowired
    private TimelineEventCreateService timelineEventCreateService;
    @Autowired
    private DailyTimelineService dailyTimelineService;
    @Autowired
    private TimelinePhotoDeleteJobService timelinePhotoDeleteJobService;
    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private TimelineEventRepository timelineEventRepository;
    @Autowired
    private TimelineItemRepository timelineItemRepository;
    @Autowired
    private TimelineEventItemRepository timelineEventItemRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 다른 테스트·잔여 데이터와 겹치지 않도록 실행마다 임의 사용자로 격리한다.
    private UUID subjectId;
    private Long recordId;
    // 사진 시나리오가 만든 독립 Item 행 정리를 위해 planted/생성 Item ID를 추적한다.
    private final List<Long> trackedItemIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        subjectId = UUID.randomUUID();
        ensureExists(jdbcTemplate, subjectId);
        recordId = dailyRecordRepository.save(DailyRecord.createDraft(subjectId, DATE, DATE.atTime(12, 0), ZONE))
                .getDailyRecordId();
    }

    @AfterEach
    void cleanUp() {
        Set<Long> itemIds = new LinkedHashSet<>(trackedItemIds);
        List.of(DATE, ABSENT_DATE).forEach(date ->
                dailyRecordRepository.findBySubjectIdAndRecordDate(subjectId, date)
                        .ifPresent(record -> {
                            timelineEventRepository
                                    .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(
                                            record.getDailyRecordId())
                                    .forEach(event -> timelineEventItemRepository
                                            .findByTimelineEventId(event.getTimelineEventId())
                                            .forEach(link -> itemIds.add(link.getTimelineItemId())));
                            dailyRecordRepository.deleteById(record.getDailyRecordId());
                        }));
        // 삭제 job이 Item을 FK RESTRICT로 참조하므로 job → Item 순으로 지운다.
        itemIds.forEach(itemId -> jdbcTemplate.update(
                "DELETE FROM timeline_photo_delete_jobs WHERE timeline_item_id = ?", itemId));
        if (!itemIds.isEmpty()) {
            timelineItemRepository.deleteAllByIdInBatch(itemIds);
        }
        SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, subjectId);
        jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?", subjectId.toString());
    }

    // --- #325 SAVED 감정 수정 ---

    @Test
    void SAVED_감정_수정은_감정과_updated_at만_바꾸고_status는_불변이다() {
        timelineSaveService.save("v1", subjectId, DATE, EmotionType.HAPPY);
        LocalDateTime updatedAtAfterSave =
                dailyRecordRepository.findById(recordId).orElseThrow().getUpdatedAt();

        dailyRecordEmotionUpdateService.updateEmotion("v1", subjectId, DATE, EmotionType.VERY_UNHAPPY);

        DailyRecord updated = dailyRecordRepository.findById(recordId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(DailyRecordStatus.SAVED);
        assertThat(updated.getEmotionType()).isEqualTo(EmotionType.VERY_UNHAPPY);
        assertThat(updated.getUpdatedAt()).isAfter(updatedAtAfterSave);
    }

    @Test
    void DRAFT는_1020으로_거절되고_emotion_type은_null로_남는다() {
        assertThatThrownBy(() -> dailyRecordEmotionUpdateService.updateEmotion(
                "v1", subjectId, DATE, EmotionType.HAPPY))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_SAVED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1020);
                });

        DailyRecord record = dailyRecordRepository.findById(recordId).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(DailyRecordStatus.DRAFT);
        assertThat(record.getEmotionType()).isNull();
    }

    @Test
    void 같은_값_재요청과_순차_변경은_성공하고_마지막_감정이_조회에_보인다() {
        timelineSaveService.save("v1", subjectId, DATE, EmotionType.HAPPY);

        assertThatCode(() -> {
            dailyRecordEmotionUpdateService.updateEmotion("v1", subjectId, DATE, EmotionType.HAPPY);
            dailyRecordEmotionUpdateService.updateEmotion("v1", subjectId, DATE, EmotionType.NEUTRAL);
            dailyRecordEmotionUpdateService.updateEmotion("v1", subjectId, DATE, EmotionType.NEUTRAL);
        }).doesNotThrowAnyException();

        DailyTimelineResponse daily = dailyTimelineService.getDailyTimeline("v1", subjectId, DATE);
        assertThat(daily.status()).isEqualTo(DailyRecordStatus.SAVED);
        assertThat(daily.emotionType()).isEqualTo(EmotionType.NEUTRAL);
    }

    @Test
    void 사전_조회_뒤_삭제된_snapshot_ID는_stale_성공이_아니라_404다() {
        timelineSaveService.save("v1", subjectId, DATE, EmotionType.HAPPY);
        dailyRecordRepository.deleteById(recordId);

        // 오케스트레이터의 사전 조회와 트랜잭션 writer 사이에 삭제가 끼어든 경합을 재현한다 —
        // writer는 자기 트랜잭션의 첫 DB 작업이 UPDATE라 stale snapshot을 보지 않는다.
        assertThatThrownBy(() -> dailyRecordEmotionUpdateTransactionService.updateEmotion(
                subjectId, recordId, EmotionType.NEUTRAL))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });
    }

    @Test
    void 기존_save는_여전히_DRAFT에서_SAVED와_최초_감정을_한_번에_확정한다() {
        timelineSaveService.save("v1", subjectId, DATE, EmotionType.VERY_HAPPY);

        DailyRecord saved = dailyRecordRepository.findById(recordId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(DailyRecordStatus.SAVED);
        assertThat(saved.getEmotionType()).isEqualTo(EmotionType.VERY_HAPPY);
    }

    // --- #326 Event 수동 생성 ---

    @Test
    void DRAFT와_SAVED_모두에_생성되고_AI_필드는_null_audit_컬럼은_채워진다() {
        TimelineEventResponse onDraft = timelineEventCreateService.createEvent("v1", subjectId, DATE,
                new CreateTimelineEventRequest(TimelineEventType.REST, "카페에서 휴식", "성수동",
                        DATE.atTime(14, 0), DATE.atTime(15, 0), " 책을 읽었다. ", List.of()));

        timelineSaveService.save("v1", subjectId, DATE, EmotionType.HAPPY);
        TimelineEventResponse onSaved = timelineEventCreateService.createEvent("v1", subjectId, DATE,
                new CreateTimelineEventRequest(TimelineEventType.MEAL, "저녁", null,
                        DATE.atTime(19, 0), null, null, List.of()));

        for (TimelineEventResponse response : List.of(onDraft, onSaved)) {
            assertThat(response.timelineEventId()).isNotNull();
            assertThat(response.question()).isNull();
            assertThat(response.place()).isNull();
            assertThat(response.address()).isNull();
            assertThat(response.items()).isEmpty();
        }

        TimelineEvent stored = timelineEventRepository.findById(onDraft.timelineEventId()).orElseThrow();
        assertThat(stored.getDailyRecordId()).isEqualTo(recordId);
        assertThat(stored.getQuestion()).isNull();
        assertThat(stored.getPlace()).isNull();
        assertThat(stored.getAddress()).isNull();
        assertThat(stored.getMemo()).isEqualTo(" 책을 읽었다. ");
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getUpdatedAt()).isNotNull();
    }

    @Test
    void 수동_Event는_junction과_Item이_없고_날짜_조회에서_정렬대로_노출된다() {
        Long later = timelineEventCreateService.createEvent("v1", subjectId, DATE,
                new CreateTimelineEventRequest(TimelineEventType.REST, "휴식", null,
                        DATE.atTime(15, 0), null, null, List.of())).timelineEventId();
        Long earlier = timelineEventCreateService.createEvent("v1", subjectId, DATE,
                new CreateTimelineEventRequest(TimelineEventType.MEAL, "점심", null,
                        DATE.atTime(12, 0), null, null, List.of())).timelineEventId();

        for (Long eventId : List.of(later, earlier)) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM timeline_event_items WHERE timeline_event_id = ?",
                    Long.class, eventId)).isZero();
        }

        DailyTimelineResponse daily = dailyTimelineService.getDailyTimeline("v1", subjectId, DATE);
        assertThat(daily.events())
                .extracting(TimelineEventResponse::timelineEventId)
                .containsExactly(earlier, later);
        assertThat(daily.events()).allSatisfy(event -> assertThat(event.items()).isEmpty());
    }

    @Test
    void 타인_subject나_없는_날짜에는_행이_생기지_않는다() {
        CreateTimelineEventRequest request = new CreateTimelineEventRequest(
                TimelineEventType.REST, "휴식", null, DATE.atTime(14, 0), null, null, List.of());

        assertThatThrownBy(() -> timelineEventCreateService.createEvent(
                "v1", UUID.randomUUID(), DATE, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(-404));
        assertThatThrownBy(() -> timelineEventCreateService.createEvent(
                "v1", subjectId, ABSENT_DATE, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(-404));

        assertThat(timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(recordId)).isEmpty();
    }

    // --- #361 photosToAdd 동시 추가 ---

    @Test
    void photosToAdd_포함_생성은_한_commit으로_저장되고_응답이_직후_조회와_일치한다() throws Exception {
        long itemCountBefore = timelineItemRepository.count();

        TimelineEventResponse response = timelineEventCreateService.createEvent("v1", subjectId, DATE,
                new CreateTimelineEventRequest(TimelineEventType.REST, "카페에서 휴식", "성수동",
                        DATE.atTime(14, 0), DATE.atTime(15, 0), "사진과 함께",
                        List.of(photoInput(RAW_ID, FILENAME))));
        trackItems(response);

        // Event·PHOTO Item·junction이 각 1행 — 한 트랜잭션 commit의 결과다.
        assertThat(timelineItemRepository.count()).isEqualTo(itemCountBefore + 1);
        assertThat(timelineEventItemRepository.findByTimelineEventId(response.timelineEventId()))
                .hasSize(1);
        assertThat(response.items()).hasSize(1);

        TimelineItem stored = timelineItemRepository
                .findById(response.items().get(0).timelineItemId()).orElseThrow();
        PhotoPayload payload = objectMapper.treeToValue(stored.getPayload(), PhotoPayload.class);
        assertThat(stored.getItemType()).isEqualTo(ItemType.PHOTO);
        assertThat(stored.getRawId()).isEqualTo(RAW_ID);
        assertThat(payload.filename()).isEqualTo(FILENAME);
        assertThat(payload.description()).isNull();
        assertThat(stored.getPayload().has("description")).isFalse();
        assertThat(payload.photoUrl()).isEqualTo(
                "https://cdn.integration.test/" + PhotoObjectKeys.subjectFullKey(FILENAME, subjectId));

        // 생성 응답의 items는 직후 날짜 조회의 해당 Event items와 순서·내용이 같다.
        DailyTimelineResponse daily = dailyTimelineService.getDailyTimeline("v1", subjectId, DATE);
        TimelineEventResponse queried = daily.events().stream()
                .filter(event -> event.timelineEventId().equals(response.timelineEventId()))
                .findFirst().orElseThrow();
        assertThat(response.items()).isEqualTo(queried.items());
    }

    @Test
    void 같은_record의_기존_PHOTO_rawId는_신규_Item_없이_junction만_추가된다() {
        TimelineEventResponse first = timelineEventCreateService.createEvent("v1", subjectId, DATE,
                new CreateTimelineEventRequest(TimelineEventType.REST, "첫 이벤트", null,
                        DATE.atTime(14, 0), null, null, List.of(photoInput(RAW_ID, FILENAME))));
        trackItems(first);
        Long existingItemId = first.items().get(0).timelineItemId();
        long itemCountBefore = timelineItemRepository.count();

        TimelineEventResponse second = timelineEventCreateService.createEvent("v1", subjectId, DATE,
                new CreateTimelineEventRequest(TimelineEventType.MEAL, "둘째 이벤트", null,
                        DATE.atTime(16, 0), null, null, List.of(photoInput(RAW_ID, FILENAME))));

        assertThat(timelineItemRepository.count()).isEqualTo(itemCountBefore);
        assertThat(second.items())
                .singleElement()
                .satisfies(item -> assertThat(item.timelineItemId()).isEqualTo(existingItemId));
        assertThat(timelineEventItemRepository.findByTimelineEventId(second.timelineEventId()))
                .extracting(TimelineEventItem::getTimelineItemId)
                .containsExactly(existingItemId);
    }

    @Test
    void 기존_PHOTO_rawId의_입력이_다르면_400이고_새_Event도_롤백된다() {
        TimelineEventResponse first = timelineEventCreateService.createEvent("v1", subjectId, DATE,
                new CreateTimelineEventRequest(TimelineEventType.REST, "첫 이벤트", null,
                        DATE.atTime(14, 0), null, null, List.of(photoInput(RAW_ID, FILENAME))));
        trackItems(first);
        long eventCountBefore = timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(recordId).size();
        long itemCountBefore = timelineItemRepository.count();

        assertThatThrownBy(() -> timelineEventCreateService.createEvent("v1", subjectId, DATE,
                new CreateTimelineEventRequest(TimelineEventType.MEAL, "롤백할 이벤트", null,
                        DATE.atTime(16, 0), null, null, List.of(photoInput(RAW_ID, OTHER_FILENAME)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("photo input does not match existing rawId");

        assertThat(timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(recordId))
                .hasSize((int) eventCountBefore);
        assertThat(timelineItemRepository.count()).isEqualTo(itemCountBefore);
    }

    @Test
    void PENDING_delete_job은_취소되고_보존_Item이_재연결된다() {
        Long preservedItemId = plantOrphanPhotoItemWithJob();

        TimelineEventResponse response = timelineEventCreateService.createEvent("v1", subjectId, DATE,
                new CreateTimelineEventRequest(TimelineEventType.REST, "재연결", null,
                        DATE.atTime(14, 0), null, null, List.of(photoInput(RAW_ID, FILENAME))));

        assertThat(response.items())
                .singleElement()
                .satisfies(item -> assertThat(item.timelineItemId()).isEqualTo(preservedItemId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM timeline_photo_delete_jobs WHERE timeline_item_id = ?",
                Long.class, preservedItemId)).isZero();
        assertThat(timelineEventItemRepository.findByTimelineEventId(response.timelineEventId()))
                .extracting(TimelineEventItem::getTimelineItemId)
                .containsExactly(preservedItemId);
    }

    @Test
    void PENDING_delete_job_PHOTO의_입력이_다르면_400이고_job_취소도_롤백된다() {
        Long preservedItemId = plantOrphanPhotoItemWithJob();
        UpdateTimelineEventPhotoRequest mismatched = new UpdateTimelineEventPhotoRequest(
                RAW_ID, DATE.atTime(14, 5), null,
                new UpdateTimelineEventPhotoPayloadRequest(
                        FILENAME, "content://photo/changed", 37.5, 127.0));

        assertThatThrownBy(() -> timelineEventCreateService.createEvent("v1", subjectId, DATE,
                new CreateTimelineEventRequest(TimelineEventType.REST, "재연결 거절", null,
                        DATE.atTime(14, 0), null, null, List.of(mismatched))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("photo input does not match existing rawId");

        assertThat(timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(recordId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM timeline_photo_delete_jobs WHERE timeline_item_id = ?",
                Long.class, preservedItemId)).isEqualTo(1);
    }

    @Test
    void 유효한_PROCESSING_delete_job은_409이고_Event_행이_생기지_않는다() {
        Long preservedItemId = plantOrphanPhotoItemWithJob();
        jdbcTemplate.update(
                "UPDATE timeline_photo_delete_jobs SET status = 'PROCESSING', "
                        + "available_at = DATE_ADD(NOW(), INTERVAL 2 DAY) WHERE timeline_item_id = ?",
                preservedItemId);

        assertThatThrownBy(() -> timelineEventCreateService.createEvent("v1", subjectId, DATE,
                new CreateTimelineEventRequest(TimelineEventType.REST, "재연결 시도", null,
                        DATE.atTime(14, 0), null, null, List.of(photoInput(RAW_ID, FILENAME)))))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.PHOTO_DELETE_IN_PROGRESS);
                    assertThat(exception.getErrorCode()).isEqualTo(-1019);
                });

        // 전체 롤백 — Event 행도, 새 junction도 없고 job은 그대로 남는다.
        assertThat(timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(recordId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM timeline_photo_delete_jobs WHERE timeline_item_id = ?",
                Long.class, preservedItemId)).isEqualTo(1);
    }

    @Test
    void 사진_해석_실패는_Event_insert까지_전체_롤백된다() {
        // 같은 record에 같은 rawId의 non-PHOTO Item을 연결해 두면 resolve가 400으로 거절한다 —
        // Event를 먼저 insert한 뒤의 실패라 rollback이 실제로 일어나야 사진 없는 Event가 남지 않는다.
        Long holderEventId = timelineEventCreateService.createEvent("v1", subjectId, DATE,
                new CreateTimelineEventRequest(TimelineEventType.MEAL, "기존 이벤트", null,
                        DATE.atTime(9, 0), null, null, List.of())).timelineEventId();
        TimelineItem nonPhoto = timelineItemRepository.save(TimelineItem.of(
                ItemType.HEALTH, RAW_ID, DATE.atTime(8, 0), null,
                objectMapper.createObjectNode().put("metric", "STEPS")));
        trackedItemIds.add(nonPhoto.getTimelineItemId());
        timelineEventItemRepository.save(TimelineEventItem.of(holderEventId, nonPhoto.getTimelineItemId()));
        long eventCountBefore = timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(recordId).size();
        long itemCountBefore = timelineItemRepository.count();

        assertThatThrownBy(() -> timelineEventCreateService.createEvent("v1", subjectId, DATE,
                new CreateTimelineEventRequest(TimelineEventType.REST, "사진 충돌", null,
                        DATE.atTime(14, 0), null, null, List.of(photoInput(RAW_ID, FILENAME)))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(recordId))
                .hasSize((int) eventCountBefore);
        assertThat(timelineItemRepository.count()).isEqualTo(itemCountBefore);
    }

    /** 삭제 대기 중 보존된 orphan PHOTO Item과 그 PENDING delete job을 심는다. */
    private Long plantOrphanPhotoItemWithJob() {
        TimelineItem preserved = timelineItemRepository.save(TimelineItem.of(
                ItemType.PHOTO, RAW_ID, DATE.atTime(14, 5), null,
                objectMapper.valueToTree(new PhotoPayload(
                        FILENAME, "content://photo/" + RAW_ID, 37.5, 127.0,
                        null, null, null, null))));
        trackedItemIds.add(preserved.getTimelineItemId());
        String objectKey = PhotoObjectKeys.subjectFullKey(FILENAME, subjectId);
        assertThat(timelinePhotoDeleteJobService.insertIfAbsent(preserved.getTimelineItemId(), objectKey))
                .isTrue();
        return preserved.getTimelineItemId();
    }

    private void trackItems(TimelineEventResponse response) {
        response.items().forEach(item -> trackedItemIds.add(item.timelineItemId()));
    }

    private static UpdateTimelineEventPhotoRequest photoInput(String rawId, String filename) {
        return new UpdateTimelineEventPhotoRequest(
                rawId, DATE.atTime(14, 5), null,
                new UpdateTimelineEventPhotoPayloadRequest(
                        filename, "content://photo/" + rawId, 37.5, 127.0));
    }
}
