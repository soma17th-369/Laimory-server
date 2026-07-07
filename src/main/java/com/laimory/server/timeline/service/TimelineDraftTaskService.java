package com.laimory.server.timeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.RecordDates;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.common.id.UuidV7;
import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineDefaults;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.payload.HealthPayload;
import com.laimory.server.timeline.payload.LocationPayload;
import com.laimory.server.timeline.payload.MovementPayload;
import com.laimory.server.timeline.payload.NotificationPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoFilenames;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 작성 작업 생성(POST) 오케스트레이터. recordDate 계산 + SAVED 거절 + 지오코딩 enrich + draft 행 저장
 * + PROCESSING 기록 + AI 디스패치를 합성한다. (leaf가 아닌 합성 오케스트레이터라 여러 leaf 서비스를 주입한다.)
 *
 * <p>요청 스레드는 디스패치를 블로킹하지 않는다(dispatch는 fire-and-forget; v1 no-op).
 * 같은 날짜로 PROCESSING task가 떠 있는 동안 두 번째 POST가 와도 둘 다 통과할 수 있다(plan 모호점 4, MVP 수용).
 *
 * <p>⚠️ 단계 순서가 load-bearing이다: draft 행을 <b>먼저 저장·커밋</b>한 뒤 Redis에 PROCESSING을 기록한다 —
 * 그래야 "PROCESSING인데 draft 없음" 오판(콜백의 idempotent-recovery 판정을 깨뜨림)이 안 생긴다.
 */
@Service
@RequiredArgsConstructor
public class TimelineDraftTaskService {

    private static final Logger log = LoggerFactory.getLogger(TimelineDraftTaskService.class);

    private final DailyRecordService dailyRecordService;
    private final TimelineTaskService timelineTaskService;
    private final TimelineDraftSourceItemService timelineDraftSourceItemService;
    private final TimelineEventService timelineEventService;
    private final TimelineItemService timelineItemService;
    private final SourceItemEnrichmentService sourceItemEnrichmentService;
    private final TimelineEventSuggestionDispatcher timelineEventSuggestionDispatcher;
    private final ObjectMapper objectMapper;

    /**
     * 작성 작업을 만들고 taskId를 반환한다. recordDate는 recordAt(벽시계 시각)의 정오 경계로 계산한다.
     * 이미 SAVED인 daily record면 409(BusinessException ERROR_1003)로 거절한다.
     * dispatch가 동기 예외를 던지면 task를 FAILED로 고정하고 taskId는 정상 반환한다(클라가 폴링으로 결과 확인).
     */
    public String createDraftTask(String applicationVersion, LocalDateTime recordAt, String recordTimeZone,
                                  List<SourceItemDto> sourceItems) {
        if (recordAt == null) {
            throw new IllegalArgumentException("recordAt is required");
        }
        if (recordTimeZone == null) {
            throw new IllegalArgumentException("recordTimeZone is required");
        }
        if (sourceItems == null || sourceItems.isEmpty()) {
            throw new IllegalArgumentException("sourceItems is required");
        }
        requireValidSourceItems(sourceItems);

        // recordTimeZone은 저장·역산용이라 유효성만 검증(잘못된 zone → IAE → 400). 날짜는 recordAt 벽시계 시각의 정오 경계로 산출(zone 불필요).
        RecordDates.requireValidTimeZone(recordTimeZone);
        LocalDate recordDate = RecordDates.resolveRecordDate(recordAt);

        // enrich의 photoUrl 키 파생과 draft row의 user_id는 반드시 같은 사용자여야 한다(불변식) —
        // 인증 도입 시 이 변수 한 곳만 바꾼다(두 지점이 따로 놀면 남의 키로 URL을 파생하는 버그).
        long userId = TimelineDefaults.DEFAULT_USER_ID;

        Optional<DailyRecord> existingRecord = dailyRecordService.findByUserIdAndRecordDate(userId, recordDate);
        existingRecord
                .filter(record -> record.getStatus() == DailyRecordStatus.SAVED)
                .ifPresent(record -> {
                    throw new BusinessException(ErrorCode.ERROR_1003);
                });

        // append 중복 방지: 요청 배치 내 중복 rawId를 dedupe하고, 같은 날짜에 이미 저장된 rawId(기존 event의 item)를 제외한다.
        // 이미 저장된 event는 사용자 소유라 서버가 재그룹핑하지 않는다 — 신규 rawId만 새 event로 append한다.
        List<SourceItemDto> newItems = excludeAlreadySaved(dedupeByRawId(sourceItems), existingRecord);
        if (newItems.isEmpty()) {
            throw new BusinessException(ErrorCode.ERROR_1013);
        }

        // AI가 이번 append에서 이벤트로 묶을 신규 item의 시간 범위(Redis task에 저장 → AI가 직접 읽는 계약).
        TimelineDraftTask.TimelineWindow timelineWindow = computeTimelineWindow(newItems, recordAt);

        // 지오코딩·photoUrl enrich + payload 재구성(DB 트랜잭션 밖 외부 호출 — 거절·필터 뒤에 둬서 낭비 방지).
        // AI가 taskId로 DB에서 직접 읽으므로 저장 전에 완료돼야 한다. 지오코딩 실패는 내부에서 필드 null로 강등된다.
        List<SourceItemDto> enrichedItems = sourceItemEnrichmentService.enrich(newItems, userId);

        String taskId = UuidV7.randomUuidV7().toString();
        // one-time 콜백 토큰: 원문은 AI에만 전달하고 서버는 해시만 보관한다.
        String callbackToken = CallbackTokens.generate();
        String callbackTokenHash = CallbackTokens.hash(callbackToken);

        // 1. draft 행을 먼저 저장·커밋한다(Redis보다 먼저 — 위 클래스 주석의 순서 불변식). 실패 시 미커밋 상태로 전파(500).
        List<TimelineDraftSourceItem> rows = enrichedItems.stream()
                .map(src -> TimelineDraftSourceItem.of(
                        taskId, userId,
                        src.itemType(), src.rawId(), src.startAt(), src.endAt(),
                        objectMapper.valueToTree(src.payload())))
                .toList();
        timelineDraftSourceItemService.saveAll(rows);

        // 2. Redis PROCESSING 기록. 실패하면 방금 저장한 draft를 보상 삭제하고 전파한다(고아 draft 방지).
        try {
            timelineTaskService.createProcessing(taskId, recordDate, recordAt, recordTimeZone, timelineWindow, callbackTokenHash);
        } catch (RuntimeException e) {
            timelineDraftSourceItemService.deleteByTaskId(taskId);
            throw e;
        }

        // 3. AI dispatch. 동기 예외(RuntimeException)면 task를 FAILED로 고정하고 draft는 보존(cleanup이 나중에 정리).
        //    taskId는 정상 반환해 클라가 폴링으로 실패를 확인하게 한다.
        try {
            timelineEventSuggestionDispatcher.dispatch(taskId, callbackToken);
        } catch (RuntimeException e) {
            // 상세는 로그로만 — task엔 분류 코드만 저장(폴링 body.error 유출 차단).
            log.warn("timeline event suggestion dispatch failed: taskId={} detail={}", taskId, e.getMessage());
            timelineTaskService.markFailed(taskId, recordDate, ErrorCode.ERROR_1009, callbackTokenHash);
        }

        return taskId;
    }

