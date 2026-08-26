package com.laimory.server.timeline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.RawIds;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoPayloadRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoRequest;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoFilenames;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.photo.PhotoUrlService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수동 PHOTO 입력의 검증·분류·저장을 소유하는 공유 컴포넌트 — Event PATCH
 * ({@link TimelineEventEditService}/{@link TimelineEventEditTransactionService})와 수동 Event 생성
 * ({@link TimelineEventCreateService})이 같은 사진 계약을 여기 하나로 공유한다(생성이 PATCH 공개
 * use case를 재호출하지 않기 위한 최소 공유 범위).
 *
 * <p>{@link #requireValidPhotos}는 비-DB 정적 검증이라 transaction 밖(PATCH preflight)·안(생성)
 * 어디서든 호출할 수 있다. {@link #resolve}/{@link #link}는 DB-dependent 분류와 저장이라
 * {@code MANDATORY}로 호출자 transaction 합류를 강제한다 — 밖에서 부르면 분류와 저장이 쪼개져
 * 부분 상태 금지가 깨진다.
 */
@Slf4j
@Service
class TimelineEventPhotoAddService {

    private final TimelineEventService timelineEventService;
    private final TimelineEventItemService timelineEventItemService;
    private final TimelineItemService timelineItemService;
    private final TimelinePhotoDeleteJobService timelinePhotoDeleteJobService;
    private final PhotoUrlService photoUrlService;
    private final ObjectMapper objectMapper;
    private final int maxPhotoCount;

    TimelineEventPhotoAddService(
            TimelineEventService timelineEventService,
            TimelineEventItemService timelineEventItemService,
            TimelineItemService timelineItemService,
            TimelinePhotoDeleteJobService timelinePhotoDeleteJobService,
            PhotoUrlService photoUrlService,
            ObjectMapper objectMapper,
            @Value("${photo.upload.max-count}") int maxPhotoCount) {
        this.timelineEventService = timelineEventService;
        this.timelineEventItemService = timelineEventItemService;
        this.timelineItemService = timelineItemService;
        this.timelinePhotoDeleteJobService = timelinePhotoDeleteJobService;
        this.photoUrlService = photoUrlService;
        this.objectMapper = objectMapper;
        this.maxPhotoCount = maxPhotoCount;
    }

    /** 검증 완료 사진 입력의 내부 표현. */
    record PhotoToAdd(
            String rawId,
            LocalDateTime startAt,
            LocalDateTime endAt,
            String filename,
            String clientPhotoUri,
            Double latitude,
            Double longitude
    ) {
    }

    /** {@link #resolve} 결과 — 재사용할 기존 Item ID와 새로 만들 사진. */
    record PhotoChanges(
            List<Long> existingItemIdsToLink,
            List<PhotoToAdd> newPhotos
    ) {
        static PhotoChanges empty() {
            return new PhotoChanges(List.of(), List.of());
        }
    }

    /** 모든 정적 입력을 검증하고 request rawId 중복은 첫 항목만 유지한다(개수 검사가 dedupe보다 먼저). */
    List<PhotoToAdd> requireValidPhotos(List<UpdateTimelineEventPhotoRequest> photosToAdd) {
        if (photosToAdd == null) {
            throw new IllegalArgumentException("photosToAdd must not be null");
        }
        if (photosToAdd.size() > maxPhotoCount) {
            throw new BusinessException(ExceptionType.PHOTO_COUNT_EXCEEDED, maxPhotoCount);
        }

        Set<String> seenRawIds = new LinkedHashSet<>();
        List<PhotoToAdd> deduped = new ArrayList<>();
        for (int i = 0; i < photosToAdd.size(); i++) {
            UpdateTimelineEventPhotoRequest photo = photosToAdd.get(i);
            if (photo == null) {
                throw new IllegalArgumentException("photosToAdd element is null: index=" + i);
            }
            // rawId는 draft source와 같은 규칙(canonical lowercase UUID, version 무관 — {@link RawIds})으로
            // 검증한다. 메시지에 rawId 원문을 싣지 않는다(GlobalExceptionHandler가 메시지를 로그에 남긴다).
            if (isBlank(photo.rawId())) {
                throw new IllegalArgumentException("photo requires rawId: index=" + i);
            }
            if (!RawIds.isCanonicalUuid(photo.rawId())) {
                throw new IllegalArgumentException("photo rawId is not a canonical UUID: index=" + i);
            }
            requireSecondPrecision(photo.startAt(), "startAt", i);
            requireSecondPrecision(photo.endAt(), "endAt", i);
            UpdateTimelineEventPhotoPayloadRequest payload = photo.payload();
            if (payload == null) {
                throw new IllegalArgumentException("photo requires payload: index=" + i);
            }
            PhotoFilenames.requireValid(payload.filename());
            if (isBlank(payload.clientPhotoUri())) {
                throw new IllegalArgumentException("photo requires clientPhotoUri: index=" + i);
            }

            PhotoToAdd commandPhoto = new PhotoToAdd(
                    photo.rawId(), photo.startAt(), photo.endAt(), payload.filename(), payload.clientPhotoUri(),
                    payload.latitude(), payload.longitude());
            if (seenRawIds.add(photo.rawId())) {
                deduped.add(commandPhoto);
            }
        }
        if (deduped.size() < photosToAdd.size()) {
            log.warn("dropped duplicate rawId photos in manual photo input: dropped={} kept={}",
                    photosToAdd.size() - deduped.size(), deduped.size());
        }
        return List.copyOf(deduped);
    }

    /**
     * 같은 DailyRecord의 rawId 후보를 new/reuse/no-op으로 분류한다. 재사용할 PHOTO의 저장된 시간과
     * 클라이언트 입력 payload가 요청과 다르면 값을 조용히 버리지 않고 거절한다. 분류와 모든 DB-dependent
     * 검증을 entity mutation보다 먼저 끝내 validation 실패 시 호출자의 Event 변경까지 함께 롤백·보류된다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    PhotoChanges resolve(DailyRecord record, Long targetEventId, List<PhotoToAdd> requestedPhotos) {
        if (requestedPhotos.isEmpty()) {
            return PhotoChanges.empty();
        }

        List<Long> recordEventIds = timelineEventService.findByDailyRecordId(record.getDailyRecordId()).stream()
                .map(TimelineEvent::getTimelineEventId)
                .toList();
        List<TimelineEventItem> recordLinks = timelineEventItemService.findByTimelineEventIds(recordEventIds);
        List<Long> recordItemIds = recordLinks.stream()
                .map(TimelineEventItem::getTimelineItemId)
                .distinct()
                .toList();
        Set<String> requestedRawIds = requestedPhotos.stream()
                .map(PhotoToAdd::rawId)
                .collect(Collectors.toSet());
        List<TimelineItem> matchingItems = timelineItemService.findByIdsAndRawIds(recordItemIds, requestedRawIds);

        Map<String, List<TimelineItem>> itemsByRawId = matchingItems.stream()
                .collect(Collectors.groupingBy(TimelineItem::getRawId, HashMap::new, Collectors.toList()));
        Set<Long> targetItemIds = recordLinks.stream()
                .filter(link -> targetEventId.equals(link.getTimelineEventId()))
                .map(TimelineEventItem::getTimelineItemId)
                .collect(Collectors.toSet());

        List<Long> existingItemIdsToLink = new ArrayList<>();
        List<PhotoToAdd> newPhotos = new ArrayList<>();
        for (PhotoToAdd requested : requestedPhotos) {
            List<TimelineItem> candidates = itemsByRawId.getOrDefault(requested.rawId(), List.of());
            if (candidates.stream().anyMatch(item -> item.getItemType() != ItemType.PHOTO)) {
                throw new IllegalArgumentException("rawId is already used by a non-PHOTO item");
            }
            if (candidates.isEmpty()) {
                String objectKey = PhotoObjectKeys.subjectFullKey(requested.filename(), record.getSubjectId());
                Long pendingItemId = timelinePhotoDeleteJobService
                        .cancelPendingForRelink(objectKey, requested.rawId())
                        .orElse(null);
                if (pendingItemId == null) {
                    newPhotos.add(requested);
                } else {
                    TimelineItem pendingItem = timelineItemService.findById(pendingItemId)
                            .orElseThrow(() -> new IllegalStateException("relinked PHOTO item not found"));
                    requireMatchingClientInput(pendingItem, requested);
                    existingItemIdsToLink.add(pendingItemId);
                }
                continue;
            }

            TimelineItem reusable = candidates.stream()
                    .filter(item -> targetItemIds.contains(item.getTimelineItemId()))
                    .min(Comparator.comparing(TimelineItem::getTimelineItemId))
                    .orElseGet(() -> candidates.stream()
                            .min(Comparator.comparing(TimelineItem::getTimelineItemId))
                            .orElseThrow());
            requireMatchingClientInput(reusable, requested);
            if (!targetItemIds.contains(reusable.getTimelineItemId())) {
                existingItemIdsToLink.add(reusable.getTimelineItemId());
            }
        }

        Set<String> newFilenames = new HashSet<>();
        for (PhotoToAdd newPhoto : newPhotos) {
            if (!newFilenames.add(newPhoto.filename())) {
                throw new IllegalArgumentException("filename is duplicated across new photos");
            }
        }
        return new PhotoChanges(existingItemIdsToLink, newPhotos);
    }

    /**
     * 분류 결과를 저장한다 — 기존 Item 재연결과 신규 PHOTO Item/junction insert. 이번 호출로 대상
     * Event에 연결된 전체 Item ID(기존 재사용·job 재연결·신규)를 반환한다 — 생성 응답 조립의 입력이며
     * PATCH는 반환을 무시한다(추가 조회 없음).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Long> link(UUID subjectId, Long timelineEventId, PhotoChanges photoChanges) {
        List<TimelineEventItem> links = new ArrayList<>();
        List<Long> linkedItemIds = new ArrayList<>(photoChanges.existingItemIdsToLink());
        for (Long itemId : photoChanges.existingItemIdsToLink()) {
            links.add(TimelineEventItem.of(timelineEventId, itemId));
        }
        for (PhotoToAdd photo : photoChanges.newPhotos()) {
            // address/places는 draft enrich 전용이라 수동 추가 경로에서는 채우지 않는다(#324) —
            // 이 경로는 지오코딩을 타지 않으므로 같은 타입에 주소가 있는 사진과 없는 사진이 공존한다.
            PhotoPayload payload = new PhotoPayload(
                    photo.filename(), photo.clientPhotoUri(), photo.latitude(), photo.longitude(),
                    null, null, null, photoUrlService.buildSubjectUrl(photo.filename(), subjectId));
            TimelineItem item = timelineItemService.save(TimelineItem.of(
                    ItemType.PHOTO, photo.rawId(), photo.startAt(), photo.endAt(),
                    objectMapper.valueToTree(payload)));
            links.add(TimelineEventItem.of(timelineEventId, item.getTimelineItemId()));
            linkedItemIds.add(item.getTimelineItemId());
        }
        if (!links.isEmpty()) {
            timelineEventItemService.saveAll(links);
        }
        return List.copyOf(linkedItemIds);
    }

    /** 같은 rawId Item 재사용은 요청 값을 버리는 update가 아니다. 클라이언트 입력 저장본이 다르면 400으로 거절한다. */
    private void requireMatchingClientInput(TimelineItem storedItem, PhotoToAdd requested) {
        PhotoPayload storedPayload;
        try {
            storedPayload = objectMapper.treeToValue(storedItem.getPayload(), PhotoPayload.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("existing PHOTO payload cannot be parsed", exception);
        }
        if (storedPayload == null) {
            throw new IllegalStateException("existing PHOTO payload is null");
        }

        if (!Objects.equals(storedItem.getStartAt(), requested.startAt())
                || !Objects.equals(storedItem.getEndAt(), requested.endAt())
                || !Objects.equals(storedPayload.filename(), requested.filename())
                || !Objects.equals(storedPayload.clientPhotoUri(), requested.clientPhotoUri())
                || !Objects.equals(storedPayload.latitude(), requested.latitude())
                || !Objects.equals(storedPayload.longitude(), requested.longitude())) {
            throw new IllegalArgumentException("photo input does not match existing rawId");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** MySQL timeline_items DATETIME 정밀도와 재사용 비교를 맞춰 소수 초가 조용히 손실되지 않게 한다. */
    private void requireSecondPrecision(LocalDateTime value, String field, int index) {
        if (value != null && value.getNano() != 0) {
            throw new IllegalArgumentException("photo " + field + " must use second precision: index=" + index);
        }
    }
}
