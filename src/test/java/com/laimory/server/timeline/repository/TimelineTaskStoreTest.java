package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
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
 * LocalDate가 jsr310 모듈로 정상 왕복하는지(회귀 방지)와 논리 키 전달을 검증한다.
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

    @BeforeEach
    void setUp() {
        store = new TimelineTaskStore(redis, objectMapper);
    }

    @Test
    void save_serializesWithKeyAndTtl() throws Exception {
        TimelineDraftTask task = TimelineDraftTask.success(LocalDate.of(2026, 5, 8), 42L, "token-hash");

        store.save("abc", task, Duration.ofHours(24));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redis).set(keyCaptor.capture(), jsonCaptor.capture(), ttlCaptor.capture());

        assertThat(keyCaptor.getValue()).isEqualTo("timeline:draft-task:abc");
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(24));
        TimelineDraftTask roundTripped = objectMapper.readValue(jsonCaptor.getValue(), TimelineDraftTask.class);
        assertThat(roundTripped).isEqualTo(task);
    }

    @Test
    void save_serializesAiContractShape_fieldNamesAndWindowFormat() throws Exception {
        // AI가 이 value JSON을 직접 읽는 계약 — 필드명·날짜 포맷이 고정돼야 한다.
        TimelineDraftTask task = TimelineDraftTask.processing(
                LocalDate.of(2026, 5, 8), LocalDate.of(2026, 5, 8).atTime(22, 41), "Asia/Seoul",
                new TimelineDraftTask.TimelineWindow(
                        LocalDate.of(2026, 5, 8).atTime(18, 30), LocalDate.of(2026, 5, 8).atTime(22, 41)),
                "token-hash", Instant.parse("2026-05-08T13:41:07Z"));

        store.save("abc", task, Duration.ofHours(1));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis).set(anyString(), jsonCaptor.capture(), any());
        String json = jsonCaptor.getValue();
        assertThat(json).contains("\"recordDate\":\"2026-05-08\"");
        assertThat(json).contains("\"recordTimezone\":\"Asia/Seoul\"");
        assertThat(json).contains("\"userMemory\":{\"usersCharacter\":null}");
        assertThat(json).contains(
                "\"timelineWindow\":{\"startTime\":\"20260508T183000\",\"endTime\":\"20260508T224100\"}");
        // PROCESSING 시작 시각은 UTC ISO-8601 문자열로 고정된다(숫자 timestamp 설정 무관 — @JsonFormat STRING).
        assertThat(json).contains("\"processingStartedAt\":\"2026-05-08T13:41:07Z\"");
        // NON_NULL 명시 필드: PROCESSING JSON(AI가 직접 읽는 계약)에 dailyRecordId가 null로 노출되지 않는다.
        assertThat(json).doesNotContain("dailyRecordId");
    }

    @Test
    void save_processingStartedAt_roundTripsAsInstant() throws Exception {
        Instant startedAt = Instant.parse("2026-05-08T13:41:07Z");
        TimelineDraftTask task = TimelineDraftTask.processing(
                LocalDate.of(2026, 5, 8), LocalDate.of(2026, 5, 8).atTime(22, 41), "Asia/Seoul",
                null, "token-hash", startedAt);

        store.save("abc", task, Duration.ofHours(1));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis).set(anyString(), jsonCaptor.capture(), any());
        TimelineDraftTask roundTripped = objectMapper.readValue(jsonCaptor.getValue(), TimelineDraftTask.class);
        assertThat(roundTripped.processingStartedAt()).isEqualTo(startedAt);
    }

    @Test
    void save_terminalTasks_omitProcessingStartedAt() throws Exception {
        // PROCESSING 전용 lifecycle: 종결 JSON에는 key 자체가 없다(NON_NULL — terminal shape 불변).
        store.save("s", TimelineDraftTask.success(LocalDate.of(2026, 5, 8), 42L, "h"), Duration.ofHours(24));
        store.save("f", TimelineDraftTask.failed(LocalDate.of(2026, 5, 8), "ERROR_1009", "h"), Duration.ofHours(24));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis, times(2)).set(anyString(), jsonCaptor.capture(), any());
        assertThat(jsonCaptor.getAllValues()).allSatisfy(json ->
                assertThat(json).doesNotContain("processingStartedAt"));
    }

    @Test
    void find_legacyProcessingJsonWithoutStartedAt_deserializesToNull() {
        // 배포 전 저장된 PROCESSING JSON(필드 자체가 없음) → 예외 없이 null — 폴링은 elapsedSeconds를 생략한다.
        when(redis.get("timeline:draft-task:legacy-p")).thenReturn(
                "{\"status\":\"PROCESSING\",\"recordDate\":\"2026-05-08\",\"recordAt\":\"2026-05-08T22:41:00\","
                        + "\"recordTimezone\":\"Asia/Seoul\",\"userMemory\":{\"usersCharacter\":null},"
                        + "\"timelineWindow\":null,\"error\":null,\"callbackTokenHash\":\"h\"}");

        Optional<TimelineDraftTask> found = store.find("legacy-p");

        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(found.get().processingStartedAt()).isNull();
    }

    @Test
    void save_successTask_includesDailyRecordId() throws Exception {
        // SUCCESS에만 결과 식별자가 실린다 — 폴링이 이 ID로만 결과를 조회한다.
        store.save("abc", TimelineDraftTask.success(LocalDate.of(2026, 5, 8), 42L, "h"), Duration.ofHours(24));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis).set(anyString(), jsonCaptor.capture(), any());
        assertThat(jsonCaptor.getValue()).contains("\"dailyRecordId\":42");
    }

    @Test
    void save_failedTask_omitsDailyRecordId() throws Exception {
        store.save("f", TimelineDraftTask.failed(LocalDate.of(2026, 5, 8), "ERROR_1009", "h"), Duration.ofHours(24));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis).set(anyString(), jsonCaptor.capture(), any());
        assertThat(jsonCaptor.getValue()).doesNotContain("dailyRecordId");
    }

    @Test
    void find_legacySuccessJsonWithoutDailyRecordId_deserializesToNull() {
        // 배포 전 저장된 SUCCESS JSON(필드 자체가 없음) → 에러 없이 null로 역직렬화돼 legacy(0404) 판정이 가능하다.
        when(redis.get("timeline:draft-task:legacy")).thenReturn(
                "{\"status\":\"SUCCESS\",\"recordDate\":\"2026-05-08\",\"recordAt\":null,\"recordTimezone\":null,"
                        + "\"userMemory\":null,\"timelineWindow\":null,\"error\":null,\"callbackTokenHash\":\"h\"}");

        Optional<TimelineDraftTask> found = store.find("legacy");

        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(found.get().dailyRecordId()).isNull();
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
        TimelineDraftTask task = TimelineDraftTask.processing(
                LocalDate.of(2026, 5, 8), LocalDate.of(2026, 5, 8).atTime(12, 0), "Asia/Seoul",
                new TimelineDraftTask.TimelineWindow(
                        LocalDate.of(2026, 5, 8).atTime(9, 0), LocalDate.of(2026, 5, 8).atTime(11, 0)),
                "token-hash", Instant.parse("2026-05-08T02:59:30Z"));
        when(redis.get("timeline:draft-task:abc")).thenReturn(objectMapper.writeValueAsString(task));

        Optional<TimelineDraftTask> found = store.find("abc");

        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(found.get().recordDate()).isEqualTo(LocalDate.of(2026, 5, 8));
        assertThat(found.get().recordAt()).isEqualTo(LocalDate.of(2026, 5, 8).atTime(12, 0));
        assertThat(found.get().recordTimezone()).isEqualTo("Asia/Seoul");
        // timelineWindow는 AI 계약 포맷(yyyyMMdd'T'HHmmss)으로 직렬화되고 그대로 왕복돼야 한다.
        assertThat(found.get().timelineWindow().startTime()).isEqualTo(LocalDate.of(2026, 5, 8).atTime(9, 0));
        assertThat(found.get().timelineWindow().endTime()).isEqualTo(LocalDate.of(2026, 5, 8).atTime(11, 0));
        // userMemory는 shape만(usersCharacter=null).
        assertThat(found.get().userMemory().usersCharacter()).isNull();
        assertThat(found.get().callbackTokenHash()).isEqualTo("token-hash");
        assertThat(found.get()).isEqualTo(task);
    }

    @Test
    void find_returnsEmptyWhenMissing() {
        when(redis.get("timeline:draft-task:missing")).thenReturn(null);

        assertThat(store.find("missing")).isEmpty();
    }
}