    /** 요청 배치 내 중복 rawId를 제거한다(첫 항목 유지). 유출 방지로 로그엔 개수만 남긴다(rawId는 클라 입력값). */
    private List<SourceItemDto> dedupeByRawId(List<SourceItemDto> sourceItems) {
        Set<String> seen = new LinkedHashSet<>();
        List<SourceItemDto> deduped = new ArrayList<>();
        for (SourceItemDto item : sourceItems) {
            if (seen.add(item.rawId())) {
                deduped.add(item);
            }
        }
        if (deduped.size() < sourceItems.size()) {
            log.warn("dropped duplicate rawId source items in request: dropped={} kept={}",
                    sourceItems.size() - deduped.size(), deduped.size());
        }
        return deduped;
    }

    /**
     * 같은 날짜에 이미 저장된 rawId(기존 event의 timeline_items)를 제외한다. record가 없거나(그날 첫 append)
     * 기존 event가 없으면 그대로 통과한다. leaf 서비스 합성으로 조회한다(TimelineItemRepository 직접 접근 금지).
     */
    private List<SourceItemDto> excludeAlreadySaved(List<SourceItemDto> items, Optional<DailyRecord> existingRecord) {
        if (existingRecord.isEmpty()) {
            return items;
        }
        List<Long> eventIds = timelineEventService.findByDailyRecordId(existingRecord.get().getDailyRecordId()).stream()
                .map(TimelineEvent::getTimelineEventId)
                .toList();
        List<String> rawIds = items.stream().map(SourceItemDto::rawId).toList();
        Set<String> savedRawIds = timelineItemService.findSavedRawIds(eventIds, rawIds);
        if (savedRawIds.isEmpty()) {
            return items;
        }
        return items.stream().filter(item -> !savedRawIds.contains(item.rawId())).toList();
    }

    /**
     * 신규 item에서 AI 그룹핑 대상 시간 범위를 만든다. start=min(startAt??endAt), end=max(endAt??startAt)을
     * recordAt으로 클램프한다(미래 캘린더 일정 방어; recordAt은 클라 벽시계 "지금"이라 item 시각과 같은 좌표계).
     * 시간이 전혀 없는 item만 있으면 null(범위 없음).
     */
    private TimelineDraftTask.TimelineWindow computeTimelineWindow(List<SourceItemDto> items, LocalDateTime recordAt) {
        LocalDateTime startTime = items.stream()
                .map(item -> item.startAt() != null ? item.startAt() : item.endAt())
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (startTime == null) {
            return null;
        }
        LocalDateTime maxEnd = items.stream()
                .map(item -> item.endAt() != null ? item.endAt() : item.startAt())
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(startTime);
        LocalDateTime endTime = maxEnd.isAfter(recordAt) ? recordAt : maxEnd;
        if (endTime.isBefore(startTime)) {
            endTime = startTime;
        }
        return new TimelineDraftTask.TimelineWindow(startTime, endTime);
    }

