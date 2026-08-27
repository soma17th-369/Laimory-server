package com.laimory.server.timeline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineEventItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * junction이 0개가 된 final Item을 batch 단위로 수렴시키는 transaction 소유자.
 *
 * <p>Item과 junction은 항상 한 transaction에서 insert되므로(AI 결과 store·수동 PHOTO link) 커밋된
 * 0-junction Item은 언제나 쓰레기다. 정리 규칙은 기존 삭제 흐름과 같다 — 유효한 PHOTO는 delete job으로
 * 넘겨 S3 삭제를 worker에 맡기고, non-PHOTO와 job을 만들 수 없는 손상 PHOTO만 즉시 hard delete한다.
 *
 * <p>batch 한 번은 <b>탐색(무잠금) → PK 지정 claim({@code FOR UPDATE SKIP LOCKED}) → 잠금 하 재검증 →
 * key 그룹 분류 → enqueue/삭제</b> 순서다. 탐색 statement에 잠금을 걸지 않는 이유와 그룹 규칙의 근거는
 * 각 단계 주석에 있다. S3를 포함한 외부 호출은 하지 않으므로 잠금 보유 시간이 짧다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineOrphanItemSweepService {

    private final TimelineItemService timelineItemService;
    private final TimelineEventItemService timelineEventItemService;
    private final TimelinePhotoDeleteJobService timelinePhotoDeleteJobService;
    private final ObjectMapper objectMapper;

    /**
     * 커서 이후 한 batch를 처리하고 다음 커서를 돌려준다.
     *
     * <p>{@code scanned == 0}일 때만 테이블을 다 훑은 것이다. claim이나 재검증이 모두 탈락해
     * {@code claimed == 0}이어도 run을 끝내면 안 된다 — 다른 host가 선점한 구간에서 조기 종료해 그날
     * 나머지를 건너뛰게 된다.
     */
    @Transactional
    public SweepBatchResult sweepBatch(long cursor, int limit) {
        List<TimelineItem> scanned = timelineItemService.findOrphanCandidates(cursor, limit);
        if (scanned.isEmpty()) {
            return SweepBatchResult.exhausted(cursor);
        }
        long nextCursor = scanned.get(scanned.size() - 1).getTimelineItemId();
        List<Long> scannedIds = scanned.stream().map(TimelineItem::getTimelineItemId).toList();

        List<TimelineItem> claimed = timelineItemService.claimOrphanCandidates(scannedIds);
        int skippedLocked = scanned.size() - claimed.size();
        if (claimed.isEmpty()) {
            return SweepBatchResult.nothingClaimed(scanned.size(), skippedLocked, nextCursor);
        }

        List<TimelineItem> actionable = revalidate(claimed);
        int revalidationDropped = claimed.size() - actionable.size();

        Counters counters = new Counters();
        List<Long> immediateDeleteIds = new ArrayList<>();
        List<KeyedPhoto> keyedPhotos = new ArrayList<>();
        for (TimelineItem item : actionable) {
            if (item.getItemType() != ItemType.PHOTO) {
                counters.nonPhotoDeleted++;
                immediateDeleteIds.add(item.getTimelineItemId());
                continue;
            }
            Optional<String> objectKey = restoreObjectKey(item);
            if (objectKey.isEmpty()) {
                counters.invalidDeleted++;
                immediateDeleteIds.add(item.getTimelineItemId());
                continue;
            }
            keyedPhotos.add(new KeyedPhoto(item, objectKey.get()));
        }

        schedulePhotoDeletions(keyedPhotos, counters, immediateDeleteIds);
        timelineItemService.deleteByIds(immediateDeleteIds);

        return new SweepBatchResult(scanned.size(), claimed.size(), skippedLocked, revalidationDropped,
                counters.photoScheduled, counters.photoAlreadyJob, counters.keyShared,
                counters.invalidDeleted, counters.nonPhotoDeleted, nextCursor, false);
    }

    /**
     * claim 이후 다시 한 번 junction·job을 확인해 탐색과 claim 사이에 상태가 바뀐 행을 뺀다.
     *
     * <p>job 조회는 반드시 current read다 — 무잠금 탐색이 이 transaction의 snapshot을 이미 고정했기
     * 때문에 일반 SELECT는 그 뒤 commit된 job을 보지 못하고, 그러면 job 있는 Item을 지워 FK 위반이 난다.
     *
     * <p>junction 조회는 snapshot read로 둔다. 0-junction·job 없는 Item에 junction을 붙이는 요청 경로가
     * 없고(재연결은 job 경유, rawId 재사용은 record junction 경유), 설령 놓쳐 job을 만들어도 다음 날
     * worker의 재검증이 취소한다. 반대로 여기서 {@code FOR SHARE}를 쓰면 존재하지 않는 key 구간에
     * gap lock이 걸려 draft finalize의 junction insert를 막을 수 있다.
     */
    private List<TimelineItem> revalidate(List<TimelineItem> claimed) {
        List<Long> claimedIds = claimed.stream().map(TimelineItem::getTimelineItemId).toList();
        Set<Long> withJob = timelinePhotoDeleteJobService.findItemIdsWithJob(claimedIds);
        Set<Long> linked = timelineEventItemService.findByTimelineItemIds(claimedIds).stream()
                .map(TimelineEventItem::getTimelineItemId)
                .collect(Collectors.toSet());
        return claimed.stream()
                .filter(item -> !withJob.contains(item.getTimelineItemId()))
                .filter(item -> !linked.contains(item.getTimelineItemId()))
                .toList();
    }

    /**
     * object key 그룹 규칙으로 job 소유자를 정하고 나머지 행은 삭제 대상에 넣는다.
     *
     * <ul>
     *   <li>같은 key를 <b>junction이 살아 있는</b> Item이 참조하면 job을 만들지 않는다 — S3 객체는 그
     *       Item의 생애주기가 계속 소유한다. 살아 있는 쪽의 key는 저장된 URL이 아니라 소유 subject에서
     *       계산하므로 그 Item의 {@code photoUrl}이 손상돼 있어도 놓치지 않는다.</li>
     *   <li>전부 orphan이면 같은 key를 참조하는 orphan 중 <b>최소 id</b>가 job 소유자다. 삭제 순서에
     *       의존하지 않아 같은 batch 안이든 밖이든 같은 결과로 수렴한다.</li>
     * </ul>
     */
    private void schedulePhotoDeletions(List<KeyedPhoto> keyedPhotos, Counters counters,
                                        List<Long> immediateDeleteIds) {
        if (keyedPhotos.isEmpty()) {
            return;
        }
        Set<String> filenames = keyedPhotos.stream()
                .map(KeyedPhoto::filename)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> liveObjectKeys = timelineItemService.findLiveObjectKeysByFilenames(filenames);
        Map<String, Long> ownerIdByObjectKey = ownerIdByObjectKey(filenames);

        for (KeyedPhoto photo : keyedPhotos) {
            long itemId = photo.item().getTimelineItemId();
            if (liveObjectKeys.contains(photo.objectKey())) {
                counters.keyShared++;
                immediateDeleteIds.add(itemId);
                continue;
            }
            Long ownerId = ownerIdByObjectKey.get(photo.objectKey());
            if (ownerId != null && ownerId != itemId) {
                counters.keyShared++;
                immediateDeleteIds.add(itemId);
                continue;
            }
            if (timelinePhotoDeleteJobService.insertIfAbsent(itemId, photo.objectKey())) {
                counters.photoScheduled++;
                continue;
            }
            // insert ignore는 item UNIQUE와 object UNIQUE 어느 쪽으로 막혀도 실패를 구분하지 않는다.
            // 자기 job이 생겼으면 다른 process가 방금 만든 것이라 행을 보존하고, 아니면 다른 Item이
            // 같은 object key를 선점한 것이라 행만 지운다(FK 위반 회피).
            if (timelinePhotoDeleteJobService.findItemIdsWithJob(List.of(itemId)).contains(itemId)) {
                counters.photoAlreadyJob++;
            } else {
                counters.keyShared++;
                immediateDeleteIds.add(itemId);
            }
        }
    }

    /** 같은 object key를 참조하는 orphan 중 최소 id. 복원 불가한 행은 소유자 후보에서 빠진다. */
    private Map<String, Long> ownerIdByObjectKey(Set<String> filenames) {
        Map<String, Long> ownerIdByObjectKey = new HashMap<>();
        for (TimelineItemRepository.OrphanPhotoKeyRow row
                : timelineItemService.findUnlinkedPhotoKeysByFilenames(filenames)) {
            PhotoObjectKeys.objectKeyFromServingUrl(row.getPhotoUrl()).ifPresent(objectKey ->
                    ownerIdByObjectKey.merge(objectKey, row.getTimelineItemId(), Math::min));
        }
        return ownerIdByObjectKey;
    }

    /**
     * 저장본에서 full object key를 복원한다. junction을 잃어 subject를 알 수 없으므로 {@code photoUrl}
     * path가 유일한 경로다. payload 파싱 실패·filename 부재·URL 손상·URL과 payload filename 불일치는
     * 모두 "복원 불가"로 같은 처리(job 생략 + 행 삭제, S3 orphan 허용 — 기존 삭제 흐름과 같은 규칙)다.
     */
    private Optional<String> restoreObjectKey(TimelineItem item) {
        PhotoPayload photo;
        try {
            photo = objectMapper.treeToValue(item.getPayload(), PhotoPayload.class);
        } catch (JsonProcessingException | RuntimeException exception) {
            log.warn("orphan PHOTO payload 파싱 실패, 삭제 job 생략: timelineItemId={} exceptionType={}",
                    item.getTimelineItemId(), exception.getClass().getSimpleName());
            return Optional.empty();
        }
        if (photo == null || photo.filename() == null || photo.filename().isBlank()) {
            log.warn("orphan PHOTO filename 없음, 삭제 job 생략: timelineItemId={}", item.getTimelineItemId());
            return Optional.empty();
        }
        Optional<String> objectKey = PhotoObjectKeys.objectKeyFromServingUrl(photo.photoUrl());
        if (objectKey.isEmpty() || !objectKey.get().endsWith("/" + photo.filename())) {
            log.warn("orphan PHOTO object key 복원 실패, 삭제 job 생략: timelineItemId={}",
                    item.getTimelineItemId());
            return Optional.empty();
        }
        return objectKey;
    }

    private record KeyedPhoto(TimelineItem item, String objectKey) {

        String filename() {
            return objectKey.substring(objectKey.lastIndexOf('/') + 1);
        }
    }

    private static final class Counters {

        private int photoScheduled;
        private int photoAlreadyJob;
        private int keyShared;
        private int invalidDeleted;
        private int nonPhotoDeleted;
    }

    /**
     * batch 결과. {@code scanned}와 {@code claimed}를 분리해 담는다 — 잠금 경합으로 건너뛴 양이 보이지
     * 않으면 run 요약을 "다 훑었다"로 오독하게 된다.
     */
    public record SweepBatchResult(
            int scanned,
            int claimed,
            int skippedLocked,
            int revalidationDropped,
            int photoScheduled,
            int photoAlreadyJob,
            int keyShared,
            int invalidDeleted,
            int nonPhotoDeleted,
            long nextCursor,
            boolean exhausted) {

        private static SweepBatchResult exhausted(long cursor) {
            return new SweepBatchResult(0, 0, 0, 0, 0, 0, 0, 0, 0, cursor, true);
        }

        private static SweepBatchResult nothingClaimed(int scanned, int skippedLocked, long nextCursor) {
            return new SweepBatchResult(scanned, 0, skippedLocked, 0, 0, 0, 0, 0, 0, nextCursor, false);
        }
    }
}
