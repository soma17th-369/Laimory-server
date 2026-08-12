package com.laimory.server.timeline.photo.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laimory.server.common.id.SubjectId;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * staging({@code timeline_draft_source_items})·final({@code timeline_items}) PHOTO payload의
 * {@code photoUrl}을 legacy→subject namespace로 rewrite하는 도구(#284, 계획 §5.4) —
 * {@code rewrite-urls} 모드(cutover window 전용). 역방향(rollback)은 지원하지 않으며, cutover 후
 * legacy object는 별도 승인 하에 즉시 삭제한다(#285 runbook).
 *
 * <p>URL의 namespace 세그먼트만 치환한다 — filename·clientPhotoUri·다른 JSON 필드는 불변이며,
 * bulk update 뒤 같은 transaction에서 재조회해 photoUrl 외 필드 동등성과 기대 URL 일치를 검증한다.
 * 알 수 없는 namespace/도메인, photoUrl 누락, 재검증 불일치는 즉시 fail-closed 중단이고 전체
 * transaction이 rollback되어 부분 rewrite를 남기지 않는다. 이미 target namespace인 행은 skip한다
 * (멱등 재실행).
 *
 * <p>로그·예외에 raw userId/HMAC/subject/URL/JSON 값을 절대 남기지 않는다 — 건수만 보고한다.
 */
class PhotoUrlRewriteMigration {

    private static final String PHOTOS_SEGMENT = "/photos/";

    private final UserRepository userRepository;
    private final SubjectMappingService subjectMappingService;
    private final TimelineDraftSourceItemRepository draftSourceItemRepository;
    private final TimelineItemRepository timelineItemRepository;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final String cdnUrlPrefix;

    PhotoUrlRewriteMigration(UserRepository userRepository,
                             SubjectMappingService subjectMappingService,
                             TimelineDraftSourceItemRepository draftSourceItemRepository,
                             TimelineItemRepository timelineItemRepository,
                             EntityManager entityManager,
                             PlatformTransactionManager transactionManager,
                             String cdnDomain) {
        this.userRepository = userRepository;
        this.subjectMappingService = subjectMappingService;
        this.draftSourceItemRepository = draftSourceItemRepository;
        this.timelineItemRepository = timelineItemRepository;
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.cdnUrlPrefix = "https://" + cdnDomain + "/";
    }

    Result execute() {
        Map<String, String> targetNamespaceBySource = new HashMap<>();
        Set<String> targetNamespaces = new HashSet<>();
        List<Long> userIds = userRepository.findAllUserIds();
        for (Long userId : userIds) {
            SubjectId subjectId = subjectMappingService.getRequired(userId);
            String subjectNamespace = PhotoObjectKeys.subjectNamespace(subjectId);
            targetNamespaceBySource.put(PhotoObjectKeys.sha256hex(userId), subjectNamespace);
            targetNamespaces.add(subjectNamespace);
        }

        // 두 테이블을 한 transaction으로 묶는다 — 중단 시 어느 테이블에도 부분 rewrite가 남지 않는다.
        return transactionTemplate.execute(status -> {
            TableCounts draft = rewriteTable(
                    draftSourceItemRepository.findByItemType(ItemType.PHOTO).stream()
                            .map(row -> new RowView(
                                    row.getTimelineDraftSourceItemId(), row.getPayload()))
                            .toList(),
                    targetNamespaceBySource, targetNamespaces,
                    draftSourceItemRepository::updatePayload,
                    ids -> draftSourceItemRepository.findAllById(ids).stream()
                            .collect(Collectors.toMap(
                                    TimelineDraftSourceItem::getTimelineDraftSourceItemId,
                                    TimelineDraftSourceItem::getPayload)));
            TableCounts finalItems = rewriteTable(
                    timelineItemRepository.findByItemType(ItemType.PHOTO).stream()
                            .map(row -> new RowView(row.getTimelineItemId(), row.getPayload()))
                            .toList(),
                    targetNamespaceBySource, targetNamespaces,
                    timelineItemRepository::updatePayload,
                    ids -> timelineItemRepository.findAllById(ids).stream()
                            .collect(Collectors.toMap(
                                    TimelineItem::getTimelineItemId, TimelineItem::getPayload)));
            return new Result(userIds.size(),
                    draft.examined(), draft.rewritten(), draft.alreadyTarget(),
                    finalItems.examined(), finalItems.rewritten(), finalItems.alreadyTarget());
        });
    }

    private TableCounts rewriteTable(List<RowView> rows,
                                     Map<String, String> targetNamespaceBySource,
                                     Set<String> targetNamespaces,
                                     PayloadUpdater updater,
                                     Function<Collection<Long>, Map<Long, JsonNode>> reReader) {
        long alreadyTarget = 0;
        List<PlannedRewrite> planned = new ArrayList<>();
        for (RowView row : rows) {
            if (!(row.payload() instanceof ObjectNode payload)) {
                throw abort("PHOTO payload가 JSON object가 아님", rows.size(), planned.size(),
                        alreadyTarget);
            }
            JsonNode urlNode = payload.get("photoUrl");
            if (urlNode == null || !urlNode.isTextual()) {
                throw abort("photoUrl 누락 또는 비텍스트", rows.size(), planned.size(), alreadyTarget);
            }
            ParsedUrl parsed = parseUrl(urlNode.asText(), rows.size(), planned.size(), alreadyTarget);
            if (targetNamespaces.contains(parsed.namespace())) {
                alreadyTarget++; // 이미 target namespace — 멱등 재실행
                continue;
            }
            String targetNamespace = targetNamespaceBySource.get(parsed.namespace());
            if (targetNamespace == null) {
                throw abort("알 수 없는 namespace", rows.size(), planned.size(), alreadyTarget);
            }
            String rewrittenUrl = cdnUrlPrefix + targetNamespace + parsed.remainder();
            ObjectNode rewrittenPayload = payload.deepCopy();
            rewrittenPayload.put("photoUrl", rewrittenUrl);
            int updated = updater.update(row.id(), rewrittenPayload);
            if (updated != 1) {
                throw abort("update 대상 행 소실", rows.size(), planned.size(), alreadyTarget);
            }
            planned.add(new PlannedRewrite(row.id(), payload, rewrittenUrl));
        }

        if (!planned.isEmpty()) {
            verifyStored(planned, reReader, rows.size(), alreadyTarget);
        }
        return new TableCounts(rows.size(), planned.size(), alreadyTarget);
    }

    /**
     * bulk update가 영속성 컨텍스트를 우회하므로 clear 후 DB에서 재조회해, 저장본의 photoUrl이 기대값과
     * 일치하고 photoUrl 외 모든 필드가 원본과 동등한지 검증한다(MySQL JSON 정규화와 무관한 tree 비교).
     */
    private void verifyStored(List<PlannedRewrite> planned,
                              Function<Collection<Long>, Map<Long, JsonNode>> reReader,
                              long examined, long alreadyTarget) {
        entityManager.clear();
        Map<Long, JsonNode> storedById = reReader.apply(planned.stream()
                .map(PlannedRewrite::id)
                .toList());
        long missing = 0;
        long mismatches = 0;
        for (PlannedRewrite rewrite : planned) {
            JsonNode stored = storedById.get(rewrite.id());
            if (stored == null) {
                missing++;
                continue;
            }
            JsonNode storedUrl = stored.get("photoUrl");
            boolean urlMatches = storedUrl != null && storedUrl.isTextual()
                    && storedUrl.asText().equals(rewrite.expectedUrl());
            if (!urlMatches || !withoutPhotoUrl(stored).equals(withoutPhotoUrl(rewrite.original()))) {
                mismatches++;
            }
        }
        if (missing + mismatches > 0) {
            throw new PhotoMigrationAbortedException("rewrite 재검증 실패: rowsExamined=" + examined
                    + " rowsRewritten=" + planned.size()
                    + " rowsAlreadyTarget=" + alreadyTarget
                    + " missing=" + missing
                    + " mismatches=" + mismatches);
        }
    }

    private ParsedUrl parseUrl(String url, long examined, long rewritten, long alreadyTarget) {
        if (!url.startsWith(cdnUrlPrefix)) {
            throw abort("photoUrl CDN 도메인 불일치", examined, rewritten, alreadyTarget);
        }
        String path = url.substring(cdnUrlPrefix.length());
        int firstSlash = path.indexOf('/');
        if (firstSlash <= 0) {
            throw abort("photoUrl 경로 형식 불일치", examined, rewritten, alreadyTarget);
        }
        String namespace = path.substring(0, firstSlash);
        String remainder = path.substring(firstSlash);
        if (!remainder.startsWith(PHOTOS_SEGMENT)
                || remainder.length() == PHOTOS_SEGMENT.length()) {
            throw abort("photoUrl 경로 형식 불일치", examined, rewritten, alreadyTarget);
        }
        return new ParsedUrl(namespace, remainder);
    }

    private static PhotoMigrationAbortedException abort(String reason, long examined, long rewritten,
                                                        long alreadyTarget) {
        // 첫 불일치에서 즉시 중단(fail-closed) — transaction rollback으로 부분 rewrite도 남지 않는다.
        return new PhotoMigrationAbortedException("photoUrl rewrite 중단(" + reason + "): mismatches=1"
                + " rowsExamined=" + examined
                + " rowsRewritten=" + rewritten
                + " rowsAlreadyTarget=" + alreadyTarget);
    }

    private static JsonNode withoutPhotoUrl(JsonNode payload) {
        JsonNode copy = payload.deepCopy();
        if (copy instanceof ObjectNode objectNode) {
            objectNode.remove("photoUrl");
        }
        // 비-object 저장본은 그대로 반환 — object인 원본과의 동등 비교에서 불일치로 걸린다(fail-closed).
        return copy;
    }

    @FunctionalInterface
    private interface PayloadUpdater {
        int update(long id, JsonNode payload);
    }

    private record RowView(long id, JsonNode payload) {
    }

    private record PlannedRewrite(long id, JsonNode original, String expectedUrl) {
    }

    private record ParsedUrl(String namespace, String remainder) {
    }

    private record TableCounts(long examined, long rewritten, long alreadyTarget) {
    }

    /** 건수 전용 실행 결과 — 식별자 값 없음. */
    record Result(long usersProcessed,
                  long stagingRowsExamined, long stagingRowsRewritten, long stagingRowsAlreadyTarget,
                  long finalRowsExamined, long finalRowsRewritten, long finalRowsAlreadyTarget) {
    }
}
