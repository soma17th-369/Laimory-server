package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.AiTimelineDispatchRequest;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.dto.TimelineWindowDto;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineDraftTask;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Callback-Token + direct-write draft 흐름 end-to-end 검증(실 MySQL+Redis). 수동 curl을 대체한다.
 * 토큰은 불투명하므로 디스패처를 spy해 dispatch body의 raw 토큰을 캡처한 뒤 콜백을 호출한다.
 * AI의 final direct-write(Event/Item/junction INSERT + accepted source DELETE)를 시뮬레이션하고
 * 콜백은 상태만 전달한다 — 서버가 결과를 재조립·재저장하지 않음을 실 인프라에서 확인한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineCallbackTokenIntegrationTest {

    @Autowired
    private TimelineDraftTaskService draftTaskService;
    @Autowired
    private TimelineCallbackService callbackService;
    @Autowired
    private TimelineTaskService taskService;
    @Autowired
    private TimelineDraftTaskPollingService pollingService;
    @Autowired
    private DailyRecordService dailyRecordService;
    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private TimelineDraftSourceItemService draftSourceItemService;
    @Autowired
    private TimelineEventRepository timelineEventRepository;
    @Autowired
    private TimelineItemRepository timelineItemRepository;
    @Autowired
    private TimelineEventItemRepository timelineEventItemRepository;
    @Autowired
    private RedisGateway redis;

    @MockitoSpyBean
    private TimelineAiDispatcher dispatcher;

    private static final String VERSION = "v1";
    private static final long USER_ID = 7L;
    private static final String ZONE = "Asia/Seoul";
    // 다른 데이터와 충돌하지 않을 고정 날짜 — 클라 선택 날짜로 요청에 명시 전송한다(서버 파생 없음).
    private static final LocalDate DATE = LocalDate.of(2000, 1, 1);
    private static final LocalDateTime RECORD_AT = LocalDateTime.of(2000, 1, 1, 12, 0); // 실제 작성 시각 메타데이터
    private static final TimelineWindowDto WINDOW = new TimelineWindowDto(
            LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2000, 1, 2, 0, 0));
    private static final String LEGACY_DATE_GUARD_KEY = "timeline:date-guard:" + USER_ID + ":" + DATE;

    private final List<String> createdTaskIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanUp() {
        // Item은 record FK cascade 대상이 아니므로 junction 경유로 먼저 지운다(테스트 잔존 방지).
        dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE).ifPresent(record -> {
            List<Long> eventIds = timelineEventRepository
                    .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId()).stream()
                    .map(TimelineEvent::getTimelineEventId)
                    .toList();
            if (!eventIds.isEmpty()) {
                List<Long> itemIds = timelineEventItemRepository.findByTimelineEventIdIn(eventIds).stream()
                        .map(TimelineEventItem::getTimelineItemId)
                        .distinct()
                        .toList();
                dailyRecordRepository.deleteById(record.getDailyRecordId()); // events/junction은 DB FK cascade
                timelineItemRepository.deleteAllByIdInBatch(itemIds);
            } else {
                dailyRecordRepository.deleteById(record.getDailyRecordId());
            }
        });
        createdTaskIds.forEach(id -> {
            draftSourceItemService.deleteByTaskId(id);
            redis.delete("timeline:draft-task:" + id);
            redis.delete("timeline:callback-token-uses:" + id);
            // redis는 RedisGateway → 환경 prefix는 내부에서 자동 부착(논리 키만 넘김).
        });
        createdTaskIds.clear();
        // 제거된 guard의 stale-key 회귀 fixture만 정리한다. 운영 코드는 이 key를 읽거나 지우지 않는다.
        redis.delete(LEGACY_DATE_GUARD_KEY);
    }

    @Test
    void validToken_recordPreCreated_directWriteThenCallback_success_andReplayRejected1012() {
        String taskId = draftTaskService.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources());
        createdTaskIds.add(taskId);

        // POST 시점에 DailyRecord가 선생성되고 source 행이 MySQL에 저장돼 있다(구조 변경 핵심).
        DailyRecord record = dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE).orElseThrow();
        assertThat(draftSourceItemService.findByTaskId(taskId)).isNotEmpty();

        // 디스패처 spy로 dispatch body 캡처 — taskId·dailyRecordId·raw 토큰이 실려 나간다.
        AiTimelineDispatchRequest dispatched = capturedRequest();
        assertThat(dispatched.taskId()).isEqualTo(taskId);
        assertThat(dispatched.dailyRecordId()).isEqualTo(record.getDailyRecordId());
        String token = dispatched.callbackToken();

        // Redis에는 원문 토큰이 없고 해시만 보관된다. task owner·dailyRecordId는 PROCESSING부터 저장된다.
        String rawJson = redis.get("timeline:draft-task:" + taskId);
        assertThat(rawJson).doesNotContain(token);
        TimelineDraftTask stored = taskService.find(taskId).orElseThrow();
        assertThat(stored.callbackTokenHash()).isNotNull().isNotEqualTo(token);
        assertThat(CallbackTokens.matches(token, stored.callbackTokenHash())).isTrue();
        assertThat(stored.userId()).isEqualTo(USER_ID);
        assertThat(stored.dailyRecordId()).isEqualTo(record.getDailyRecordId());

        // AI 시뮬(direct-write): final Event/Item/junction commit + accepted source 삭제.
        simulateAiDirectWrite(taskId, record.getDailyRecordId());

        // 유효 토큰으로 콜백(바디엔 status만) → Redis SUCCESS 전이만 수행.
        callbackService.handleCallback(VERSION, taskId, token, success());

        DraftTaskStatusResponse status = pollingService.poll(VERSION, USER_ID, taskId);
        assertThat(status.status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(status.result().events()).hasSize(1);
        assertThat(status.result().events().get(0).items()).hasSize(1);
        // 종결 후에도 해시와 owner가 보존된다(terminal 재콜백 token-first 검증 + 폴링 소유권 대조용).
        TimelineDraftTask terminal = taskService.find(taskId).orElseThrow();
        assertThat(terminal.callbackTokenHash()).isEqualTo(stored.callbackTokenHash());
        assertThat(terminal.userId()).isEqualTo(USER_ID);

        // 같은 token 재사용은 terminal 상태를 다시 쓰지 않고 인증 게이트에서 1012로 거절한다.
        assertThatThrownBy(() -> callbackService.handleCallback(VERSION, taskId, token, success()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1012));
        DraftTaskStatusResponse afterReplay = pollingService.poll(VERSION, USER_ID, taskId);
        assertThat(afterReplay.status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(afterReplay.result().events()).hasSameSizeAs(status.result().events());
    }

    @Test
    void wrongToken_rejected401_recordAndSourcesPreserved() {
        String taskId = draftTaskService.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources());
        createdTaskIds.add(taskId);

        assertThatThrownBy(() -> callbackService.handleCallback(VERSION, taskId, "wrong-token", success()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1002));

        // task는 여전히 PROCESSING, 선생성된 empty DRAFT와 source는 보존(final write는 AI 소유라 없음).
        assertThat(taskService.find(taskId).orElseThrow().status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).isPresent();
        assertThat(draftSourceItemService.findByTaskId(taskId)).isNotEmpty();
    }

    @Test
    void failedCallback_keepsEmptyDraftAndSources_forRetryAndCleanup() {
        String taskId = draftTaskService.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources());
        createdTaskIds.add(taskId);
        String token = capturedRequest().callbackToken();

        callbackService.handleCallback(VERSION, taskId, token,
                new DraftTaskCallbackRequest(TaskStatus.FAILED, -1008, "inference failed"));

        DraftTaskStatusResponse status = pollingService.poll(VERSION, USER_ID, taskId);
        assertThat(status.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(status.error()).isEqualTo(-1008);
        // empty DRAFT는 삭제하지 않는다 — 같은 날짜 재시도가 재사용한다. source는 retention cleanup 대상.
        assertThat(dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE)).isPresent();
        assertThat(draftSourceItemService.findByTaskId(taskId)).isNotEmpty();

        // FAILED token도 한 번만 쓸 수 있고, 재사용 거절은 failure/source 상태를 바꾸지 않는다.
        assertThatThrownBy(() -> callbackService.handleCallback(VERSION, taskId, token,
                new DraftTaskCallbackRequest(TaskStatus.FAILED, -1008, "retry")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1012));
        assertThat(taskService.find(taskId).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(draftSourceItemService.findByTaskId(taskId)).isNotEmpty();

        // 날짜별 admission이 없으므로 FAILED terminal 뒤 같은 날짜 재시도도 즉시 진행한다.
        String retryTaskId = draftTaskService.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW,
                List.of(new SourceItemDto(ItemType.PHOTO, "raw-p2", DATE.atTime(10, 0), null,
                        new PhotoPayload("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "content://p2",
                                37.5, 127.0, null, null))));
        createdTaskIds.add(retryTaskId);
        assertThat(retryTaskId).isNotEqualTo(taskId);
        assertThat(taskService.find(retryTaskId).orElseThrow().status()).isEqualTo(TaskStatus.PROCESSING);
        verify(dispatcher, times(2)).dispatch(any(AiTimelineDispatchRequest.class));
    }

    @Test
    void aiRestartAfterCommitBeforeCallback_taskStaysProcessing_graphPersists_fullRetryRejected1013() {
        // MVP 한계 failure test(§계획 12): commit 후 callback 전 AI 종료 — 살아있는 재시도 주체가 없다.
        String taskId = draftTaskService.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW, sources());
        createdTaskIds.add(taskId);
        DailyRecord record = dailyRecordService.findByUserIdAndRecordDate(USER_ID, DATE).orElseThrow();

        // AI가 commit까지 마쳤지만 callback은 오지 않았다.
        simulateAiDirectWrite(taskId, record.getDailyRecordId());

        // 원 task는 PROCESSING인 채 남고(운영에선 TTL 1h 만료), final graph는 commit대로 유지된다.
        assertThat(taskService.find(taskId).orElseThrow().status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(timelineEventRepository
                .findByDailyRecordIdOrderByStartAtAscTimelineEventIdAsc(record.getDailyRecordId())).hasSize(1);

        // 같은 날짜의 PROCESSING task와 배포 전에 남은 legacy guard key가 있어도 재시도 admission을
        // 막지 않는다. 동일 source는 저장된 rawId 전량 제외로 ERROR_1013이다.
        redis.set(LEGACY_DATE_GUARD_KEY, "legacy-holder", Duration.ofHours(1));
        assertThatThrownBy(() -> draftTaskService.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW,
                sources()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(-1013));

        // 일부만 겹치는 재시도는 신규 rawId만으로 진행된다 — 새 task의 SUCCESS 폴링이 기존 커밋분까지 반환해
        // 실질 복구 경로가 된다.
        String retryTaskId = draftTaskService.createDraftTask(VERSION, USER_ID, DATE, RECORD_AT, ZONE, WINDOW,
                List.of(sources().get(0),
                        new SourceItemDto(ItemType.PHOTO, "raw-p2", DATE.atTime(10, 0), null,
                                new PhotoPayload("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "content://p2",
                                        37.5, 127.0, null, null))));
        createdTaskIds.add(retryTaskId);
        assertThat(draftSourceItemService.findByTaskId(retryTaskId))
                .extracting(TimelineDraftSourceItem::getRawId)
                .containsExactly("raw-p2");
        assertThat(taskService.find(retryTaskId).orElseThrow().status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(redis.get(LEGACY_DATE_GUARD_KEY)).isEqualTo("legacy-holder");
    }

    /** AI direct-write 시뮬: final Event 1건 + source별 Item/junction INSERT 후 accepted source 삭제(한 커밋처럼). */
    private void simulateAiDirectWrite(String taskId, long dailyRecordId) {
        TimelineEvent event = timelineEventRepository.save(
                TimelineEvent.of(dailyRecordId, TimelineEventType.UNKNOWN, DATE.atTime(9, 0), null, "아침", null));
        for (TimelineDraftSourceItem row : draftSourceItemService.findByTaskId(taskId)) {
            TimelineItem item = timelineItemRepository.save(TimelineItem.of(
                    row.getItemType(), row.getRawId(), row.getStartAt(), row.getEndAt(), row.getPayload()));
            timelineEventItemRepository.save(TimelineEventItem.of(event.getTimelineEventId(), item.getTimelineItemId()));
        }
        draftSourceItemService.deleteByTaskId(taskId);
    }

    private AiTimelineDispatchRequest capturedRequest() {
        ArgumentCaptor<AiTimelineDispatchRequest> captor = ArgumentCaptor.forClass(AiTimelineDispatchRequest.class);
        verify(dispatcher).dispatch(captor.capture());
        return captor.getValue();
    }

    private DraftTaskCallbackRequest success() {
        return new DraftTaskCallbackRequest(TaskStatus.SUCCESS, null, null);
    }

    private List<SourceItemDto> sources() {
        return List.of(new SourceItemDto(ItemType.PHOTO, "raw-p1", DATE.atTime(9, 0), null,
                new PhotoPayload("0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg", "content://p", 37.5, 127.0, null, null)));
    }
}
