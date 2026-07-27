package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.redis.RedisGateway;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Event PATCH PHOTO append의 실제 MySQL transaction과 Redis date guard 계약 검증. */
@SpringBootTest
@ActiveProfiles("docker")
@TestPropertySource(properties = "photo.cdn.domain=cdn.integration.test")
@Tag("integration")
class TimelineEventPhotoAddIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2000, 1, 6);
    private static final String FILENAME = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg";

    @Autowired
    private TimelineEventEditService timelineEventEditService;
    @Autowired
    private TimelineTaskService timelineTaskService;
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

    private long userId;
    private Long recordId;
    private Long eventId;

    @BeforeEach
    void setUp() {
        userId = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
        recordId = dailyRecordRepository.save(
                DailyRecord.createDraft(userId, DATE, DATE.atTime(12, 0), "Asia/Seoul"))
                .getDailyRecordId();
        eventId = timelineEventRepository.save(TimelineEvent.of(
                recordId, TimelineEventType.REST, DATE.atTime(9, 0), DATE.atTime(10, 0),
                "기존 제목", "기존 부제"))
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
        redisGateway.delete("timeline:date-guard:" + userId + ":" + DATE);
    }

    @Test
    void samePatchRetryCreatesOnePhotoAndOneLink_withManualPayloadContract() throws Exception {
        UpdateTimelineEventRequest request = request("새 제목", "새 메모", List.of(photo("raw-photo")));
        long itemCountBefore = timelineItemRepository.count();

        timelineEventEditService.updateEvent("v1", userId, eventId, request);
        timelineEventEditService.updateEvent("v1", userId, eventId, request);

        assertThat(timelineItemRepository.count()).isEqualTo(itemCountBefore + 1);
        List<TimelineEventItem> links = timelineEventItemRepository.findByTimelineEventId(eventId);
        assertThat(links).hasSize(1);

        TimelineItem stored = timelineItemRepository.findById(links.get(0).getTimelineItemId()).orElseThrow();
        PhotoPayload payload = objectMapper.treeToValue(stored.getPayload(), PhotoPayload.class);
        assertThat(payload.filename()).isEqualTo(FILENAME);
        assertThat(payload.clientPhotoUri()).isEqualTo("content://photo/raw-photo");
        assertThat(payload.latitude()).isEqualTo(37.5);
        assertThat(payload.longitude()).isEqualTo(127.0);
        assertThat(payload.description()).isNull();
        assertThat(stored.getPayload().has("description")).isFalse();
        assertThat(payload.photoUrl()).isEqualTo("https://cdn.integration.test/"
                + com.laimory.server.timeline.photo.PhotoObjectKeys.fullKey(FILENAME, userId));

        TimelineEvent updated = timelineEventRepository.findById(eventId).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("새 제목");
        assertThat(updated.getMemo()).isEqualTo("새 메모");
    }

    @Test
    void taskGuardBlocksPhotoPatchWithoutMutations_butAllowsScalarPatch_thenPhotoSucceedsAfterRelease() {
        String taskHolder = TimelineTaskService.taskGuardHolder("event-photo-it");
        assertThat(timelineTaskService.claimDateGuard(userId, DATE, taskHolder)).isTrue();

        UpdateTimelineEventRequest photoRequest = request("막혀야 할 제목", "막혀야 할 메모",
                List.of(photo("raw-photo")));
        assertThatThrownBy(() -> timelineEventEditService.updateEvent("v1", userId, eventId, photoRequest))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1016));
        TimelineEvent unchanged = timelineEventRepository.findById(eventId).orElseThrow();
        assertThat(unchanged.getTitle()).isEqualTo("기존 제목");
        assertThat(unchanged.getMemo()).isNull();
        assertThat(timelineEventItemRepository.findByTimelineEventId(eventId)).isEmpty();

        UpdateTimelineEventRequest scalarRequest = request("scalar 제목", "scalar 메모", List.of());
        timelineEventEditService.updateEvent("v1", userId, eventId, scalarRequest);
        TimelineEvent scalarUpdated = timelineEventRepository.findById(eventId).orElseThrow();
        assertThat(scalarUpdated.getTitle()).isEqualTo("scalar 제목");
        assertThat(scalarUpdated.getMemo()).isEqualTo("scalar 메모");

        assertThat(timelineTaskService.releaseDateGuard(userId, DATE, taskHolder)).isTrue();
        timelineEventEditService.updateEvent("v1", userId, eventId, photoRequest);
        assertThat(timelineEventItemRepository.findByTimelineEventId(eventId)).hasSize(1);
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
}
