package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.laimory.server.common.redis.RedisGateway;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

    @BeforeEach
    void setUp() {
        store = new TimelineTaskStore(redis, objectMapper);
    }

    private TimelineDraftTask processingTask() {
        return TimelineDraftTask.processing(7L, 42L,
                new TimelineDraftTask.TimelineWindow(
                        LocalDate.of(2026, 5, 8).atTime(18, 30), LocalDate.of(2026, 5, 8).atTime(22, 41)),
                "token-hash", STARTED_AT);
    }

    @Test
    void save_serializesWithKeyAndTtl() throws Exception {
        TimelineDraftTask task = TimelineDraftTask.success(7L, 42L, "token-hash");

        store.save("abc", task, Duration.ofHours(24));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redis).setAndRemoveFromSortedSet(
                keyCaptor.capture(), jsonCaptor.capture(), ttlCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(TimelineTaskStore.PROCESSING_INDEX_KEY),
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
        store.save("abc", processingTask(), Duration.ofHours(1));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis).setAndAddToSortedSet(anyString(), jsonCaptor.capture(), any(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
        String json = jsonCaptor.getValue();
        assertThat(json).contains("\"dailyRecordId\":42");
        assertThat(json).contains("\"userId\":7");
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
        store.save("abc", processingTask(), Duration.ofHours(1));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis).setAndAddToSortedSet(anyString(), jsonCaptor.capture(), any(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
        TimelineDraftTask roundTripped = objectMapper.readValue(jsonCaptor.getValue(), TimelineDraftTask.class);
        assertThat(roundTripped.processingStartedAt()).isEqualTo(STARTED_AT);
        assertThat(roundTripped.timelineWindow().startTime()).isEqualTo(LocalDate.of(2026, 5, 8).atTime(18, 30));
    }

    @Test
    void save_terminalTasks_omitProcessingStartedAtAndWindow() throws Exception {
        // PROCESSING 전용 lifecycle: 종결 JSON에는 key 자체가 없다(NON_NULL — terminal shape 불변).
        store.save("s", TimelineDraftTask.success(7L, 42L, "h"), Duration.ofHours(24));
        store.save("f", TimelineDraftTask.failed(7L, 42L, -1009, "h"), Duration.ofHours(24));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis, times(2)).setAndRemoveFromSortedSet(
                anyString(), jsonCaptor.capture(), any(), anyString(), anyString());
        assertThat(jsonCaptor.getAllValues()).allSatisfy(json -> {
            assertThat(json).doesNotContain("processingStartedAt");
            assertThat(json).doesNotContain("timelineWindow");
        });
    }

    @Test
    void save_failedTask_writesNumericError() {
        store.save("numeric", TimelineDraftTask.failed(7L, 42L, -1009, "h"), Duration.ofHours(24));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis).setAndRemoveFromSortedSet(
                anyString(), jsonCaptor.capture(), any(), anyString(), anyString());
        assertThat(jsonCaptor.getValue()).contains("\"error\":-1009");
        assertThat(jsonCaptor.getValue()).doesNotContain("\"error\":\"");
    }

    @Test
    void find_stringErrorCode_isRejected() {
        for (String value : java.util.List.of("ERROR_1009", "-1009")) {
            when(redis.get("timeline:draft-task:string-error")).thenReturn(
                    "{\"status\":\"FAILED\",\"dailyRecordId\":42,\"error\":\"" + value + "\","
                            + "\"callbackTokenHash\":\"h\",\"userId\":7}");

            assertThatThrownBy(() -> store.find("string-error"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void save_allStates_preserveOwnerAndDailyRecordId() throws Exception {
        // 세 상태 전이 모두 owner·dailyRecordId를 보존한다 — 폴링 소유권 대조·결과 조회·guard 해제의 기준값.
        store.save("p", processingTask(), Duration.ofHours(1));
        store.save("s", TimelineDraftTask.success(7L, 42L, "h"), Duration.ofHours(24));
        store.save("f", TimelineDraftTask.failed(7L, 42L, -1009, "h"), Duration.ofHours(24));

        ArgumentCaptor<String> processingJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> terminalJson = ArgumentCaptor.forClass(String.class);
        verify(redis).setAndAddToSortedSet(anyString(), processingJson.capture(), any(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(redis, times(2)).setAndRemoveFromSortedSet(
                anyString(), terminalJson.capture(), any(), anyString(), anyString());
        java.util.List<String> allJson = new java.util.ArrayList<>();
        allJson.add(processingJson.getValue());
        allJson.addAll(terminalJson.getAllValues());
        for (String json : allJson) {
            TimelineDraftTask roundTripped = objectMapper.readValue(json, TimelineDraftTask.class);
            assertThat(roundTripped.userId()).isEqualTo(7L);
            assertThat(roundTripped.dailyRecordId()).isEqualTo(42L);
        }
    }

    @Test
    void save_processingAtomicallyAddsStartedAtToIndex() {
        store.save("abc", processingTask(), Duration.ofHours(1));

        verify(redis).setAndAddToSortedSet(
                org.mockito.ArgumentMatchers.eq("timeline:draft-task:abc"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(Duration.ofHours(1)),
                org.mockito.ArgumentMatchers.eq(TimelineTaskStore.PROCESSING_INDEX_KEY),
                org.mockito.ArgumentMatchers.eq("abc"),
                org.mockito.ArgumentMatchers.eq(STARTED_AT.toEpochMilli()));
    }

    @Test
    void save_terminalAtomicallyRemovesTaskFromProcessingIndex() {
        store.save("success", TimelineDraftTask.success(7L, 42L, "h"), Duration.ofHours(24));
        store.save("failed", TimelineDraftTask.failed(7L, 42L, -1009, "h"),
                Duration.ofHours(24));

        verify(redis).setAndRemoveFromSortedSet(
                org.mockito.ArgumentMatchers.eq("timeline:draft-task:success"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(Duration.ofHours(24)),
                org.mockito.ArgumentMatchers.eq(TimelineTaskStore.PROCESSING_INDEX_KEY),
                org.mockito.ArgumentMatchers.eq("success"));
        verify(redis).setAndRemoveFromSortedSet(
                org.mockito.ArgumentMatchers.eq("timeline:draft-task:failed"),
                anyString(),
                org.mockito.ArgumentMatchers.eq(Duration.ofHours(24)),
                org.mockito.ArgumentMatchers.eq(TimelineTaskStore.PROCESSING_INDEX_KEY),
                org.mockito.ArgumentMatchers.eq("failed"));
    }

    @Test
    void countStuckProcessing_delegatesExactTtlAndThresholdCutoffs() {
        Instant now = Instant.parse("2026-07-24T12:00:00Z");
        when(redis.pruneAndCountSortedSet(TimelineTaskStore.PROCESSING_INDEX_KEY,
                now.minus(Duration.ofHours(1)).toEpochMilli(),
                now.minus(Duration.ofMinutes(10)).toEpochMilli())).thenReturn(2L);

        assertThat(store.countStuckProcessing(
                now, Duration.ofMinutes(10), Duration.ofHours(1))).isEqualTo(2L);
    }

    @Test
    void consumeCallbackToken_delegatesExactMarkerKeyValueAndTtl() {
        Duration ttl = Duration.ofHours(25);
        when(redis.setIfAbsent("timeline:callback-token-uses:abc", "used", ttl)).thenReturn(true);

        assertThat(store.consumeCallbackToken("abc", ttl)).isTrue();

        verify(redis).setIfAbsent("timeline:callback-token-uses:abc", "used", ttl);
    }

    @Test
    void find_jsonWithoutRequiredTaskFields_isRejected() {
        when(redis.get("timeline:draft-task:invalid")).thenReturn(
                "{\"status\":\"PROCESSING\",\"recordDate\":\"2026-05-08\",\"recordAt\":\"2026-05-08T22:41:00\","
                        + "\"recordTimezone\":\"Asia/Seoul\",\"userMemory\":{\"usersCharacter\":null},"
                        + "\"timelineWindow\":null,\"error\":null,\"callbackTokenHash\":\"h\"}");

        assertThatThrownBy(() -> store.find("invalid"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dateGuard_claimRefreshRelease_delegateWithLogicalKey() {
        // 논리 키 {userId}:{recordDate}(ISO) 조립과 RedisGateway 원자 연산 위임을 고정한다.
        LocalDate date = LocalDate.of(2026, 5, 8);
        when(redis.setIfAbsent("timeline:date-guard:7:2026-05-08", "task:abc", Duration.ofHours(1)))
                .thenReturn(true);
        when(redis.expireIfValueMatches("timeline:date-guard:7:2026-05-08", "task:abc", Duration.ofHours(1)))
                .thenReturn(true);
        when(redis.deleteIfValueMatches("timeline:date-guard:7:2026-05-08", "task:abc")).thenReturn(true);

        assertThat(store.claimDateGuard(7L, date, "task:abc", Duration.ofHours(1))).isTrue();
        assertThat(store.refreshDateGuard(7L, date, "task:abc", Duration.ofHours(1))).isTrue();
        assertThat(store.releaseDateGuard(7L, date, "task:abc")).isTrue();
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
        assertThat(found.get().callbackTokenHash()).isEqualTo("token-hash");
        assertThat(found.get()).isEqualTo(task);
    }

    @Test
    void find_returnsEmptyWhenMissing() {
        when(redis.get("timeline:draft-task:missing")).thenReturn(null);

        assertThat(store.find("missing")).isEmpty();
    }
}
