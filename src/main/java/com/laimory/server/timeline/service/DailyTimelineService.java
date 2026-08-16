package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.DailyTimelinesResponse;
import com.laimory.server.timeline.dto.MonthlyDailyRecordResponse;
import com.laimory.server.timeline.dto.MonthlyDailyRecordListResponse;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.UUID;
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

    /** MySQL {@code DATE}가 지원하는 연도 범위 — API 경계도 같은 값으로 제한한다. */
    private static final int MIN_YEAR = 1000;
    private static final int MAX_YEAR = 9999;

    private final DailyRecordService dailyRecordService;
    private final TimelineEventService timelineEventService;
    private final TimelineEventItemService timelineEventItemService;
    private final TimelineItemService timelineItemService;

    /** 인증 사용자의 모든 일일 기록 graph를 recordDate·ID 내림차순으로 반환한다. */
    @Transactional(readOnly = true)
    public DailyTimelinesResponse getDailyTimelines(String applicationVersion, UUID subjectId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        List<DailyRecord> records = dailyRecordService.findBySubjectIdOrderByRecordDateDescDailyRecordIdDesc(subjectId);
        return new DailyTimelinesResponse(assembleTimelines(records));
    }

    /** 인증 사용자가 소유한 일일 기록 한 건의 graph를 반환한다. 없음·비소유는 같은 404로 은닉한다. */
    @Transactional(readOnly = true)
    public DailyTimelineResponse getDailyTimeline(String applicationVersion, UUID subjectId, Long dailyRecordId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        DailyRecord record = dailyRecordService.findByDailyRecordIdAndSubjectId(dailyRecordId, subjectId)
                .orElseThrow(() -> new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND));
        return assembleTimelines(List.of(record)).get(0);
    }

    /** 인증 사용자의 선택 날짜에 해당하는 일일 기록 graph를 반환한다. */
    @Transactional(readOnly = true)
    public DailyTimelineResponse getDailyTimeline(String applicationVersion, UUID subjectId,
                                                  LocalDate recordDate) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        DailyRecord record = dailyRecordService.findBySubjectIdAndRecordDate(subjectId, recordDate)
                .orElseThrow(() -> new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND));
        return assembleTimelines(List.of(record)).get(0);
    }

    /** 인증 사용자가 소유한 Event와 연결 Item을 반환한다. 없음·부모 없음·비소유는 같은 404로 은닉한다. */
    @Transactional(readOnly = true)
    public TimelineEventResponse getTimelineEvent(String applicationVersion, UUID subjectId,
                                                  Long timelineEventId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        dailyRecordService.findById(event.getDailyRecordId())
                .filter(record -> record.getSubjectId().equals(subjectId))
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        return assembleEventResponses(List.of(event)).get(0);
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
     * 캘린더 월별 경량 조회. 요청 subject가 소유한 해당 월의 DRAFT/SAVED record를 날짜 오름차순으로
     * {@code recordDate}·{@code emotionType}만 담아 반환한다. Event·junction·Item graph는 읽지 않으며
     * 기록이 없는 월은 404가 아니라 빈 배열이다.
     *
     * @throws IllegalArgumentException {@code year}가 1000~9999(MySQL {@code DATE} 지원 범위) 밖이거나
     *                                  {@code month}가 1~12 밖일 때(400 {@code -400})
     */
    @Transactional(readOnly = true)
    public MonthlyDailyRecordListResponse getMonthlyDailyRecords(String applicationVersion, UUID subjectId,
                                                              int year, int month) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw new IllegalArgumentException("year must be between 1000 and 9999: " + year);
        }
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month must be between 1 and 12: " + month);
        }
        YearMonth yearMonth = YearMonth.of(year, month);
        List<DailyRecord> records = dailyRecordService.findBySubjectIdAndRecordDateBetweenOrderByRecordDateAsc(
                subjectId, yearMonth.atDay(1), yearMonth.atEndOfMonth());
        return new MonthlyDailyRecordListResponse(records.stream()
                .map(record -> new MonthlyDailyRecordResponse(record.getRecordDate(), record.getEmotionType()))
                .toList());
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
        List<TimelineEventResponse> eventResponses = assembleEventResponses(events);

        Map<Long, List<TimelineEventResponse>> eventResponsesByDailyRecordId = new HashMap<>();
        for (int index = 0; index < events.size(); index++) {
            eventResponsesByDailyRecordId
                    .computeIfAbsent(events.get(index).getDailyRecordId(), ignored -> new ArrayList<>())
                    .add(eventResponses.get(index));
        }

        return records.stream()
                .map(record -> new DailyTimelineResponse(
                        record.getDailyRecordId(),
                        record.getRecordDate(),
                        record.getStatus(),
                        record.getEmotionType(),
                        eventResponsesByDailyRecordId.getOrDefault(record.getDailyRecordId(), List.of())))
                .toList();
    }

    /** 입력 Event 순서를 유지하며 junction을 경유한 Item 응답을 조립한다. */
    private List<TimelineEventResponse> assembleEventResponses(List<TimelineEvent> events) {
        List<Long> eventIds = events.stream().map(TimelineEvent::getTimelineEventId).toList();
        List<TimelineEventItem> links = timelineEventItemService.findByTimelineEventIds(eventIds);
        Map<Long, TimelineItem> itemsById = timelineItemService.findByIds(
                        links.stream().map(TimelineEventItem::getTimelineItemId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(TimelineItem::getTimelineItemId, Function.identity()));
        Map<Long, List<TimelineEventItem>> linksByEventId = links.stream()
                .collect(Collectors.groupingBy(TimelineEventItem::getTimelineEventId));

        return events.stream()
                .map(event -> TimelineEventResponse.from(event, linksByEventId
                        .getOrDefault(event.getTimelineEventId(), List.of())
                        .stream()
                        .map(link -> itemsById.get(link.getTimelineItemId()))
                        .sorted(Comparator.comparing(TimelineItem::getStartAt,
                                        Comparator.nullsFirst(Comparator.naturalOrder()))
                                .thenComparing(TimelineItem::getTimelineItemId))
                        .map(TimelineItemResponse::from)
                        .toList()))
                .toList();
    }
}