    /**
     * row 생성·DB 제약(NOT NULL) 전에 입력 오류를 IAE로 막아 400으로 응답한다(500 방지).
     *
     * <p>sealed payload 패턴 스위치(default 없음)라 새 payload 타입 추가 시 case 누락이 컴파일 에러로 걸린다.
     * 각 case는 itemType↔payload 일치도 함께 검증한다(HTTP 경로는 Jackson external 디스크리미네이터가 일치를
     * 보장하지만, 프로그래밍 방식 생성 경로 방어).
     *
     * <p>PHOTO는 클라가 보낸 {@code filename}을 서버가 full key에 끼워 넣으므로, 이 입력 경계 한 곳에서
     * 엄격 패턴 검증한다({@link PhotoFilenames}; UUIDv7+허용ext, 슬래시·{@code ..} 불허).
     */
    private void requireValidSourceItems(List<SourceItemDto> sourceItems) {
        for (int i = 0; i < sourceItems.size(); i++) {
            SourceItemDto src = sourceItems.get(i);
            if (src == null) {
                throw new IllegalArgumentException("sourceItem is null: index=" + i);
            }
            if (src.itemType() == null) {
                throw new IllegalArgumentException("sourceItem has null itemType: index=" + i);
            }
            // rawId는 클라 원본 데이터 ID(UUIDv7) — opaque echo 값이라 형식 검증·trim 없이 존재·길이만 본다(DB 컬럼 36자).
            if (isBlank(src.rawId())) {
                throw new IllegalArgumentException("sourceItem requires rawId: index=" + i);
            }
            if (src.rawId().length() > 36) {
                throw new IllegalArgumentException("sourceItem rawId is too long: index=" + i);
            }
            if (src.payload() == null) {
                throw new IllegalArgumentException("sourceItem has null payload: index=" + i);
            }
            switch (src.payload()) {
                case PhotoPayload photo -> {
                    requireItemType(src.itemType(), ItemType.PHOTO, i);
                    PhotoFilenames.requireValid(photo.filename());
                    // clientPhotoUri는 1차 로컬 캐싱용 기기 URI라 PHOTO엔 필수다(서버는 내용 미해석, echo 전용).
                    if (isBlank(photo.clientPhotoUri())) {
                        throw new IllegalArgumentException("PHOTO sourceItem requires clientPhotoUri: index=" + i);
                    }
                }
                case CalendarPayload calendar -> requireItemType(src.itemType(), ItemType.CALENDAR, i);
                case LocationPayload location -> {
                    requireItemType(src.itemType(), ItemType.LOCATION, i);
                    requireValidCoordinate(location.latitude(), location.longitude(), "LOCATION", i);
                }
                case MovementPayload movement -> {
                    requireItemType(src.itemType(), ItemType.MOVEMENT, i);
                    if (movement.start() == null || movement.end() == null) {
                        throw new IllegalArgumentException("MOVEMENT sourceItem requires start and end: index=" + i);
                    }
                    requireValidCoordinate(movement.start().latitude(), movement.start().longitude(), "MOVEMENT start", i);
                    requireValidCoordinate(movement.end().latitude(), movement.end().longitude(), "MOVEMENT end", i);
                    // 이동 거리는 음수가 무의미(HEALTH value와 같은 이유로 입력 경계에서 차단).
                    if (movement.distanceMeters() != null
                            && (!Double.isFinite(movement.distanceMeters()) || movement.distanceMeters() < 0)) {
                        throw new IllegalArgumentException(
                                "MOVEMENT sourceItem distanceMeters must be a non-negative finite number: index=" + i);
                    }
                }
                case HealthPayload health -> {
                    requireItemType(src.itemType(), ItemType.HEALTH, i);
                    if (health.metric() == null || isBlank(health.value())) {
                        throw new IllegalArgumentException("HEALTH sourceItem requires metric and value: index=" + i);
                    }
                }
                case NotificationPayload notification -> {
                    requireItemType(src.itemType(), ItemType.NOTIFICATION, i);
                    // 전부 null이면 NON_NULL 직렬화로 빈 {} payload가 저장되므로 최소 내용은 요구한다.
                    if (isBlank(notification.title()) && isBlank(notification.text())) {
                        throw new IllegalArgumentException("NOTIFICATION sourceItem requires title or text: index=" + i);
                    }
                }
            }
        }
    }

    private static void requireItemType(ItemType actual, ItemType expected, int index) {
        if (actual != expected) {
            throw new IllegalArgumentException(
                    expected + " payload does not match itemType " + actual + ": index=" + index);
        }
    }

    /** 좌표는 LOCATION/MOVEMENT 필수. NaN은 범위 비교를 전부 통과하므로 isFinite로 별도 차단한다. */
    private static void requireValidCoordinate(Double latitude, Double longitude, String field, int index) {
        if (latitude == null || longitude == null) {
            throw new IllegalArgumentException(field + " requires latitude and longitude: index=" + index);
        }
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException(field + " has out-of-range coordinate: index=" + index);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
