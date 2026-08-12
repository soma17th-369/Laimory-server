package com.laimory.server.timeline.photo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laimory.server.common.id.SubjectId;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineItem;
import com.laimory.server.timeline.photo.PhotoObjectKeys;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemRepository;
import com.laimory.server.timeline.repository.TimelineItemRepository;
import com.laimory.server.user.NewUserProvisioner;
import com.laimory.server.user.Provider;
import com.laimory.server.user.SubjectLookupKeyDeriver;
import com.laimory.server.user.SubjectMappingService;
import com.laimory.server.user.UserRepository;
import com.laimory.server.user.UserSubjectLinkRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * staging/final PHOTO payload {@code photoUrl} rewrite 도구의 실 MySQL 왕복 검증(#284) —
 * rewrite 후 photoUrl 외 필드 동등성, 멱등 재실행, 알 수 없는 namespace의 fail-closed 중단과
 * transaction rollback.
 *
 * <p>executor는 property 게이트 밖에서 직접 조립한다 — {@code app.photo.migration.mode}를 켠
 * 컨텍스트는 기동 시 runner가 실행되므로 테스트에서 켜지 않는다. rewrite는 두 테이블의 PHOTO 전 행을
 * 스캔하므로 이 테스트는 다른 테스트처럼 자신이 만든 행을 모두 정리한다는 전제를 공유한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@TestPropertySource(properties = "photo.cdn.domain=cdn.migration.test")
@Tag("integration")
class PhotoUrlRewriteMigrationIntegrationTest {

    private static final String CDN_DOMAIN = "cdn.migration.test";
    private static final String FILENAME = "0190b2c3-d4e5-7f6a-8b9c-0d1e2f3a4b5c.jpg";
    private static final String RAW_ID = "0190a1b2-0002-7000-8000-000000000002";

    @Autowired
    private NewUserProvisioner newUserProvisioner;
    @Autowired
    private SubjectMappingService subjectMappingService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TimelineDraftSourceItemRepository draftSourceItemRepository;
    @Autowired
    private TimelineItemRepository timelineItemRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ObjectMapper objectMapper;
    // 정리 전용 — repository·deriver 직접 접근은 테스트 한정 예외다(arch rule은 main 코드만 검사).
    @Autowired
    private SubjectLookupKeyDeriver subjectLookupKeyDeriver;
    @Autowired
    private UserSubjectLinkRepository userSubjectLinkRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private PhotoUrlRewriteMigration migration;

    private Long userId;
    private String legacyUrl;
    private String subjectUrl;
    private final List<Long> createdDraftIds = new ArrayList<>();
    private final List<Long> createdItemIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        migration = new PhotoUrlRewriteMigration(userRepository, subjectMappingService,
                draftSourceItemRepository, timelineItemRepository, entityManager, transactionManager,
                CDN_DOMAIN);
        userId = newUserProvisioner
                .provision(Provider.GOOGLE, "photo-mig-" + UUID.randomUUID(), null, null)
                .getUserId();
        SubjectId subjectId = subjectMappingService.getRequired(userId);
        legacyUrl = "https://" + CDN_DOMAIN + "/" + PhotoObjectKeys.sha256hex(userId)
                + "/photos/" + FILENAME;
        subjectUrl = "https://" + CDN_DOMAIN + "/" + PhotoObjectKeys.subjectNamespace(subjectId)
                + "/photos/" + FILENAME;
    }

    @AfterEach
    void cleanUp() {
        createdDraftIds.forEach(draftSourceItemRepository::deleteById);
        createdDraftIds.clear();
        createdItemIds.forEach(timelineItemRepository::deleteById);
        createdItemIds.clear();
        userSubjectLinkRepository.deleteById(subjectLookupKeyDeriver.deriveCurrent(userId));
        userRepository.deleteById(userId);
    }

    private ObjectNode photoPayload(String photoUrl) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("filename", FILENAME);
        payload.put("clientPhotoUri", "content://media/external/images/media/1001");
        payload.put("latitude", 37.5);
        payload.put("longitude", 127.0);
        payload.put("photoUrl", photoUrl);
        return payload;
    }

    private long saveDraftRow(JsonNode payload) {
        TimelineDraftSourceItem row = draftSourceItemRepository.save(TimelineDraftSourceItem.of(
                UUID.randomUUID().toString(), userId, ItemType.PHOTO, RAW_ID, null, null, payload));
        createdDraftIds.add(row.getTimelineDraftSourceItemId());
        return row.getTimelineDraftSourceItemId();
    }

    private long saveItemRow(JsonNode payload) {
        TimelineItem row = timelineItemRepository
                .save(TimelineItem.of(ItemType.PHOTO, RAW_ID, null, null, payload));
        createdItemIds.add(row.getTimelineItemId());
        return row.getTimelineItemId();
    }

    private static JsonNode withoutPhotoUrl(JsonNode payload) {
        ObjectNode copy = payload.deepCopy();
        copy.remove("photoUrl");
        return copy;
    }

    @Test
    void rewrite_rewritesPhotoUrlOnlyInBothTables() {
        ObjectNode original = photoPayload(legacyUrl);
        long draftId = saveDraftRow(original);
        long itemId = saveItemRow(original);

        PhotoUrlRewriteMigration.Result result = migration.execute();

        assertThat(result.stagingRowsRewritten()).isEqualTo(1);
        assertThat(result.finalRowsRewritten()).isEqualTo(1);
        JsonNode draftStored = draftSourceItemRepository.findById(draftId).orElseThrow().getPayload();
        JsonNode itemStored = timelineItemRepository.findById(itemId).orElseThrow().getPayload();
        assertThat(draftStored.get("photoUrl").asText()).isEqualTo(subjectUrl);
        assertThat(itemStored.get("photoUrl").asText()).isEqualTo(subjectUrl);
        // filename·clientPhotoUri·좌표 등 photoUrl 외 모든 필드는 불변이다.
        assertThat(withoutPhotoUrl(draftStored)).isEqualTo(withoutPhotoUrl(original));
        assertThat(withoutPhotoUrl(itemStored)).isEqualTo(withoutPhotoUrl(original));
    }

    @Test
    void rewrite_secondRunIsIdempotent() {
        long draftId = saveDraftRow(photoPayload(legacyUrl));

        migration.execute();
        PhotoUrlRewriteMigration.Result secondRun = migration.execute();

        assertThat(secondRun.stagingRowsRewritten()).isZero();
        assertThat(secondRun.stagingRowsAlreadyTarget()).isEqualTo(1);
        assertThat(draftSourceItemRepository.findById(draftId).orElseThrow()
                .getPayload().get("photoUrl").asText()).isEqualTo(subjectUrl);
    }

    @Test
    void unknownNamespace_abortsAndRollsBackWholeTransaction() {
        long goodDraftId = saveDraftRow(photoPayload(legacyUrl));
        // 어떤 사용자 매핑에도 속하지 않는 64자 hex namespace — fail-closed 대상.
        String unknownNamespaceUrl = "https://" + CDN_DOMAIN + "/" + "ab".repeat(32)
                + "/photos/" + FILENAME;
        saveDraftRow(photoPayload(unknownNamespaceUrl));

        assertThatThrownBy(migration::execute)
                .isInstanceOf(PhotoMigrationAbortedException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .doesNotContain("https://")
                        .doesNotContain("ab".repeat(32))
                        .doesNotContain(FILENAME));

        // 같은 transaction의 앞선 rewrite도 rollback되어 부분 rewrite가 남지 않는다.
        assertThat(draftSourceItemRepository.findById(goodDraftId).orElseThrow()
                .getPayload().get("photoUrl").asText()).isEqualTo(legacyUrl);
    }

    @Test
    void missingPhotoUrl_abortsFailClosed() {
        ObjectNode payload = photoPayload(legacyUrl);
        payload.remove("photoUrl");
        saveDraftRow(payload);

        assertThatThrownBy(migration::execute)
                .isInstanceOf(PhotoMigrationAbortedException.class)
                .hasMessageContaining("photoUrl 누락");
    }
}
