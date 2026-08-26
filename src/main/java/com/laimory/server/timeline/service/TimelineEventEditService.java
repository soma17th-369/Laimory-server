package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.dto.UpdateTimelineEventRequest;
import com.laimory.server.timeline.entity.TimelineEvent;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 타임라인 Event 편집 오케스트레이터.
 *
 * <p>통합 PATCH는 소유권 사전 확인과 입력 정규화를 먼저 수행한 뒤 별도
 * {@link TimelineEventEditTransactionService}에서 Event·memo·PHOTO Item·junction 변경을 하나의
 * transaction으로 commit한다. PROCESSING·SAVED 중에도 PATCH와 memo PUT은 허용된다.
 * 상세 필드 공통 규칙(title·subtitle·시간·memo)은 {@link TimelineEventInputRules}로, 사진 입력
 * 규칙은 {@link TimelineEventPhotoAddService}로 수동 Event 생성과 공유한다.
 *
 * <p>이벤트 없음·record 없음·비소유는 모두 404(-404)로 은닉한다. 사용자 입력 문자열·사진
 * 식별자는 로그에 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TimelineEventEditService {

    private final TimelineEventService timelineEventService;
    private final DailyRecordService dailyRecordService;
    private final TimelineEventEditTransactionService timelineEventEditTransactionService;
    private final TimelineEventPhotoAddService timelineEventPhotoAddService;

    /** Event 상세·optional memo·optional PHOTO append를 하나의 PATCH 계약으로 처리한다. */
    public void updateEvent(String applicationVersion, UUID subjectId, Long timelineEventId,
                            UpdateTimelineEventRequest request) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        findOwnedEvent(subjectId, timelineEventId);
        TimelineEventEditCommand command = requireValidCommand(request);
        timelineEventEditTransactionService.updateEvent(subjectId, timelineEventId, command);
    }

    /** 메모 전용 API. null·공백뿐은 제거, 그 외는 trim 없이 원문 저장한다. */
    @Transactional
    public void updateMemo(String applicationVersion, UUID subjectId, Long timelineEventId, String memo) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineEvent event = findOwnedEvent(subjectId, timelineEventId);
        event.updateMemo(TimelineEventInputRules.normalizeMemo(memo));
    }

    /** owner 검증은 입력 검증·DB mutation보다 먼저 수행한다. */
    private TimelineEvent findOwnedEvent(UUID subjectId, Long timelineEventId) {
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        dailyRecordService.findById(event.getDailyRecordId())
                .filter(owned -> owned.getSubjectId().equals(subjectId))
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        return event;
    }

    /** 모든 정적 입력을 검증·정규화하고 request rawId 중복은 첫 항목만 유지한다. */
    private TimelineEventEditCommand requireValidCommand(UpdateTimelineEventRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        String title = TimelineEventInputRules.requireValidTitle(request.title());
        String subtitle = TimelineEventInputRules.normalizeSubtitle(request.subtitle());
        TimelineEventInputRules.requireValidTimeRange(request.startAt(), request.endAt());
        String memo = request.memoPresent() ? TimelineEventInputRules.normalizeMemo(request.memo()) : null;
        List<TimelineEventPhotoAddService.PhotoToAdd> photos =
                timelineEventPhotoAddService.requireValidPhotos(request.photosToAdd());
        return new TimelineEventEditCommand(
                request.eventType(), title, subtitle, request.startAt(), request.endAt(),
                request.memoPresent(), memo, photos);
    }
}
