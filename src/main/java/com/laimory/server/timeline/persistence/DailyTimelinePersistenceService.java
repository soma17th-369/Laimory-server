package com.laimory.server.timeline.persistence;

import com.laimory.server.timeline.dto.CardProposalDto;
import com.laimory.server.timeline.dto.SourceItemDto;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 타임라인 영속 오케스트레이터. 3개 leaf 서비스를 합성한다(레포 직접 접근 금지).
 *
 * <p>같은 날짜 daily record가 이미 있으면 그대로 재사용하고 카드/아이템을 append 한다(용어 사전의 "추가 데이터 처리").
 */
@Service
@RequiredArgsConstructor
public class DailyTimelinePersistenceService {

    private final DailyRecordService dailyRecordService;
    private final TimelineCardService timelineCardService;
    private final TimelineItemService timelineItemService;

    /**
     * daily record(없으면 DRAFT 생성, 있으면 재사용)에 카드 제안과 채택된 source item을 저장한다.
     * 입력(itemIds·시간 범위·빈 카드 등)은 상위 validator가 이미 검증했다고 가정한다 - 여기서 재검증하지 않는다.
     * SAVED 상태 거부도 상위(caller) 책임이며 여기서 재확인하지 않는다.
     * summary는 AI 입력 컨텍스트일 뿐이므로 의도적으로 저장하지 않는다.
     */
    @Transactional
    public Long persist(Long userId, LocalDate recordDate,
                        List<SourceItemDto> sourceItems, List<CardProposalDto> cards) {
        DailyRecord dailyRecord = dailyRecordService.findByUserIdAndRecordDate(userId, recordDate)
                .orElseGet(() -> dailyRecordService.save(DailyRecord.createDraft(userId, recordDate)));
        Long dailyRecordId = dailyRecord.getId();

        Map<Integer, SourceItemDto> byItemId = sourceItems.stream()
                .collect(Collectors.toMap(SourceItemDto::itemId, Function.identity()));

        for (CardProposalDto cardDto : cards) {
            TimelineCard savedCard = timelineCardService.save(
                    TimelineCard.of(dailyRecordId, cardDto.startAt(), cardDto.endAt(),
                            cardDto.title(), cardDto.subtitle()));
            for (Integer itemId : cardDto.itemIds()) {
                SourceItemDto src = byItemId.get(itemId);
                timelineItemService.save(
                        TimelineItem.of(savedCard.getId(), src.startAt(), src.endAt(), src.payload()));
            }
        }

        return dailyRecordId;
    }
}
