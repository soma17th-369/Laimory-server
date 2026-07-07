package com.laimory.server.timeline.service;

import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.TimelineEventSuggestionDto;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineItem;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 하루 타임라인 오케스트레이터. leaf 서비스 + 검증기를 합성한다(레포 직접 접근 금지).
 *
 * <p>쓰기(appendDailyTimeline)와 읽기(getDailyTimeline)는 같은 애그리거트(하루 타임라인)를 다루므로 한 서비스에 둔다.
 * 트랜잭션 경계는 메서드별로 지정한다(쓰기 vs readOnly). 읽기/쓰기가 서로 다른 이유로 갈라지면 그때 분리한다.
 */
@Service
@RequiredArgsConstructor
public class DailyTimelineService {

    private final DailyRecordService dailyRecordService;
    private final TimelineEventService timelineEventService;
    private final TimelineItemService timelineItemService;
    private final TimelineDraftSourceItemService timelineDraftSourceItemService;
    private final TimelineDraftEventSuggestionService timelineDraftEventSuggestionService;
    private final TimelineEventSuggestionValidator timelineEventSuggestionValidator;

    /**
     * 콜백 SUCCESS의 단일 finalize 트랜잭션 단위(all-or-nothing): events 검증 → daily record(없으면 DRAFT 생성)
     * → timeline_events/timeline_items 저장 → 소비한 draft 행 삭제. 어느 단계가 실패해도 전부 롤백된다.
     *
     * <p>이 메서드가 {@code @Transactional} 경계다 — 콜백 서비스는 별도 빈인 이 메서드를 Spring 프록시를 통해 호출해야
     * 트랜잭션이 활성화된다(같은 클래스 self-invocation이면 AOP를 안 거쳐 트랜잭션이 조용히 무효화됨).
     * {@code findOrCreateDraft}가 {@code REQUIRED}라 record 생성도 이 트랜잭션에 합류 → 롤백 시 record까지 사라진다.
     *
     * <p>아이템은 draft 행에서 그대로 복사한다(itemType/start/end/payload). payload는 이미 JsonNode이므로 재변환·ObjectMapper 없음.
     */
    @Transactional
    public Long appendDailyTimeline(Long userId, LocalDate recordDate, LocalDateTime recordAt, String recordTimezone,
                                    List<TimelineDraftSourceItem> draftRows,
                                    List<TimelineEventSuggestionDto> events) {
        // 1. 검증을 record 생성 전에 끝낸다(아래 영속 단계 전 DB 쓰기 없음). 위반은 IAE로 던져 트랜잭션 롤백 + 콜백이 FAILED 기록.
        timelineEventSuggestionValidator.validate(draftRows, events);

        Map<Long, TimelineDraftSourceItem> byItemId = new HashMap<>();
        for (TimelineDraftSourceItem row : draftRows) {
            byItemId.put(row.getTimelineDraftSourceItemId(), row);
        }

        // 2. record 생성/조회 + SAVED 가드. record_at/record_timezone은 클라가 보낸 값으로 PROCESSING task에서 전달된다(draft 행엔 저장 안 함).
        // 같은 날짜 재요청(append)이면 findOrCreateDraft가 record_at/record_timezone을 이번 finalize 값으로 갱신한다(마지막에 finalize된 값이 남음 — 콜백 순서 기준, POST 순서 아님).
        DailyRecord dailyRecord = dailyRecordService.findOrCreateDraft(userId, recordDate, recordAt, recordTimezone);
        if (dailyRecord.getStatus() == DailyRecordStatus.SAVED) {
            throw new IllegalStateException("daily record already SAVED: " + dailyRecord.getDailyRecordId());
        }
        Long dailyRecordId = dailyRecord.getDailyRecordId();

        // 3. 영속(검증 완료된 입력만 도달). 아이템은 draft 행에서 그대로 복사.
        for (TimelineEventSuggestionDto event : events) {
            TimelineEvent savedEvent = timelineEventService.save(
                    TimelineEvent.of(dailyRecordId, event.startAt(), event.endAt(),
                            event.title(), event.subtitle()));
            for (Long itemId : event.itemIds()) {
                TimelineDraftSourceItem src = byItemId.get(itemId);
                timelineItemService.save(
                        TimelineItem.of(savedEvent.getTimelineEventId(),
                                src.getItemType(), src.getRawId(),
                                src.getStartAt(), src.getEndAt(),
                                src.getPayload()));
            }
        }

        // 4. 소비한 staging 행 삭제(같은 트랜잭션 — 롤백 시 함께 살아남는다). source item + event suggestion 둘 다.
        String taskId = draftRows.get(0).getTaskId();
        timelineDraftSourceItemService.deleteByTaskId(taskId);
        timelineDraftEventSuggestionService.deleteByTaskId(taskId);

        return dailyRecordId;
    }

    /**
     * dailyRecordId로 그날 전체 타임라인을 조립해 반환한다. 이벤트/아이템 정렬은 leaf 서비스 쿼리가 보장.
     * payload는 저장본 그대로 통과한다(PHOTO photoUrl도 draft 저장 시 주입된 값 — 읽기 시점 변환 없음).
     */
    @Transactional(readOnly = true)
    public DailyTimelineResponse getDailyTimeline(Long dailyRecordId) {
        DailyRecord record = dailyRecordService.findById(dailyRecordId)
                .orElseThrow(() -> new IllegalStateException("daily record not found: " + dailyRecordId));

        List<TimelineEventResponse> eventResponses = new ArrayList<>();
        for (TimelineEvent event : timelineEventService.findByDailyRecordId(dailyRecordId)) {
            List<TimelineItemResponse> itemResponses = timelineItemService.findByTimelineEventId(event.getTimelineEventId())
                    .stream()
                    .map(TimelineItemResponse::from)
                    .toList();
            eventResponses.add(TimelineEventResponse.from(event, itemResponses));
        }

        return new DailyTimelineResponse(record.getRecordDate(), record.getEmotionType(), eventResponses);
    }
}
