package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.dto.CreateTimelineEventRequest;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기존 하루 기록에 타임라인 Event를 수동 생성하는 use case. leaf 서비스를 합성한다(레포 직접 접근 금지).
 *
 * <p>소유 record 재확인과 Event insert를 하나의 트랜잭션이 소유한다. DailyRecord를 자동 생성하지
 * 않는다 — {@code recordAt}·{@code recordTimezone}을 이 요청만으로 정할 수 없다. 기존 편집 정책과 같이
 * DRAFT/SAVED 모두 허용하고, AI 결과 저장의 exact-start +10분 충돌 보정 없이 보낸 시각을 그대로 저장한다.
 *
 * <p>수동 Event의 AI 결과 전용 필드({@code question}/{@code place}/{@code address})는 모두 null이고
 * 연결 Item은 없다({@code items=[]}). 사진은 생성 후 기존 Event PATCH {@code photosToAdd}가 담당한다.
 * User Memory 갱신은 새로 enqueue하지 않는다 — SAVED Event 편집과 같은 현재 정책이다.
 */
@Service
@RequiredArgsConstructor
public class TimelineEventCreateService {

    private final DailyRecordService dailyRecordService;
    private final TimelineEventService timelineEventService;

    /**
     * 인증 사용자의 해당 날짜 하루 기록에 Event를 생성하고, 생성 ID를 포함한 Event 표현을 반환한다.
     *
     * @throws BusinessException 해당 날짜 record 없음·비소유 404 {@code -404}(존재를 구분해 주지 않는다)
     * @throws IllegalArgumentException 필수·길이·시간 범위 입력 오류(400 {@code -400})
     */
    @Transactional
    public TimelineEventResponse createEvent(String applicationVersion, UUID subjectId, LocalDate recordDate,
                                             CreateTimelineEventRequest request) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        DailyRecord record = dailyRecordService.findBySubjectIdAndRecordDate(subjectId, recordDate)
                .orElseThrow(() -> new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND));

        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.eventType() == null) {
            throw new IllegalArgumentException("eventType is required");
        }
        String title = TimelineEventInputRules.requireValidTitle(request.title());
        String subtitle = TimelineEventInputRules.normalizeSubtitle(request.subtitle());
        TimelineEventInputRules.requireValidTimeRange(request.startAt(), request.endAt());
        String memo = TimelineEventInputRules.normalizeMemo(request.memo());

        TimelineEvent event = TimelineEvent.of(record.getDailyRecordId(), request.eventType(),
                request.startAt(), request.endAt(), title, subtitle, null, null, null);
        event.updateMemo(memo);
        TimelineEvent saved = timelineEventService.save(event);
        return TimelineEventResponse.from(saved, List.of());
    }
}
