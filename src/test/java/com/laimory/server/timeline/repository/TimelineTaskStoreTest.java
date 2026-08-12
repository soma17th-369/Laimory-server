package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.laimory.server.testsupport.TestSubjects.id;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import static com.laimory.server.testsupport.TaskTokenFixtures.tokenHashes;

import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.common.id.SubjectId;
import com.laimory.server.common.id.SubjectIdCodec;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * TimelineTaskStore 직렬화 왕복 단위테스트(인프라 없음).
 * task shape(내부 계약 — AI는 더 이상 Redis를 읽지 않음)와 논리 키 전달을 검증한다.
 * 환경 prefix 부착은 RedisGateway의 책임이라 여기선 논리 키만 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class TimelineTaskStoreTest {

    @Mock
    private RedisGateway redis;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private TimelineTaskStore store;

    private static final Instant STARTED_AT = Instant.parse("2026-05-08T13:41:07Z");
    private static final SubjectId SUBJECT = id(7L);
    private static final SubjectId OTHER_SUBJECT = id(8L);
    private static final String USER_INDEX_KEY = TimelineTaskStore.subjectProcessingIndexKey(SUBJECT);

    @BeforeEach
    void setUp() {
        store = new TimelineTaskStore(redis, objectMapper);
    }

    private TimelineDraftTask processingTask() {
        return TimelineDraftTask.processing(SUBJECT, 42L,
                new TimelineDraftTask.TimelineWindow(
                        LocalDate.of(2026, 5, 8).atTime(18, 30), LocalDate.of(2026, 5, 8).atTime(22, 41)),
                tokenHashes("token-hash"), STARTED_AT);
    }

    @Test
    void save_serializesWithKeyAndTtl() throws Exception {
        TimelineDraftTask task = TimelineDraftTask.success(SUBJECT, 42L, tokenHashes("token-hash"));

        store.save("abc", task, Duration.ofHours(24));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redis).setAndRemoveFromSortedSets(
                keyCaptor.capture(), jsonCaptor.capture(), ttlCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(TimelineTaskStore.PROCESSING_INDEX_KEY),
                org.mockito.ArgumentMatchers.eq(USER_INDEX_KEY),
                org.mockito.ArgumentMatchers.eq("abc"));

        assertThat(keyCaptor.getValue()).isEqualTo("timeline:draft-task:abc");
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(24));
        TimelineDraftTask roundTripped = objectMapper.readValue(jsonCaptor.getValue(), TimelineDraftTask.class);
        assertThat(roundTripped).isEqualTo(task);
    }

    @Test
    void save_processingShape_hasRecordIdWindowStartedAtOwner_withoutRecordMetadata() throws Exception {
        // 축소된 shape: record 메타데이터(recordDate/recordAt/recordTimezone/userMemory)는 더 이상 없다 —
        // DailyRecord가 선생성되어 DB가 단일 권위다. dailyRecordId는 PROCESSING부터 실린다.
        store.save("abc", processingTask(), Duration.ofMinutes(3));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis).setAndAddToSortedSets(anyString(), jsonCaptor.capture(), any(),
                anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
        String json = jsonCaptor.getValue();
        assertThat(json).contains("\"dailyRecordId\":42");
        assertThat(json).contains("\"subjectId\":\"" + SubjectIdCodec.encode(SUBJECT) + "\"");
        assertThat(json).doesNotContain("userId");
        // PROCESSING 시작 시각은 UTC ISO-8601 문자열로 고정된다(숫자 timestamp 설정 무관 — @JsonFormat STRING).
        assertThat(json).contains("\"processingStartedAt\":\"2026-05-08T13:41:07Z\"");
        assertThat(json).contains("\"timelineWindow\"");
        assertThat(json).doesNotContain("recordDate");
        assertThat(json).doesNotContain("recordAt");
        assertThat(json).doesNotContain("recordTimezone");
        assertThat(json).doesNotContain("userMemory");
    }

    @Test
    void save_processingStartedAt_roundTripsAsInstant() throws Exception {
        store.save("abc", processingTask(), Duration.ofMinutes(3));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis).setAndAddToSortedSets(anyString(), jsonCaptor.capture(), any(),
                anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
        TimelineDraftTask roundTripped = objectMapper.readValue(jsonCaptor.getValue(), TimelineDraftTask.class);
        assertThat(roundTripped.processingStartedAt()).isEqualTo(STARTED_AT);
        assertThat(roundTripped.timelineWindow().startTime()).isEqualTo(LocalDate.of(2026, 5, 8).atTime(18, 30));
    }

    @Test
    void replaceIfUnchanged_processingStage_usesAtomicCasWithBothIndexes() throws Exception {
        TimelineDraftTask expected = processingTask();
        TimelineDraftTask replacement =
                expected.withTokenAndStage(expected.tokenHash(), ProcessStage.RESULT_PENDING);
        when(redis.compareAndSetAndAddToSortedSets(
                anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);

        assertThat(store.replaceIfUnchanged(
                "abc", expected, replacement, Duration.ofMinutes(3))).isTrue();

        ArgumentCaptor<String> expectedJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> replacementJson = ArgumentCaptor.forClass(String.class);
        verify(redis).compareAndSetAndAddToSortedSets(
                org.mockito.ArgumentMatchers.eq("timeline:draft-task:abc"),
                expectedJson.capture(), replacementJson.capture(),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(3)),
                org.mockito.ArgumentMatchers.eq(TimelineTaskStore.PROCESSING_INDEX_KEY),
                org.mockito.ArgumentMatchers.eq(USER_INDEX_KEY),
                org.mockito.ArgumentMatchers.eq("abc"),
                org.mockito.ArgumentMatchers.eq(STARTED_AT.toEpochMilli()));
        assertThat(objectMapper.readValue(expectedJson.getValue(), TimelineDraftTask.class)).isEqualTo(expected);
        assertThat(objectMapper.readValue(replacementJson.getValue(), TimelineDraftTask.class))
                .isEqualTo(replacement);
    }

    @Test
    void save_terminalTasks_omitProcessingStartedAtAndWindow() throws Exception {
        // PROCESSING 전용 lifecycle: 종결 JSON에는 key 자체가 없다(NON_NULL — terminal shape 불변).
        store.save("s", TimelineDraftTask.success(SUBJECT, 42L, tokenHashes("h")), Duration.ofHours(24));
        store.save("f", TimelineDraftTask.failed(SUBJECT, 42L, -1009, tokenHashes("h")), Duration.ofHours(24));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis, times(2)).setAndRemoveFromSortedSets(
                anyString(), jsonCaptor.capture(), any(), anyString(), anyString(), anyString());
        assertThat(jsonCaptor.getAllValues()).allSatisfy(json -> {
            assertThat(json).doesNotContain("processingStartedAt");
            assertThat(json).doesNotContain("timelineWindow");
        });
    }

    @Test
    void save_failedTask_writesNumericError() {
        store.save("numeric", TimelineDraftTask.failed(SUBJECT, 42L, -1009, tokenHashes("h")), Duration.ofHours(24));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis).setAndRemoveFromSortedSets(
                anyString(), jsonCaptor.capture(), any(), anyString(), anyString(), anyString());
        assertThat(jsonCaptor.getValue()).contains("\"error\":-1009");
        assertThat(jsonCaptor.getValue()).doesNotContain("\"error\":\"");
    }

    @Test
    void find_stringErrorCode_isRejected() {
        for (String value : java.util.List.of("ERROR_1009", "-1009")) {
            when(redis.get("timeline:draft-task:string-error")).thenReturn(
                    "{\"status\":\"FAILED\",\"dailyRecordId\":42,\"error\":\"" + value + "\","
                            + "\"tokenHash\":\"h\",\"subjectId\":\"" + SubjectIdCodec.encode(SUBJECT) + "\"}");

            assertThatThrownBy(() -> store.find("string-error"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void save_allStates_preserveOwnerAndDailyRecordId() throws Exception {
        // 세 상태 전이 모두 owner·dailyRecordId를 보존한다 — 폴링 소유권 대조·결과 조회의 기준값.
        store.save("p", processingTask(), Duration.ofMinutes(3));
        store.save("s", TimelineDraftTask.success(SUBJECT, 42L, tokenHashes("h")), Duration.ofHours(24));
        store.save("f", TimelineDraftTask.failed(SUBJECT, 42L, -1009, tokenHashes("h")), Duration.ofHours(24));

        ArgumentCaptor<String> processingJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> terminalJson = ArgumentCaptor.forClass(String.class);
        verify(redis).setAndAddToSortedSets(anyString(), processingJson.capture(), any(),
                anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(redis, times(2)).setAndRemoveFromSortedSets(
                anyString(), terminalJson.capture(), any(), anyString(), anyString(), anyString());
        java.util.List<String> allJson = new java.util.ArrayList<>();
        allJson.add(processingJson.getValue());
        allJson.addAll(terminalJson.getAllValues());
        for (String json : allJson) {
            TimelineDraftTask roundTripped = objectMapper.readValue(json, TimelineDraftTask.class);
            assertThat(roundTripped.subjectId()).isEqualTo(SUBJECT);
            assertThat(roundTripped.dailyRecordId()).isEqualTo(42L);
        }
    }

    @Test
    void save_processingAtomicallyAddsStartedAtToBothIndexes() {
        // T13: task JSON·전역 index·소유자의 사용자 index가 같은 member/score·PROCESSING TTL로
        // gateway 원자 호출 한 번에 전달된다(사용자 index key TTL 갱신은 gateway script 책임).
        store.save("abc", processingTask(), Duration.ofMinutes(3));

        verify(redis).setAndAddToSortedSets(
                org.mockito.ArgumentMatchers.eq("timeline:draft-task:abc"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(Duration.ofMinutes(3)),
                org.mockito.ArgumentMatchers.eq(TimelineTaskStore.PROCESSING_INDEX_KEY),
                org.mockito.ArgumentMatchers.eq(USER_INDEX_KEY),
                org.mockito.ArgumentMatchers.eq("abc"),
                org.mockito.ArgumentMatchers.eq(STARTED_AT.toEpochMilli()));
    }

    @Test
    void save_terminalAtomicallyRemovesTaskFromBothIndexes() {
        // T14: SUCCESS/FAILED 종류와 무관하게 terminal 저장 한 번이 전역·사용자 index 제거를 함께 나른다.
        // 사용자 index key는 보존된 양수 owner로 조립한다(터미널에도 owner 필수 — D6/D14).
        store.save("success", TimelineDraftTask.success(SUBJECT, 42L, tokenHashes("h")), Duration.ofHours(24));
        store.save("failed", TimelineDraftTask.failed(SUBJECT, 42L, -1009, tokenHashes("h")),
                Duration.ofHours(24));

        verify(redis).setAndRemoveFromSortedSets(
                org.mockito.ArgumentMatchers.eq("timeline:draft-task:success"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(Duration.ofHours(24)),
                org.mockito.ArgumentMatchers.eq(TimelineTaskStore.PROCESSING_INDEX_KEY),
                org.mockito.ArgumentMatchers.eq(USER_INDEX_KEY),
                org.mockito.ArgumentMatchers.eq("success"));
        verify(redis).setAndRemoveFromSortedSets(
                org.mockito.ArgumentMatchers.eq("timeline:draft-task:failed"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(Duration.ofHours(24)),
                org.mockito.ArgumentMatchers.eq(TimelineTaskStore.PROCESSING_INDEX_KEY),
                org.mockito.ArgumentMatchers.eq(USER_INDEX_KEY),
                org.mockito.ArgumentMatchers.eq("failed"));
    }

    @Test
    void countStuckProcessing_delegatesExactTtlAndThresholdCutoffs() {
        // 만료 cutoff(now-3m)는 prune, stuck cutoff(now-90s)는 count 상한 — 정확한 epoch millis 전달을 고정한다.
        Instant now = Instant.parse("2026-07-24T12:00:00Z");
        when(redis.pruneAndCountSortedSet(TimelineTaskStore.PROCESSING_INDEX_KEY,
                now.minus(Duration.ofMinutes(3)).toEpochMilli(),
                now.minus(Duration.ofSeconds(90)).toEpochMilli())).thenReturn(2L);

        assertThat(store.countStuckProcessing(
                now, Duration.ofSeconds(90), Duration.ofMinutes(3))).isEqualTo(2L);
    }

    @Test
    void find_jsonWithoutRequiredTaskFields_isRejected() {
        when(redis.get("timeline:draft-task:invalid")).thenReturn(
                "{\"status\":\"PROCESSING\",\"recordDate\":\"2026-05-08\",\"recordAt\":\"2026-05-08T22:41:00\","
                        + "\"recordTimezone\":\"Asia/Seoul\",\"userMemory\":{\"usersCharacter\":null},"
                        + "\"timelineWindow\":null,\"error\":null,\"tokenHash\":\"h\"}");

        assertThatThrownBy(() -> store.find("invalid"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void find_returnsDeserializedTask() throws Exception {
        TimelineDraftTask task = processingTask();
        when(redis.get("timeline:draft-task:abc")).thenReturn(objectMapper.writeValueAsString(task));

        Optional<TimelineDraftTask> found = store.find("abc");

        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(found.get().dailyRecordId()).isEqualTo(42L);
        assertThat(found.get().timelineWindow().startTime()).isEqualTo(LocalDate.of(2026, 5, 8).atTime(18, 30));
        assertThat(found.get().timelineWindow().endTime()).isEqualTo(LocalDate.of(2026, 5, 8).atTime(22, 41));
        assertThat(found.get().tokenHash()).isEqualTo("token-hash");
        assertThat(found.get()).isEqualTo(task);
    }

    @Test
    void find_returnsEmptyWhenMissing() {
        when(redis.get("timeline:draft-task:missing")).thenReturn(null);

        assertThat(store.find("missing")).isEmpty();
    }

    private String processingJson() throws Exception {
        return objectMapper.writeValueAsString(processingTask());
    }

    @Test
    void findProcessingTaskIds_emptyIndex_returnsEmptyList() {
        // T2: index key 없음(빈 range)이면 후보 read 없이 빈 목록이다 — null이 아니다.
        when(redis.getSortedSetReverseRange(USER_INDEX_KEY)).thenReturn(List.of());

        assertThat(store.findProcessingTaskIds(SUBJECT)).isNotNull().isEmpty();
        verify(redis, org.mockito.Mockito.never()).multiGet(any());
    }

    @Test
    void findProcessingTaskIds_returnsOwnedProcessing_inCandidateOrder() throws Exception {
        // T1·T6: 후보 순서(score 내림차순 — gateway ZREVRANGE 계약)를 그대로 보존해 반환하고,
        // 전부 유효하면 index를 정리하지 않는다.
        when(redis.getSortedSetReverseRange(USER_INDEX_KEY)).thenReturn(List.of("newer", "older"));
        when(redis.multiGet(List.of("timeline:draft-task:newer", "timeline:draft-task:older")))
                .thenReturn(List.of(processingJson(), processingJson()));

        assertThat(store.findProcessingTaskIds(SUBJECT)).containsExactly("newer", "older");
        verify(redis, org.mockito.Mockito.never()).removeFromSortedSet(anyString(), any());
    }

    @Test
    void findProcessingTaskIds_missingTaskKey_isExcludedAndPruned() throws Exception {
        // T8: task key 만료·소멸 뒤 남은 member는 응답에서 제외하고 같은 조회에서 lazy ZREM한다.
        when(redis.getSortedSetReverseRange(USER_INDEX_KEY)).thenReturn(List.of("gone", "alive"));
        when(redis.multiGet(List.of("timeline:draft-task:gone", "timeline:draft-task:alive")))
                .thenReturn(java.util.Arrays.asList(null, processingJson()));

        assertThat(store.findProcessingTaskIds(SUBJECT)).containsExactly("alive");
        verify(redis).removeFromSortedSet(USER_INDEX_KEY, List.of("gone"));
    }

    @Test
    void findProcessingTaskIds_terminalTasks_areExcludedAndPruned() throws Exception {
        // T9·T16: 권위 read 시점에 이미 terminal(SUCCESS/FAILED)인 후보는 종류와 무관하게 제외·prune한다.
        // (권위 read '직후' terminal이 되는 race는 이번 응답에 포함될 수 있음 — 단건 폴링이 최신 권위, D13.)
        when(redis.getSortedSetReverseRange(USER_INDEX_KEY))
                .thenReturn(List.of("succeeded", "failed", "alive"));
        when(redis.multiGet(List.of("timeline:draft-task:succeeded", "timeline:draft-task:failed",
                "timeline:draft-task:alive")))
                .thenReturn(List.of(
                        objectMapper.writeValueAsString(TimelineDraftTask.success(SUBJECT, 42L, tokenHashes("h"))),
                        objectMapper.writeValueAsString(TimelineDraftTask.failed(SUBJECT, 42L, -1009, tokenHashes("h"))),
                        processingJson()));

        assertThat(store.findProcessingTaskIds(SUBJECT)).containsExactly("alive");
        verify(redis).removeFromSortedSet(USER_INDEX_KEY, List.of("succeeded", "failed"));
    }

    @Test
    void findProcessingTaskIds_wrongOwnerTask_isExcludedAndPrunedFromRequesterIndexOnly() throws Exception {
        // T4: 역직렬화 가능한 양수의 다른 owner task는 존재 여부를 노출하지 않고 제외하며,
        // 요청 사용자의 index member만 정리한다(task JSON·타 사용자 index는 건드리지 않음).
        when(redis.getSortedSetReverseRange(USER_INDEX_KEY)).thenReturn(List.of("foreign", "mine"));
        when(redis.multiGet(List.of("timeline:draft-task:foreign", "timeline:draft-task:mine")))
                .thenReturn(List.of(
                        objectMapper.writeValueAsString(
                                TimelineDraftTask.processing(OTHER_SUBJECT, 43L, null, tokenHashes("h"), STARTED_AT)),
                        processingJson()));

        assertThat(store.findProcessingTaskIds(SUBJECT)).containsExactly("mine");
        verify(redis).removeFromSortedSet(USER_INDEX_KEY, List.of("foreign"));
        verify(redis, org.mockito.Mockito.never())
                .removeFromSortedSet(org.mockito.ArgumentMatchers.eq(
                        TimelineTaskStore.subjectProcessingIndexKey(OTHER_SUBJECT)), any());
    }

    @Test
    void findProcessingTaskIds_pruneFailure_stillReturnsValidIds() throws Exception {
        // T10: stale ZREM은 best-effort cleanup이다 — 실패해도 이미 판정한 유효 목록 반환을 깨지 않는다.
        when(redis.getSortedSetReverseRange(USER_INDEX_KEY)).thenReturn(List.of("alive", "gone"));
        when(redis.multiGet(List.of("timeline:draft-task:alive", "timeline:draft-task:gone")))
                .thenReturn(java.util.Arrays.asList(processingJson(), null));
        when(redis.removeFromSortedSet(USER_INDEX_KEY, List.of("gone")))
                .thenThrow(new IllegalStateException("redis down"));

        assertThat(store.findProcessingTaskIds(SUBJECT)).containsExactly("alive");
    }

    @Test
    void findProcessingTaskIds_candidateReadFailure_propagates() throws Exception {
        // T11: 후보 task batch read 실패는 권위를 판정할 수 없다 — index만 보고 결과를 만들지 않고 전파한다(500).
        when(redis.getSortedSetReverseRange(USER_INDEX_KEY)).thenReturn(List.of("a"));
        when(redis.multiGet(any())).thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> store.findProcessingTaskIds(SUBJECT))
                .isInstanceOf(IllegalStateException.class);
        verify(redis, org.mockito.Mockito.never()).removeFromSortedSet(anyString(), any());
    }

    @Test
    void findProcessingTaskIds_malformedOwnerJson_throwsWithoutPrune() {
        // T12: owner 누락·null·0 등 역직렬화 불가 JSON은 안전 판정이 불가하므로 500이며,
        // 같은 조회의 어떤 member도 자동 삭제하지 않는다(수동 조사 가능성 보존).
        for (String malformed : java.util.List.of(
                "{\"status\":\"PROCESSING\",\"dailyRecordId\":42,\"tokenHash\":\"h\",\"stage\":\"INPUT_PENDING\","
                        + "\"processingStartedAt\":\"2026-05-08T13:41:07Z\"}",
                "{\"status\":\"PROCESSING\",\"dailyRecordId\":42,\"tokenHash\":\"h\",\"stage\":\"INPUT_PENDING\","
                        + "\"processingStartedAt\":\"2026-05-08T13:41:07Z\",\"subjectId\":null}",
                "{\"status\":\"PROCESSING\",\"dailyRecordId\":42,\"tokenHash\":\"h\",\"stage\":\"INPUT_PENDING\","
                        + "\"processingStartedAt\":\"2026-05-08T13:41:07Z\",\"subjectId\":\"invalid\"}",
                "not-json")) {
            when(redis.getSortedSetReverseRange(USER_INDEX_KEY)).thenReturn(List.of("bad", "gone"));
            when(redis.multiGet(List.of("timeline:draft-task:bad", "timeline:draft-task:gone")))
                    .thenReturn(java.util.Arrays.asList(malformed, null));

            assertThatThrownBy(() -> store.findProcessingTaskIds(SUBJECT))
                    .isInstanceOf(IllegalStateException.class);
        }
        verify(redis, org.mockito.Mockito.never()).removeFromSortedSet(anyString(), any());
    }
}
