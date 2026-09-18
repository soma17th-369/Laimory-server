package com.laimory.server.timeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.RawIds;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoPayloadRequest;
import com.laimory.server.timeline.dto.UpdateTimelineEventPhotoRequest;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoFilenames;
import com.laimory.server.timeline.photo.PhotoUrlService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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

    private final TimelineEventItemService timelineEventItemService;
    private final TimelineItemService timelineItemService;
    private final PhotoUrlService photoUrlService;
    private final ObjectMapper objectMapper;
    private final int maxPhotoCount;

    TimelineEventPhotoAddService(
            TimelineEventItemService timelineEventItemService,
            TimelineItemService timelineItemService,
            PhotoUrlService photoUrlService,
            ObjectMapper objectMapper,
            @Value("${photo.upload.max-count}") int maxPhotoCount) {
        this.timelineEventItemService = timelineEventItemService;
        this.timelineItemService = timelineItemService;
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

    /** {@link #resolve} 결과 — 새로 만들 사진. 대상 Event에 같은 rawId가 이미 연결된 사진은 빠진다(no-op). */
    record PhotoChanges(List<PhotoToAdd> newPhotos) {
        static PhotoChanges empty() {
            return new PhotoChanges(List.of());
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
     * 대상 Event에 이미 연결된 Item의 rawId와 대조해 no-op/new로 분류한다. 같은 rawId가 있으면 비교 없이
     * 건너뛴다 — 이 분기에 도달하는 것은 커밋 뒤 응답을 잃은 같은 PATCH의 재시도뿐이다. Android는 사진 선택마다
     * 새 rawId·새 filename을 발급하므로 record의 다른 Event에 있는 사진을 같은 rawId로 다시 보내는 경로가 없고,
     * 서버도 record 전체를 조회하지 않는다(#502). 분류와 DB-dependent 검증을 entity mutation보다 먼저 끝내
     * 실패 시 호출자의 Event 변경까지 함께 롤백·보류된다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    PhotoChanges resolve(Long targetEventId, List<PhotoToAdd> requestedPhotos) {
        if (requestedPhotos.isEmpty()) {
            return PhotoChanges.empty();
        }

        List<Long> targetItemIds = timelineEventItemService.findByTimelineEventId(targetEventId).stream()
                .map(TimelineEventItem::getTimelineItemId)
                .toList();
        Set<String> requestedRawIds = requestedPhotos.stream()
                .map(PhotoToAdd::rawId)
                .collect(Collectors.toSet());
        Set<String> linkedRawIds = timelineItemService.findSavedRawIds(targetItemIds, requestedRawIds);

        List<PhotoToAdd> newPhotos = requestedPhotos.stream()
                .filter(photo -> !linkedRawIds.contains(photo.rawId()))
                .toList();
        Set<String> newFilenames = new HashSet<>();
        for (PhotoToAdd newPhoto : newPhotos) {
            if (!newFilenames.add(newPhoto.filename())) {
                throw new IllegalArgumentException("filename is duplicated across new photos");
            }
        }
        return new PhotoChanges(newPhotos);
    }

    /**
     * 신규 PHOTO Item/junction을 insert하고 이번 호출로 대상 Event에 연결된 Item ID를 반환한다 — 생성 응답
     * 조립의 입력이며 PATCH는 반환을 무시한다(추가 조회 없음).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Long> link(UUID subjectId, Long timelineEventId, PhotoChanges photoChanges) {
        List<TimelineEventItem> links = new ArrayList<>();
        List<Long> linkedItemIds = new ArrayList<>();
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** MySQL timeline_items DATETIME 정밀도에 맞춰 소수 초가 조용히 손실되지 않게 한다. */
    private void requireSecondPrecision(LocalDateTime value, String field, int index) {
        if (value != null && value.getNano() != 0) {
            throw new IllegalArgumentException("photo " + field + " must use second precision: index=" + index);
        }
    }
}
