package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.laimory.server.common.redis.PrefixedRedis;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.Duration;
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
 * 환경 prefix 부착은 PrefixedRedis의 책임이라 여기선 논리 키만 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class TimelineTaskStoreTest {

    @Mock
    private PrefixedRedis redis;

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
        TimelineDraftTask task = TimelineDraftTask.success(LocalDate.of(2026, 5, 8), "token-hash");

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
                "token-hash");

        store.save("abc", task, Duration.ofHours(1));

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis).set(anyString(), jsonCaptor.capture(), any());
        String json = jsonCaptor.getValue();
        assertThat(json).contains("\"recordDate\":\"2026-05-08\"");
        assertThat(json).contains("\"recordTimezone\":\"Asia/Seoul\"");
        assertThat(json).contains("\"userMemory\":{\"usersCharacter\":null}");
        assertThat(json).contains(
                "\"timelineWindow\":{\"startTime\":\"20260508T183000\",\"endTime\":\"20260508T224100\"}");
    }

    @Test
    void find_returnsDeserializedTask() throws Exception {
        TimelineDraftTask task = TimelineDraftTask.processing(
                LocalDate.of(2026, 5, 8), LocalDate.of(2026, 5, 8).atTime(12, 0), "Asia/Seoul",
                new TimelineDraftTask.TimelineWindow(
                        LocalDate.of(2026, 5, 8).atTime(9, 0), LocalDate.of(2026, 5, 8).atTime(11, 0)),
                "token-hash");
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
