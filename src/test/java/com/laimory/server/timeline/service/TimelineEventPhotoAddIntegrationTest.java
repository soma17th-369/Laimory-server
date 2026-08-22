package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static com.laimory.server.testsupport.SubjectMappingFixtures.ensureExists;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.testsupport.SubjectMappingFixtures;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoPayloadRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventRequest;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import com.laimory.server.timeline.repository.TimelineEventItemRepository;
import com.laimory.server.timeline.repository.TimelineEventRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Event PATCH PHOTO append의 실제 MySQL transaction과 과거 guard 키 무시 계약 검증. */
@SpringBootTest
@ActiveProfiles("docker")
@TestPropertySource(properties = "photo.cdn.domain=cdn.integration.test")
@Tag("integration")
class TimelineEventPhotoAddIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2000, 1, 6);
    private static final String FILENAME = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg";
    // PATCH 입력 경계의 canonical lowercase UUID 검증(version 무관)을 통과하는 rawId.
    private static final String RAW_ID = "0190a1b2-0001-7000-8000-000000000001";

    @Autowired
    private TimelineEventEditService timelineEventEditService;
    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private TimelineEventRepository timelineEventRepository;
    @Autowired
    private TimelineItemRepository timelineItemRepository;
    @Autowired
    private TimelineEventItemRepository timelineEventItemRepository;
    @Autowired
    private RedisGateway redisGateway;
    @Autowired
    private ObjectMapper objectMapper;
    // junction 저장 실패 주입용 spy — 나머지 테스트에서는 pass-through라 기존 계약에 영향이 없다.
    @MockitoSpyBean
    private TimelineEventItemService timelineEventItemService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID subjectId;
    private long legacyUserId;
    private Long recordId;
    private Long eventId;

    @BeforeEach
    void setUp() {
        subjectId = UUID.randomUUID();
        legacyUserId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
        ensureExists(jdbcTemplate, subjectId);
        recordId = dailyRecordRepository.save(
                DailyRecord.createDraft(subjectId, DATE, DATE.atTime(12, 0), "Asia/Seoul"))
                .getDailyRecordId();
        eventId = timelineEventRepository.save(TimelineEvent.of(
                recordId, TimelineEventType.REST, DATE.atTime(9, 0), DATE.atTime(10, 0),
                "기존 제목", "기존 부제", null, null, null))
                .getTimelineEventId();
    }

    @AfterEach
    void cleanUp() {
        List<Long> itemIds = timelineEventItemRepository.findByTimelineEventId(eventId).stream()
                .map(TimelineEventItem::getTimelineItemId)
                .toList();
        dailyRecordRepository.findById(recordId)
                .ifPresent(record -> dailyRecordRepository.deleteById(record.getDailyRecordId()));
        if (!itemIds.isEmpty()) {
            timelineItemRepository.deleteAllByIdInBatch(itemIds);
        }
        // 가입 transaction이 만든 subject 축 push 행(#314)이 남아 있으면 mapping 삭제가 FK RESTRICT에 막힌다.
        SubjectMappingFixtures.deleteSubjectScopedPushRows(jdbcTemplate, subjectId);
        jdbcTemplate.update("DELETE FROM user_subject_links WHERE subject_id = ?", subjectId.toString());
        redisGateway.delete(legacyGuardKey());
    }

    @Test
    void samePatchRetryCreatesOnePhotoAndOneLink_withManualPayloadContract() throws Exception {
        UpdateTimelineEventRequest request = request("새 제목", "새 메모", List.of(photo(RAW_ID)));
        long itemCountBefore = timelineItemRepository.count();

        timelineEventEditService.updateEvent("v1", subjectId, eventId, request);
        timelineEventEditService.updateEvent("v1", subjectId, eventId, request);

        assertThat(timelineItemRepository.count()).isEqualTo(itemCountBefore + 1);
        List<TimelineEventItem> links = timelineEventItemRepository.findByTimelineEventId(eventId);
        assertThat(links).hasSize(1);

        TimelineItem stored = timelineItemRepository.findById(links.get(0).getTimelineItemId()).orElseThrow();
        PhotoPayload payload = objectMapper.treeToValue(stored.getPayload(), PhotoPayload.class);
        assertThat(payload.filename()).isEqualTo(FILENAME);
        assertThat(payload.clientPhotoUri()).isEqualTo("content://photo/" + RAW_ID);
        assertThat(payload.latitude()).isEqualTo(37.5);
        assertThat(payload.longitude()).isEqualTo(127.0);
        assertThat(payload.description()).isNull();
        assertThat(stored.getPayload().has("description")).isFalse();
        assertThat(payload.photoUrl()).isEqualTo("https://cdn.integration.test/"
                + com.laimory.server.timeline.photo.PhotoObjectKeys.subjectFullKey(FILENAME, subjectId));

        TimelineEvent updated = timelineEventRepository.findById(eventId).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("새 제목");
        assertThat(updated.getMemo()).isEqualTo("새 메모");
    }

    @Test
    void staleGuardKeyDoesNotBlockPhotoPatchAndRemainsUntouched() {
        redisGateway.set(legacyGuardKey(), "task:legacy-photo", Duration.ofHours(1));

        UpdateTimelineEventRequest photoRequest = request("사진 제목", "사진 메모",
                List.of(photo(RAW_ID)));
        timelineEventEditService.updateEvent("v1", subjectId, eventId, photoRequest);

        assertThat(timelineEventItemRepository.findByTimelineEventId(eventId)).hasSize(1);
        TimelineEvent updated = timelineEventRepository.findById(eventId).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("사진 제목");
        assertThat(updated.getMemo()).isEqualTo("사진 메모");
        assertThat(redisGateway.get(legacyGuardKey())).isEqualTo("task:legacy-photo");
    }

    @Test
    void junctionSaveFailureRollsBackEventMutationAndNewItem() {
        long itemCountBefore = timelineItemRepository.count();
        doThrow(new RuntimeException("junction save 강제 실패"))
                .when(timelineEventItemService).saveAll(anyList());

        assertThatThrownBy(() -> timelineEventEditService.updateEvent("v1", subjectId, eventId,
                request("새 제목", "새 메모", List.of(photo(RAW_ID)))))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("junction save 강제 실패");

        // 단일 transaction 계약: Event 상세·memo 변경과 새 Item 저장이 junction 실패와 함께 전부 원복된다.
        TimelineEvent event = timelineEventRepository.findById(eventId).orElseThrow();
        assertThat(event.getTitle()).isEqualTo("기존 제목");
        assertThat(event.getSubtitle()).isEqualTo("기존 부제");
        assertThat(event.getMemo()).isNull();
        assertThat(timelineItemRepository.count()).isEqualTo(itemCountBefore);
        assertThat(timelineEventItemRepository.findByTimelineEventId(eventId)).isEmpty();
        assertThat(dailyRecordRepository.findById(recordId)).isPresent();
    }

    private UpdateTimelineEventRequest request(String title, String memo,
                                               List<UpdateTimelineEventPhotoRequest> photos) {
        return new UpdateTimelineEventRequest(
                title, "부제", DATE.atTime(14, 0), DATE.atTime(15, 0), null,
                memo, true, photos);
    }

    private UpdateTimelineEventPhotoRequest photo(String rawId) {
        return new UpdateTimelineEventPhotoRequest(
                rawId, DATE.atTime(14, 5), null,
                new UpdateTimelineEventPhotoPayloadRequest(
                        FILENAME, "content://photo/" + rawId, 37.5, 127.0));
    }

    private String legacyGuardKey() {
        return "timeline:date-guard:" + legacyUserId + ":" + DATE;
    }
}
