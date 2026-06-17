package com.laimory.server.timeline.persistence;

import com.laimory.server.timeline.dto.DailyTimelineResult;
import com.laimory.server.timeline.dto.TimelineCardResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 타임라인 조회 오케스트레이터. 3개 leaf 서비스를 합성한다(레포 직접 접근 금지).
 * 카드/아이템의 표시 순서 정렬은 leaf 서비스의 정렬 쿼리가 이미 보장한다.
 */
@Service
@RequiredArgsConstructor
public class DailyTimelineQueryService {

    private final DailyRecordService dailyRecordService;
    private final TimelineCardService timelineCardService;
    private final TimelineItemService timelineItemService;

    @Transactional(readOnly = true)
    public DailyTimelineResult getDailyTimeline(Long dailyRecordId) {
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

        return new DailyTimelineResult(record.getRecordDate(), record.getEmotionType(), cardResponses);
    }
}
