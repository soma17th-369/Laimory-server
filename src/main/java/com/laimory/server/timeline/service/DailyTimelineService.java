package com.laimory.server.timeline.service;

import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 하루 타임라인 읽기 오케스트레이터. leaf 서비스를 합성한다(레포 직접 접근 금지).
 *
 * <p>final write(Event/Item/junction 저장)는 AI가 direct-write로 소유하므로 서버에는 쓰기 경로가 없다 —
 * 이 서비스는 junction을 경유해 하루 전체를 조립하는 읽기 전용이다. 같은 Item이 여러 Event에 연결될 수
 * 있으므로(N:M) 같은 {@code timelineItemId}가 여러 Event의 {@code items}에 반복될 수 있다(응답 shape 유지,
 * Android 수용 확인됨).
 */
@Service
@RequiredArgsConstructor
public class DailyTimelineService {

    private final DailyRecordService dailyRecordService;
    private final TimelineEventService timelineEventService;
    private final TimelineEventItemService timelineEventItemService;
    private final TimelineItemService timelineItemService;

    /**
     * dailyRecordId로 그날 전체 타임라인을 조립해 반환한다. 이벤트 정렬은 leaf 쿼리(start_at, id 오름차순),
     * 아이템 정렬은 junction으로 로드한 뒤 같은 기준으로 메모리 정렬한다(start_at null 먼저 — 기존 SQL
     * NULLS-FIRST 동작 보존). payload는 저장본 그대로 통과한다(PHOTO photoUrl도 draft 저장 시 주입된 값).
     */
    @Transactional(readOnly = true)
    public DailyTimelineResponse getDailyTimeline(Long dailyRecordId) {
        DailyRecord record = dailyRecordService.findById(dailyRecordId)
                .orElseThrow(() -> new IllegalStateException("daily record not found: " + dailyRecordId));

        List<TimelineEvent> events = timelineEventService.findByDailyRecordId(dailyRecordId);
        List<Long> eventIds = events.stream().map(TimelineEvent::getTimelineEventId).toList();
        List<TimelineEventItem> links = timelineEventItemService.findByTimelineEventIds(eventIds);
        Map<Long, TimelineItem> itemsById = timelineItemService.findByIds(
                        links.stream().map(TimelineEventItem::getTimelineItemId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(TimelineItem::getTimelineItemId, Function.identity()));
        Map<Long, List<TimelineEventItem>> linksByEventId = links.stream()
                .collect(Collectors.groupingBy(TimelineEventItem::getTimelineEventId));

        List<TimelineEventResponse> eventResponses = new ArrayList<>();
        for (TimelineEvent event : events) {
            List<TimelineItemResponse> itemResponses = linksByEventId
                    .getOrDefault(event.getTimelineEventId(), List.of())
                    .stream()
                    .map(link -> itemsById.get(link.getTimelineItemId()))
                    .sorted(Comparator.comparing(TimelineItem::getStartAt,
                                    Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(TimelineItem::getTimelineItemId))
                    .map(TimelineItemResponse::from)
                    .toList();
            eventResponses.add(TimelineEventResponse.from(event, itemResponses));
        }

        return new DailyTimelineResponse(record.getDailyRecordId(), record.getRecordDate(),
                record.getEmotionType(), eventResponses);
    }
}
