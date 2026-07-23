package com.laimory.server.timeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.TimelineEventResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineEvent;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoUrlService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event PATCH의 단일 DB transaction writer. outer edit service가 사전 검증과 선택적 date guard를 끝낸 뒤
 * 이 별도 Spring bean을 호출하므로, 메서드 반환 전에 Event·memo·Item·junction commit이 완료된다.
 */
@Service
@RequiredArgsConstructor
public class TimelineEventEditTransactionService {

    private final TimelineEventService timelineEventService;
    private final DailyRecordService dailyRecordService;
    private final TimelineEventItemService timelineEventItemService;
    private final TimelineItemService timelineItemService;
    private final TimelineEventResponseAssembler timelineEventResponseAssembler;
    private final PhotoUrlService photoUrlService;
    private final ObjectMapper objectMapper;

    /** 소유권·DRAFT를 재확인하고 Event 필드와 수동 PHOTO graph를 원자적으로 반영한다. */
    @Transactional
    public TimelineEventResponse updateEvent(long userId, Long timelineEventId, TimelineEventEditCommand command) {
        TimelineEvent event = timelineEventService.findById(timelineEventId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        DailyRecord record = requireOwnedDraftRecord(userId, event.getDailyRecordId());

        PhotoChanges photoChanges = resolvePhotoChanges(record, timelineEventId, command.photosToAdd());

        TimelineEventType targetEventType = command.eventType() != null
                ? command.eventType() : event.getEventType();
        event.updateDetails(targetEventType, command.title(), command.subtitle(), command.startAt(), command.endAt());
        if (command.memoPresent()) {
            event.updateMemo(command.memo());
        }

        List<TimelineEventItem> links = new ArrayList<>();
        for (Long itemId : photoChanges.existingItemIdsToLink()) {
            links.add(TimelineEventItem.of(timelineEventId, itemId));
        }
        for (TimelineEventEditCommand.PhotoToAdd photo : photoChanges.newPhotos()) {
            PhotoPayload payload = new PhotoPayload(
                    photo.filename(), photo.clientPhotoUri(), photo.latitude(), photo.longitude(),
                    null, photoUrlService.buildUrl(photo.filename(), userId));
            TimelineItem item = timelineItemService.save(TimelineItem.of(
                    ItemType.PHOTO, photo.rawId(), photo.startAt(), photo.endAt(),
                    objectMapper.valueToTree(payload)));
            links.add(TimelineEventItem.of(timelineEventId, item.getTimelineItemId()));
        }
        if (!links.isEmpty()) {
            timelineEventItemService.saveAll(links);
        }
        return timelineEventResponseAssembler.toResponse(event);
    }

    /**
     * 같은 DailyRecord의 rawId 후보를 new/reuse/no-op으로 분류한다. 분류와 모든 DB-dependent 검증을
     * entity mutation보다 먼저 끝내 validation 실패 시 Event/memo도 그대로 둔다.
     */
    private PhotoChanges resolvePhotoChanges(DailyRecord record, Long targetEventId,
                                             List<TimelineEventEditCommand.PhotoToAdd> requestedPhotos) {
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
                .map(TimelineEventEditCommand.PhotoToAdd::rawId)
                .collect(Collectors.toSet());
        List<TimelineItem> matchingItems = timelineItemService.findByIdsAndRawIds(recordItemIds, requestedRawIds);

        Map<String, List<TimelineItem>> itemsByRawId = matchingItems.stream()
                .collect(Collectors.groupingBy(TimelineItem::getRawId, HashMap::new, Collectors.toList()));
        Set<Long> targetItemIds = recordLinks.stream()
                .filter(link -> targetEventId.equals(link.getTimelineEventId()))
                .map(TimelineEventItem::getTimelineItemId)
                .collect(Collectors.toSet());

        List<Long> existingItemIdsToLink = new ArrayList<>();
        List<TimelineEventEditCommand.PhotoToAdd> newPhotos = new ArrayList<>();
        for (TimelineEventEditCommand.PhotoToAdd requested : requestedPhotos) {
            List<TimelineItem> candidates = itemsByRawId.getOrDefault(requested.rawId(), List.of());
            if (candidates.stream().anyMatch(item -> item.getItemType() != ItemType.PHOTO)) {
                throw new IllegalArgumentException("rawId is already used by a non-PHOTO item");
            }
            if (candidates.isEmpty()) {
                newPhotos.add(requested);
                continue;
            }

            TimelineItem reusable = candidates.stream()
                    .filter(item -> targetItemIds.contains(item.getTimelineItemId()))
                    .min(Comparator.comparing(TimelineItem::getTimelineItemId))
                    .orElseGet(() -> candidates.stream()
                            .min(Comparator.comparing(TimelineItem::getTimelineItemId))
                            .orElseThrow());
            if (!targetItemIds.contains(reusable.getTimelineItemId())) {
                existingItemIdsToLink.add(reusable.getTimelineItemId());
            }
        }

        Set<String> newFilenames = new HashSet<>();
        for (TimelineEventEditCommand.PhotoToAdd newPhoto : newPhotos) {
            if (!newFilenames.add(newPhoto.filename())) {
                throw new IllegalArgumentException("filename is duplicated across new photos");
            }
        }
        return new PhotoChanges(existingItemIdsToLink, newPhotos);
    }

    private DailyRecord requireOwnedDraftRecord(long userId, Long dailyRecordId) {
        DailyRecord record = dailyRecordService.findById(dailyRecordId)
                .filter(owned -> owned.getUserId() == userId)
                .orElseThrow(() -> new BusinessException(ExceptionType.TIMELINE_EVENT_NOT_FOUND));
        if (record.getStatus() == DailyRecordStatus.SAVED) {
            throw new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
        }
        return record;
    }

    private record PhotoChanges(
            List<Long> existingItemIdsToLink,
            List<TimelineEventEditCommand.PhotoToAdd> newPhotos
    ) {
        private static PhotoChanges empty() {
            return new PhotoChanges(List.of(), List.of());
        }
    }
}
