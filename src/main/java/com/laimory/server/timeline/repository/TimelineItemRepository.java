package com.laimory.server.timeline.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineItem;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimelineItemRepository extends JpaRepository<TimelineItem, Long> {

    // append 시 이미 저장된 source item을 rawId로 제외하기 위한 projection 조회.
    // rawId만 select 한다(JSON payload를 든 전체 엔티티 로드 회피). 후보 itemIds·rawIds로 좁혀 전체 스캔을 막는다.
    @Query("select ti.rawId from TimelineItem ti where ti.timelineItemId in :itemIds and ti.rawId in :rawIds")
    List<String> findRawIdsByTimelineItemIdInAndRawIdIn(@Param("itemIds") Collection<Long> itemIds,
                                                        @Param("rawIds") Collection<String> rawIds);

    /** Event PATCH PHOTO append의 rawId type/reuse/no-op 분류용 full entity 조회. */
    @Query("select ti from TimelineItem ti where ti.timelineItemId in :itemIds and ti.rawId in :rawIds")
    List<TimelineItem> findByTimelineItemIdInAndRawIdIn(@Param("itemIds") Collection<Long> itemIds,
                                                        @Param("rawIds") Collection<String> rawIds);

    /** PHOTO migration 도구(#284) 전용 — payload {@code photoUrl} rewrite 대상 전체 조회. */
    List<TimelineItem> findByItemType(ItemType itemType);

    /**
     * PHOTO migration 도구(#284) 전용 — payload만 교체하는 bulk update. auditing·영속성 컨텍스트를
     * 우회하므로 호출자가 transaction 안에서 {@code EntityManager#clear} 후 재검증한다.
     * {@code @Transactional}을 붙이지 않아 활성 transaction 없이는 실패한다(원자성 보장).
     */
    @Modifying
    @Query("update TimelineItem ti set ti.payload = :payload where ti.timelineItemId = :id")
    int updatePayload(@Param("id") long id, @Param("payload") JsonNode payload);
}
