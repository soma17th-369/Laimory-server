package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.id.UuidV7;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoPayloadRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventRequest;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.photo.PhotoFilenames;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 타임라인 Event 편집 오케스트레이터.
 *
 * <p>통합 PATCH는 소유권·DRAFT 사전 확인과 입력 정규화를 먼저 수행한다. {@code photosToAdd}가 비어 있으면
 * date guard 없이 transactional writer를 호출해 기존 scalar 편집 동시성 계약을 유지한다. 사진이 있으면
 * 같은 날짜의 AI append·삭제·다른 사진 추가와 겹치지 않도록 {@code patch-photo-add:{operationId}} guard를
 * 선점한 뒤 writer를 호출하고, 성공·실패 모두 finally에서 자신의 guard만 best-effort 해제한다.
 *
 * <p>실제 PATCH DB 변경은 별도 {@link TimelineEventEditTransactionService}가 맡는다. 따라서 guard는
 * Event·memo·PHOTO Item·junction transaction이 commit된 뒤 해제된다. PROCESSING 중에도 사진 없는 PATCH와
 * memo PUT은 계속 허용된다.
 *
 * <p>이벤트 없음·record 없음·비소유는 모두 404(ERROR_0404)로 은닉하고 SAVED는 입력 검증보다 먼저
 * 409(ERROR_1003)로 거절한다. 사용자 입력 문자열·사진 식별자는 로그에 남기지 않는다.
 */
@Slf4j
@Service
public class TimelineEventEditService {

    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_SUBTITLE_LENGTH = 255;
    private static final int MAX_MEMO_LENGTH = 10_000;
    private static final int MAX_RAW_ID_LENGTH = 36;

    private final TimelineEventService timelineEventService;
    private final DailyRecordService dailyRecordService;
    private final TimelineTaskService timelineTaskService;
    private final TimelineEventEditTransactionService timelineEventEditTransactionService;
    private final int maxPhotoCount;

    public TimelineEventEditService(
            TimelineEventService timelineEventService,
            DailyRecordService dailyRecordService,
            TimelineTaskService timelineTaskService,
            TimelineEventEditTransactionService timelineEventEditTransactionService,
            @Value("${photo.upload.max-count}") int maxPhotoCount) {
        this.timelineEventService = timelineEventService;
        this.dailyRecordService = dailyRecordService;
        this.timelineTaskService = timelineTaskService;
        this.timelineEventEditTransactionService = timelineEventEditTransactionService;
        this.maxPhotoCount = maxPhotoCount;
    }

    /** Event 상세·optional memo·optional PHOTO append를 하나의 PATCH 계약으로 처리한다. */
    public void updateEvent(String applicationVersion, long userId, Long timelineEventId,
                            UpdateTimelineEventRequest request) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        OwnedEvent ownedEvent = findOwnedEditableEvent(userId, timelineEventId);
        TimelineEventEditCommand command = requireValidCommand(request);
        if (command.photosToAdd().isEmpty()) {
            timelineEventEditTransactionService.updateEvent(userId, timelineEventId, command);
            return;
        }

