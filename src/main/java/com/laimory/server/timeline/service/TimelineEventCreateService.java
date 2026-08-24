package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.dto.CreateTimelineEventRequest;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.dto.TimelineItemResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineItem;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기존 하루 기록에 타임라인 Event를 수동 생성하는 use case. leaf 서비스를 합성한다(레포 직접 접근 금지).
 *
 * <p>소유 record 재확인·Event insert·optional PHOTO Item/junction insert를 하나의 트랜잭션이
 * 소유한다 — 사진 분류({@code resolve})·저장({@code link}) 실패는 Event insert까지 전체 롤백되어
 * 사진 없는 Event만 남는 부분 상태를 만들지 않는다. DailyRecord를 자동 생성하지 않는다 —
 * {@code recordAt}·{@code recordTimezone}을 이 요청만으로 정할 수 없다. 기존 편집 정책과 같이
 * DRAFT/SAVED 모두 허용하고, AI 결과 저장의 exact-start +10분 충돌 보정 없이 보낸 시각을 그대로 저장한다.
 *
 * <p>수동 Event의 AI 결과 전용 필드({@code question}/{@code place}/{@code address})는 모두 null이다.
 * optional {@code photosToAdd}의 검증·분류·저장 규칙은 Event PATCH와
 * {@link TimelineEventPhotoAddService}로 공유하며(트랜잭션 안 S3 호출·업로드 존재 확인 없음),
 * 함께 연결된 PHOTO Item은 응답 {@code items}에 조회 경로와 같은 정렬로 포함한다(사진 없으면
 * {@code items=[]}). User Memory 갱신은 새로 enqueue하지 않는다 — SAVED Event 편집과 같은 현재 정책이다.
 */
@Service
@RequiredArgsConstructor
public class TimelineEventCreateService {

    private final DailyRecordService dailyRecordService;
    private final TimelineEventService timelineEventService;
    private final TimelineItemService timelineItemService;
    private final TimelineEventPhotoAddService timelineEventPhotoAddService;

    /**
     * 인증 사용자의 해당 날짜 하루 기록에 Event(와 optional PHOTO)를 생성하고, 생성 ID와 연결 Item을
     * 포함한 Event 표현을 반환한다.
     *
     * @throws BusinessException 해당 날짜 record 없음·비소유 404 {@code -404}(존재를 구분해 주지 않는다),
     *                           사진 수 초과 400 {@code -1004}, 같은 PHOTO object 삭제 진행 중 409 {@code -1019}
     * @throws IllegalArgumentException 필수·길이·시간 범위·사진 입력 오류(400 {@code -400})
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
        List<TimelineEventPhotoAddService.PhotoToAdd> photos =
                timelineEventPhotoAddService.requireValidPhotos(request.photosToAdd());

        TimelineEvent event = TimelineEvent.of(record.getDailyRecordId(), request.eventType(),
                request.startAt(), request.endAt(), title, subtitle, null, null, null);
        event.updateMemo(memo);
        TimelineEvent saved = timelineEventService.save(event);
        if (photos.isEmpty()) {
            return TimelineEventResponse.from(saved, List.of());
        }

        // Event를 먼저 저장하고 실제 생성 ID를 target으로 넘겨 resolve의 계약(비-null target)을 PATCH와
        // 동일하게 유지한다 — 신규 Event는 연결 junction이 없어 "대상 Event 기연결 no-op" 분기가 비활성이다.
        TimelineEventPhotoAddService.PhotoChanges changes =
                timelineEventPhotoAddService.resolve(record, saved.getTimelineEventId(), photos);
        List<Long> linkedItemIds =
                timelineEventPhotoAddService.link(subjectId, saved.getTimelineEventId(), changes);
        return TimelineEventResponse.from(saved, assembleItems(linkedItemIds));
    }

    /** 조회 경로(DailyTimelineService)와 같은 정렬 계약 — startAt(null 먼저)·timelineItemId 오름차순. */
    private List<TimelineItemResponse> assembleItems(List<Long> linkedItemIds) {
        return timelineItemService.findByIds(linkedItemIds).stream()
                .sorted(Comparator.comparing(TimelineItem::getStartAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(TimelineItem::getTimelineItemId))
                .map(TimelineItemResponse::from)
                .toList();
    }
}
