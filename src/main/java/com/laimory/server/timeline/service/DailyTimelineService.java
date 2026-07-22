package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DailyTimelinesResponse;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    /** 인증 사용자의 모든 일일 기록 graph를 recordDate·ID 내림차순으로 반환한다. */
    @Transactional(readOnly = true)
    public DailyTimelinesResponse getDailyTimelines(String applicationVersion, long userId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        List<DailyRecord> records = dailyRecordService.findByUserIdOrderByRecordDateDescDailyRecordIdDesc(userId);
        return new DailyTimelinesResponse(assembleTimelines(records));
    }

    /** 인증 사용자가 소유한 일일 기록 한 건의 graph를 반환한다. 없음·비소유는 같은 404로 은닉한다. */
    @Transactional(readOnly = true)
    public DailyTimelineResponse getDailyTimeline(String applicationVersion, long userId, Long dailyRecordId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        DailyRecord record = dailyRecordService.findByDailyRecordIdAndUserId(dailyRecordId, userId)
                .orElseThrow(() -> new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND));
        return assembleTimelines(List.of(record)).get(0);
    }

    /**
     * SUCCESS polling 전용 ID 조회. polling 선검증과 이 권위 재조회 사이 record가 삭제돼도 500이 아니라
     * DRAFT_RESULT_NOT_FOUND 404로 수렴한다. 이 조회부터 하위 graph 조립까지 한 read-only transaction이다.
     */
    @Transactional(readOnly = true)
    public DailyTimelineResponse getDailyTimeline(Long dailyRecordId) {
        DailyRecord record = dailyRecordService.findById(dailyRecordId)
                .orElseThrow(() -> new BusinessException(ExceptionType.DRAFT_RESULT_NOT_FOUND));
        return assembleTimelines(List.of(record)).get(0);
    }

    /**
     * 소유권이 확인된 record 목록을 최대 Event→junction→Item 3번의 bulk 조회로 조립한다. 입력 record 순서가
     * 응답 순서이고, Event는 leaf 쿼리 순서, Item은 startAt(null 먼저)·ID 오름차순이다.
     */
    private List<DailyTimelineResponse> assembleTimelines(List<DailyRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        List<Long> dailyRecordIds = records.stream().map(DailyRecord::getDailyRecordId).toList();
        List<TimelineEvent> events = timelineEventService.findByDailyRecordIds(dailyRecordIds);
        List<Long> eventIds = events.stream().map(TimelineEvent::getTimelineEventId).toList();
        List<TimelineEventItem> links = timelineEventItemService.findByTimelineEventIds(eventIds);
        Map<Long, TimelineItem> itemsById = timelineItemService.findByIds(
                        links.stream().map(TimelineEventItem::getTimelineItemId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(TimelineItem::getTimelineItemId, Function.identity()));
        Map<Long, List<TimelineEventItem>> linksByEventId = links.stream()
                .collect(Collectors.groupingBy(TimelineEventItem::getTimelineEventId));

        Map<Long, List<TimelineEventResponse>> eventResponsesByDailyRecordId = new HashMap<>();
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
            eventResponsesByDailyRecordId
                    .computeIfAbsent(event.getDailyRecordId(), ignored -> new ArrayList<>())
                    .add(TimelineEventResponse.from(event, itemResponses));
        }

        return records.stream()
                .map(record -> new DailyTimelineResponse(
                        record.getDailyRecordId(),
                        record.getRecordDate(),
                        record.getEmotionType(),
                        eventResponsesByDailyRecordId.getOrDefault(record.getDailyRecordId(), List.of())))
                .toList();
    }
}
