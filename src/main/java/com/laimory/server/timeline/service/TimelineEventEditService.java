package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 타임라인 Event 편집(수정·메모) 오케스트레이터. leaf 서비스를 합성한다(레포 직접 접근 금지).
 *
 * <p>공통 순서: 조회 → 소유권/상태 검증 → 입력 검증 → 변경 → 응답 조립. 각 public 메서드가
 * {@code @Transactional} 경계다 — 컨트롤러가 별도 빈인 이 메서드를 Spring 프록시를 통해 호출해야
 * 트랜잭션이 활성화된다(같은 클래스 self-invocation이면 AOP를 안 거쳐 조용히 무효화됨).
 * 조회한 관리 엔티티의 변경은 커밋 시 dirty checking으로 flush된다(repo.save 호출 없음).
 *
 * <p>소유권: {@code TimelineEvent}에는 userId가 없어 {@code event.dailyRecordId → DailyRecord.userId}로
 * 검증한다. 이벤트 없음·record 없음·비소유는 전부 404(ERROR_0404)로 은닉한다(존재 여부 비노출).
 * SAVED record는 모든 편집(입력 검증 포함) 전에 409(ERROR_1003)로 거절한다 — DRAFT에서만 수정한다.
 * PROCESSING(AI 진행) 중에도 편집은 허용한다 — AI finalize는 기존 Event를 건드리지 않고 append만
 * 하므로 날짜 guard는 확인하지 않는다.
 *
 * <p>입력 검증은 프로그래밍 방식(IAE → 400)으로 생성 경로({@link TimelineEventSuggestionValidator})의
 * 규칙(title 필수, startAt 필수, endAt은 있으면 startAt 이상)과 정렬한다. 길이 상한(255자)은 편집 경로의
 * 의도적 추가 규칙이다 — 생성 경로 입력은 AI 산출물이지만 여기는 사용자 자유 입력이라, DB 컬럼
 * (VARCHAR(255)/TEXT) 제약 위반으로 500이 나기 전에 400으로 거절한다.
 */
@Service
@RequiredArgsConstructor
public class TimelineEventEditService {

    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_SUBTITLE_LENGTH = 255;
    private static final int MAX_MEMO_LENGTH = 10_000;

    private final TimelineEventService timelineEventService;
    private final DailyRecordService dailyRecordService;
    private final TimelineItemService timelineItemService;

    /**
     * title·subtitle·startAt·endAt 4개 필드를 요청 값으로 전체 교체한다(절대값 대입 — memo·하위 items 불변).
     * 시간은 사용자 입력 그대로 저장한다 — AI finalize의 +10분 충돌 보정·Item 시간 변경을 적용하지 않는다.
     */
    @Transactional
    public TimelineEventResponse updateEvent(String applicationVersion, long userId, Long timelineEventId,
                                             String title, String subtitle,
                                             LocalDateTime startAt, LocalDateTime endAt) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineEvent event = findOwnedEditableEvent(userId, timelineEventId);
        String validTitle = requireValidTitle(title);
        String normalizedSubtitle = normalizeSubtitle(subtitle);
        requireValidTimeRange(startAt, endAt);
        event.updateDetails(validTitle, normalizedSubtitle, startAt, endAt);
        return toResponse(event);
    }

    /** 메모 작성·수정·제거 단일 진입점. null·공백뿐(필드 부재 포함)은 제거, 그 외는 trim 없이 원문 저장. */
    @Transactional
    public TimelineEventResponse updateMemo(String applicationVersion, long userId, Long timelineEventId,
                                            String memo) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineEvent event = findOwnedEditableEvent(userId, timelineEventId);
        event.updateMemo(normalizeMemo(memo));
        return toResponse(event);
    }

    /**
     * 편집 대상 이벤트를 조회하고 소유권·상태를 검증한다. 이벤트 없음·record 없음·비소유는 전부
     * 404(ERROR_0404)로 은닉하고, SAVED record는 409(ERROR_1003)로 거절한다.
     */
    private TimelineEvent findOwnedEditableEvent(long userId, Long timelineEventId) {
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        DailyRecord record = dailyRecordService.findById(event.getDailyRecordId())
                .filter(owned -> owned.getUserId() == userId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        if (record.getStatus() == DailyRecordStatus.SAVED) {
            throw new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
        }
        return event;
    }

    /** title은 앞뒤 공백 제거 후 1~255자 필수. 위반 메시지에 사용자 입력을 echo하지 않는다(길이만). */
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

    /** subtitle은 null·공백뿐이면 비움(null), 그 외 앞뒤 공백 제거 후 최대 255자. */
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

    /** startAt 필수, endAt은 있으면 startAt 이상(같음 허용 — 0분 구간). 생성 검증기와 같은 규칙. */
    private void requireValidTimeRange(LocalDateTime startAt, LocalDateTime endAt) {
        if (startAt == null) {
            throw new IllegalArgumentException("startAt is required");
        }
        if (endAt != null && endAt.isBefore(startAt)) {
            throw new IllegalArgumentException("endAt is before startAt");
        }
    }

    /** memo는 null·공백뿐이면 제거(null), 그 외 trim 없이 원문 보존 + String.length() 기준 최대 10,000자. */
    private String normalizeMemo(String memo) {
        if (memo == null || memo.isBlank()) {
            return null;
        }
        if (memo.length() > MAX_MEMO_LENGTH) {
            throw new IllegalArgumentException("memo is too long: length=" + memo.length());
        }
        return memo;
    }

    /** 갱신된 이벤트를 하위 아이템 포함 응답으로 조립한다(아이템은 조회만 — 편집 API는 아이템을 바꾸지 않는다). */
    private TimelineEventResponse toResponse(TimelineEvent event) {
        List<TimelineItemResponse> items = timelineItemService.findByTimelineEventId(event.getTimelineEventId())
                .stream()
                .map(TimelineItemResponse::from)
                .toList();
        return TimelineEventResponse.from(event, items);
    }
}
