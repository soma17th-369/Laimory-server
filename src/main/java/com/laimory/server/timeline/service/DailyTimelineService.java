package com.laimory.server.timeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.dto.CardSuggestionDto;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineItem;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 하루 타임라인 오케스트레이터. 3개 leaf 서비스를 합성한다(레포 직접 접근 금지).
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
    private final ObjectMapper objectMapper;

    /**
     * daily record(없으면 DRAFT 생성, 있으면 재사용)에 카드 제안과 채택된 source item을 저장한다.
     * 빈 카드·byItemId에 없는 itemId 검증을 record 생성(findOrCreateDraft) 전에 수행한다 —
     * findOrCreateDraft가 REQUIRES_NEW로 record를 별도 커밋하므로, 생성 후 검증이 실패하면 바깥 트랜잭션이
     * 롤백돼도 빈 DRAFT record가 고아로 남기 때문이다. SAVED 상태(async race: POST 체크 이후 SAVED 전환)는
     * record 조회 후 가드한다(found된 기존 record에만 해당돼 고아를 만들지 않음).
     * 그 외 상세 검증(시간 범위 등)은 상위(caller) validator 책임이다.
     * summary는 AI 입력 컨텍스트일 뿐이므로 의도적으로 저장하지 않는다.
     */
    @Transactional
    public Long appendDailyTimeline(Long userId, LocalDate recordDate,
                        List<SourceItemDto> sourceItems, List<CardSuggestionDto> cards) {
        // 1. 입력 검증을 record 생성 전에 끝낸다(아래 영속 단계 전 DB 쓰기 없음). 잘못된 콜백이 고아 DRAFT를 남기지 않도록.
        Map<Integer, SourceItemDto> byItemId = sourceItems.stream()
                .collect(Collectors.toMap(SourceItemDto::itemId, Function.identity()));
        for (CardSuggestionDto cardDto : cards) {
            if (cardDto.itemIds() == null || cardDto.itemIds().isEmpty()) {
                throw new IllegalArgumentException("card has no itemIds: " + cardDto.title());
            }
            for (Integer itemId : cardDto.itemIds()) {
                if (!byItemId.containsKey(itemId)) {
                    throw new IllegalArgumentException("unknown itemId in card: " + itemId);
                }
            }
        }

        // 2. 검증 통과 후 record 생성/조회 + SAVED 가드.
        DailyRecord dailyRecord = dailyRecordService.findOrCreateDraft(userId, recordDate);
        if (dailyRecord.getStatus() == DailyRecordStatus.SAVED) {
            throw new IllegalStateException("daily record already SAVED: " + dailyRecord.getDailyRecordId());
        }
        Long dailyRecordId = dailyRecord.getDailyRecordId();

        // 3. 영속(검증 완료된 입력만 도달). 여기서 남는 실패는 이벤트/아이템 insert의 DB 장애뿐.
        for (CardSuggestionDto cardDto : cards) {
            TimelineEvent savedEvent = timelineEventService.save(
                    TimelineEvent.of(dailyRecordId, cardDto.startAt(), cardDto.endAt(),
                            cardDto.title(), cardDto.subtitle()));
            for (Integer itemId : cardDto.itemIds()) {
                SourceItemDto src = byItemId.get(itemId);
                timelineItemService.save(
                        TimelineItem.of(savedEvent.getTimelineEventId(),
                                src.itemType(),
                                src.startAt(), src.endAt(),
                                objectMapper.valueToTree(src.payload())));
            }
        }

        return dailyRecordId;
    }

    /** dailyRecordId로 그날 전체 타임라인을 조립해 반환한다. 이벤트/아이템 정렬은 leaf 서비스 쿼리가 보장. */
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
