package com.laimory.server.timeline.service;

import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.dto.CardSuggestionDto;
import com.laimory.server.timeline.dto.DailyTimelineResponse;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.dto.TimelineCardResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineCard;
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
    private final TimelineCardService timelineCardService;
    private final TimelineItemService timelineItemService;

    /**
     * daily record(없으면 DRAFT 생성, 있으면 재사용)에 카드 제안과 채택된 source item을 저장한다.
     * 이 트랜잭션 경계는 SAVED 상태(async race: POST 체크 이후 SAVED 전환)·빈 카드·byItemId에 없는 itemId를
     * 마지막 방어선으로 재확인해 잘못된 콜백을 검증 실패로 처리한다.
     * 그 외 상세 검증(시간 범위 등)은 상위(caller) validator 책임이다.
     * summary는 AI 입력 컨텍스트일 뿐이므로 의도적으로 저장하지 않는다.
     */
    @Transactional
    public Long appendDailyTimeline(Long userId, LocalDate recordDate,
                        List<SourceItemDto> sourceItems, List<CardSuggestionDto> cards) {
        DailyRecord dailyRecord = dailyRecordService.findByUserIdAndRecordDate(userId, recordDate)
                .orElseGet(() -> dailyRecordService.save(DailyRecord.createDraft(userId, recordDate)));
        if (dailyRecord.getStatus() == DailyRecordStatus.SAVED) {
            throw new IllegalStateException("daily record already SAVED: " + dailyRecord.getId());
        }
        Long dailyRecordId = dailyRecord.getId();

        Map<Integer, SourceItemDto> byItemId = sourceItems.stream()
                .collect(Collectors.toMap(SourceItemDto::itemId, Function.identity()));

        for (CardSuggestionDto cardDto : cards) {
            if (cardDto.itemIds() == null || cardDto.itemIds().isEmpty()) {
                throw new IllegalArgumentException("card has no itemIds: " + cardDto.title());
            }
            TimelineCard savedCard = timelineCardService.save(
                    TimelineCard.of(dailyRecordId, cardDto.startAt(), cardDto.endAt(),
                            cardDto.title(), cardDto.subtitle()));
            for (Integer itemId : cardDto.itemIds()) {
                SourceItemDto src = byItemId.get(itemId);
                if (src == null) {
                    throw new IllegalArgumentException("unknown itemId in card: " + itemId);
                }
                timelineItemService.save(
                        TimelineItem.of(savedCard.getId(), src.startAt(), src.endAt(), src.payload()));
            }
        }

        return dailyRecordId;
    }

    /** dailyRecordId로 그날 전체 타임라인을 조립해 반환한다. 카드/아이템 정렬은 leaf 서비스 쿼리가 보장. */
    @Transactional(readOnly = true)
    public DailyTimelineResponse getDailyTimeline(Long dailyRecordId) {
        DailyRecord record = dailyRecordService.findById(dailyRecordId)
                .orElseThrow(() -> new IllegalStateException("daily record not found: " + dailyRecordId));

        List<TimelineCardResponse> cardResponses = new ArrayList<>();
        for (TimelineCard card : timelineCardService.findByDailyRecordId(dailyRecordId)) {
            List<TimelineItemResponse> itemResponses = timelineItemService.findByTimelineCardId(card.getId())
                    .stream()
                    .map(TimelineItemResponse::from)
                    .toList();
            cardResponses.add(TimelineCardResponse.from(card, itemResponses));
        }

        return new DailyTimelineResponse(record.getRecordDate(), record.getEmotionType(), cardResponses);
    }
}
