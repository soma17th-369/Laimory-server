package com.laimory.server.timeline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.id.UuidV7;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.photo.S3PhotoStorageService;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 사용자 타임라인 삭제(Event·DailyRecord) 오케스트레이터. leaf 서비스를 합성한다(레포 직접 접근 금지).
 *
 * <p>공통 순서가 load-bearing이다:
 * 조회·소유권/상태 사전 검증(404 은닉·SAVED 409 — 아무 부수효과 전에 거절) → 날짜 guard를
 * {@code delete:{operationId}} holder로 선점(실패 = 같은 날짜 AI 작업/사진추가/삭제 진행 중 → 409 -1016) →
 * <b>exclusive Item</b>(삭제 대상 Event에만 연결된 Item)의 PHOTO S3 key 수집(guard 안에서 — 동시 AI append가
 * 수집과 삭제 사이에 연결을 바꾸지 못함) → S3 배치 삭제(DB 트랜잭션 밖) → 전 batch 성공 시에만 별도 빈
 * 트랜잭션에서 재확인 후 DB 삭제({@link TimelineDeletionTransactionService} — Event/junction은 DB
 * {@code ON DELETE CASCADE}, orphan Item은 명시 삭제).
 *
 * <p>N:M에서 Item은 여러 Event에 공유될 수 있다 — 다른 Event에도 연결된 shared Item과 그 PHOTO는
 * 유지하고, exclusive Item만 S3·DB에서 지운다. 정상 write 경로에선 same-record 규칙으로 record 밖 Event에
 * 연결된 후보가 없어야 하지만, 있어도 shared로 간주해 방어적으로 유지한다.
 *
 * <p>S3 실패(-1017)면 DB 삭제를 시작하지 않아 데이터가 보존되고, S3 성공 후 DB 실패(500)는
 * 재시도로 수렴한다(이미 지워진 key는 S3가 성공 처리). Outbox·보상 업로드·참조 카운트는 두지 않는다.
 *
 * <p>guard는 <b>성공·1017·500 모든 종료 경로에서 finally로 compare-and-release</b>한다 — 실패 시
 * 미해제면 클라 재시도가 TTL(1h) 동안 -1016으로 막혀 "재시도로 수렴" 설계가 깨진다.
 * 해제 자체는 best-effort다(예외는 삼키고 WARN — TTL이 안전망).
 *
 * <p>마지막 Event를 지워도 DailyRecord는 유지한다 — 하루 전체 제거는 DailyRecord 삭제만 담당한다.
 * 로그에 객체 key·URL·메모 내용은 남기지 않는다(사진 수·batch 수·소요시간·성공 여부만).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineDeletionService {

    private final TimelineEventService timelineEventService;
    private final TimelineEventItemService timelineEventItemService;
    private final DailyRecordService dailyRecordService;
    private final TimelineItemService timelineItemService;
    private final TimelineTaskService timelineTaskService;
    private final S3PhotoStorageService s3PhotoStorageService;
    private final TimelineDeletionTransactionService timelineDeletionTransactionService;
    private final ObjectMapper objectMapper;

    /** Event와 이 Event에만 연결된 exclusive Item을 삭제한다(exclusive 사진 S3 객체 포함). shared Item은 유지된다. */
    public void deleteEvent(String applicationVersion, long userId, Long timelineEventId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        DailyRecord record = requireOwnedDraftRecord(userId, event.getDailyRecordId(),
                ExceptionType.TIMELINE_EVENT_NOT_FOUND);
        deleteUnderDateGuard(userId, record.getRecordDate(), "event", timelineEventId,
                () -> findExclusiveItems(Set.of(timelineEventId)),
                () -> timelineDeletionTransactionService.deleteEvent(userId, timelineEventId));
    }

    /** 하루 전체(Record·Events·exclusive Items)를 삭제한다(exclusive 사진 S3 객체 포함). */
    public void deleteDailyRecord(String applicationVersion, long userId, Long dailyRecordId) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        DailyRecord record = requireOwnedDraftRecord(userId, dailyRecordId, ExceptionType.DAILY_RECORD_NOT_FOUND);
        deleteUnderDateGuard(userId, record.getRecordDate(), "dailyRecord", dailyRecordId,
                () -> findExclusiveItems(timelineEventService.findByDailyRecordId(dailyRecordId).stream()
                        .map(TimelineEvent::getTimelineEventId)
                        .collect(Collectors.toSet())),
                () -> timelineDeletionTransactionService.deleteDailyRecord(userId, dailyRecordId));
    }

    /**
     * 날짜 guard 선점 → exclusive Item PHOTO key 수집 → S3 배치 삭제 → DB 삭제(별도 빈 트랜잭션) → finally 해제.
     * itemsSupplier는 guard 선점 <b>후</b>에 평가한다 — 동시 AI append(같은 guard 필요)가 수집과 삭제
     * 사이에 junction을 추가해 S3 orphan(또는 broken photo)을 만드는 창을 없앤다.
     */
    private void deleteUnderDateGuard(long userId, LocalDate recordDate, String target, Long targetId,
                                      Supplier<List<TimelineItem>> itemsSupplier, Runnable dbDelete) {
        long totalStartNanos = System.nanoTime();
        String operationId = UuidV7.randomUuidV7().toString();
        String guardHolder = TimelineTaskService.deleteGuardHolder(operationId);
        if (!timelineTaskService.claimDateGuard(userId, recordDate, guardHolder)) {
            throw new BusinessException(ExceptionType.RECORD_DATE_IN_PROGRESS);
        }
        try {
            List<String> photoObjectKeys = collectPhotoObjectKeys(itemsSupplier.get(), userId);
            long s3StartNanos = System.nanoTime();
            if (!photoObjectKeys.isEmpty()) {
                s3PhotoStorageService.deleteAll(photoObjectKeys);
            }
            long s3ElapsedMs = Duration.ofNanos(System.nanoTime() - s3StartNanos).toMillis();
            dbDelete.run();
            int batches = (photoObjectKeys.size() + S3PhotoStorageService.MAX_KEYS_PER_BATCH_DELETE - 1)
                    / S3PhotoStorageService.MAX_KEYS_PER_BATCH_DELETE;
            log.info("timeline 삭제 완료: target={} id={} photoObjects={} batches={} s3ElapsedMs={} totalElapsedMs={}",
                    target, targetId, photoObjectKeys.size(), batches, s3ElapsedMs,
                    Duration.ofNanos(System.nanoTime() - totalStartNanos).toMillis());
        } finally {
            releaseDateGuardQuietly(userId, recordDate, guardHolder, operationId);
        }
    }

    /** record 없음·비소유는 {@code notFoundType}(404 은닉), SAVED는 409(-1003)로 사전 거절한다. */
    private DailyRecord requireOwnedDraftRecord(long userId, Long dailyRecordId, ExceptionType notFoundType) {
        DailyRecord record = dailyRecordService.findById(dailyRecordId)
                .filter(owned -> owned.getUserId() == userId)
                .orElseThrow(() -> new BusinessException(notFoundType));
        if (record.getStatus() == DailyRecordStatus.SAVED) {
            throw new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
        }
        return record;
    }

    /**
     * 삭제될 Event 집합에<b>만</b> 연결된 exclusive Item들을 로드한다(leaf 서비스 합성 — 레포 직접 접근 금지).
     * 판정 기준은 {@link TimelineDeletionTransactionService}의 orphan 계산과 동일하다 — 같은 guard 아래라
     * 두 시점의 결과가 갈리지 않는다(S3 삭제 대상 = DB에서 지워질 Item).
     */
    private List<TimelineItem> findExclusiveItems(Set<Long> deletedEventIds) {
        List<Long> candidateItemIds = timelineEventItemService.findByTimelineEventIds(deletedEventIds).stream()
                .map(TimelineEventItem::getTimelineItemId)
                .distinct()
                .toList();
        Map<Long, Set<Long>> eventIdsByItemId = timelineEventItemService.findByTimelineItemIds(candidateItemIds)
                .stream()
                .collect(Collectors.groupingBy(TimelineEventItem::getTimelineItemId,
                        Collectors.mapping(TimelineEventItem::getTimelineEventId, Collectors.toSet())));
        List<Long> exclusiveItemIds = candidateItemIds.stream()
                .filter(itemId -> deletedEventIds.containsAll(
                        eventIdsByItemId.getOrDefault(itemId, new HashSet<>())))
                .toList();
        return timelineItemService.findByIds(exclusiveItemIds);
    }

    /**
     * exclusive PHOTO item들의 payload에서 filename을 복원해 full S3 key를 유도한다(중복 제거 — 같은 파일을
     * 참조하는 중복 Item이 있어도 한 번만 삭제). payload가 깨졌거나 filename이 없으면 S3 삭제는 건너뛰고
     * 계속 진행한다(orphan 허용 — {@code TimelineDraftCleanupScheduler.deletePhotoObject}와 동일 규칙;
     * 나쁜 한 건이 전체 삭제를 막지 않음). userId는 소유권 검증을 통과한 컨트롤러 결정값이라
     * {@code DailyRecord.userId}와 동일하다.
     */
    private List<String> collectPhotoObjectKeys(List<TimelineItem> items, long userId) {
        Set<String> keys = new LinkedHashSet<>();
        for (TimelineItem item : items) {
            if (item.getItemType() != ItemType.PHOTO) {
                continue;
            }
            PhotoPayload photo;
            try {
                photo = objectMapper.treeToValue(item.getPayload(), PhotoPayload.class);
            } catch (JsonProcessingException e) {
                log.warn("PHOTO payload 파싱 실패, S3 삭제 건너뜀(orphan 허용): timelineItemId={}",
                        item.getTimelineItemId(), e);
                continue;
            }
            if (photo.filename() == null || photo.filename().isBlank()) {
                log.warn("PHOTO payload filename 없음, S3 삭제 건너뜀(orphan 허용): timelineItemId={}",
                        item.getTimelineItemId());
                continue;
            }
            keys.add(PhotoObjectKeys.fullKey(photo.filename(), userId));
        }
        return List.copyOf(keys);
    }

    /** guard 해제는 best-effort — 실패해도 TTL(1h)이 자연 해제하는 안전망이 있어 원래 예외/결과를 막지 않는다. */
    private void releaseDateGuardQuietly(long userId, LocalDate recordDate, String holder, String operationId) {
        try {
            timelineTaskService.releaseDateGuard(userId, recordDate, holder);
        } catch (RuntimeException e) {
            log.warn("date guard release failed (TTL로 자연 해제 예정): operationId={} detail={}",
                    operationId, e.getMessage());
        }
    }
}