        String operationId = UuidV7.randomUuidV7().toString();
        String guardHolder = TimelineTaskService.photoAddGuardHolder(operationId);
        if (!timelineTaskService.claimDateGuard(userId, ownedEvent.record().getRecordDate(), guardHolder)) {
            throw new BusinessException(ExceptionType.RECORD_DATE_IN_PROGRESS);
        }
        try {
            timelineEventEditTransactionService.updateEvent(userId, timelineEventId, command);
        } finally {
            releaseDateGuardQuietly(userId, ownedEvent.record(), guardHolder, operationId);
        }
    }

    /** 메모 전용 API. null·공백뿐은 제거, 그 외는 trim 없이 원문 저장한다. */
    @Transactional
    public void updateMemo(String applicationVersion, long userId, Long timelineEventId, String memo) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineEvent event = findOwnedEditableEvent(userId, timelineEventId).event();
        event.updateMemo(normalizeMemo(memo));
    }

    /** owner/DRAFT 검증은 입력 검증·guard·DB mutation보다 먼저 수행한다. */
    private OwnedEvent findOwnedEditableEvent(long userId, Long timelineEventId) {
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        DailyRecord record = dailyRecordService.findById(event.getDailyRecordId())
                .filter(owned -> owned.getUserId() == userId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        if (record.getStatus() == DailyRecordStatus.SAVED) {
            throw new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
        }
        return new OwnedEvent(event, record);
    }

    /** 모든 정적 입력을 검증·정규화하고 request rawId 중복은 첫 항목만 유지한다. */
    private TimelineEventEditCommand requireValidCommand(UpdateTimelineEventRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        String title = requireValidTitle(request.title());
        String subtitle = normalizeSubtitle(request.subtitle());
        requireValidTimeRange(request.startAt(), request.endAt());
        String memo = request.memoPresent() ? normalizeMemo(request.memo()) : null;
        List<TimelineEventEditCommand.PhotoToAdd> photos = requireValidPhotos(request.photosToAdd());
        return new TimelineEventEditCommand(
                request.eventType(), title, subtitle, request.startAt(), request.endAt(),
                request.memoPresent(), memo, photos);
    }

    private List<TimelineEventEditCommand.PhotoToAdd> requireValidPhotos(
            List<UpdateTimelineEventPhotoRequest> photosToAdd) {
        if (photosToAdd == null) {
            throw new IllegalArgumentException("photosToAdd must not be null");
        }
        if (photosToAdd.size() > maxPhotoCount) {
            throw new BusinessException(ExceptionType.PHOTO_COUNT_EXCEEDED, maxPhotoCount);
        }

        Set<String> seenRawIds = new LinkedHashSet<>();
        List<TimelineEventEditCommand.PhotoToAdd> deduped = new ArrayList<>();
        for (int i = 0; i < photosToAdd.size(); i++) {
            UpdateTimelineEventPhotoRequest photo = photosToAdd.get(i);
            if (photo == null) {
                throw new IllegalArgumentException("photosToAdd element is null: index=" + i);
            }
            if (isBlank(photo.rawId())) {
                throw new IllegalArgumentException("photo requires rawId: index=" + i);
            }
            if (photo.rawId().length() > MAX_RAW_ID_LENGTH) {
                throw new IllegalArgumentException("photo rawId is too long: index=" + i);
            }
            UpdateTimelineEventPhotoPayloadRequest payload = photo.payload();
            if (payload == null) {
                throw new IllegalArgumentException("photo requires payload: index=" + i);
            }
            PhotoFilenames.requireValid(payload.filename());
            if (isBlank(payload.clientPhotoUri())) {
                throw new IllegalArgumentException("photo requires clientPhotoUri: index=" + i);
            }

            TimelineEventEditCommand.PhotoToAdd commandPhoto = new TimelineEventEditCommand.PhotoToAdd(
                    photo.rawId(), photo.startAt(), photo.endAt(), payload.filename(), payload.clientPhotoUri(),
                    payload.latitude(), payload.longitude());
            if (seenRawIds.add(photo.rawId())) {
                deduped.add(commandPhoto);
            }
        }
        if (deduped.size() < photosToAdd.size()) {
            log.warn("dropped duplicate rawId photos in Event PATCH: dropped={} kept={}",
                    photosToAdd.size() - deduped.size(), deduped.size());
        }
        return List.copyOf(deduped);
    }

    private String requireValidTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        String stripped = title.strip();
        if (stripped.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title is too long: length=" + stripped.length());
        }
        return stripped;
    }

    private String normalizeSubtitle(String subtitle) {
        if (subtitle == null || subtitle.isBlank()) {
            return null;
        }
        String stripped = subtitle.strip();
        if (stripped.length() > MAX_SUBTITLE_LENGTH) {
            throw new IllegalArgumentException("subtitle is too long: length=" + stripped.length());
        }
        return stripped;
    }

    private void requireValidTimeRange(LocalDateTime startAt, LocalDateTime endAt) {
        if (startAt == null) {
            throw new IllegalArgumentException("startAt is required");
        }
        if (endAt != null && endAt.isBefore(startAt)) {
            throw new IllegalArgumentException("endAt is before startAt");
        }
    }

    private String normalizeMemo(String memo) {
        if (memo == null || memo.isBlank()) {
            return null;
        }
        if (memo.length() > MAX_MEMO_LENGTH) {
            throw new IllegalArgumentException("memo is too long: length=" + memo.length());
        }
        return memo;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void releaseDateGuardQuietly(long userId, DailyRecord record, String holder, String operationId) {
        try {
            timelineTaskService.releaseDateGuard(userId, record.getRecordDate(), holder);
        } catch (RuntimeException e) {
            log.warn("date guard release failed (TTL로 자연 해제 예정): operationId={} detail={}",
                    operationId, e.getMessage());
        }
    }

    private record OwnedEvent(TimelineEvent event, DailyRecord record) {
    }
}
