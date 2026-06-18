package com.laimory.server.timeline.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * TimelineTaskStore 직렬화 왕복 단위테스트(인프라 없음).
 * LocalDate가 jsr310 모듈로 정상 왕복하는지(회귀 방지)를 핵심으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class TimelineTaskStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private TimelineTaskStore store;

    @BeforeEach
    void setUp() {
        store = new TimelineTaskStore(redisTemplate, objectMapper);
    }

    @Test
    void save_serializesWithKeyAndTtl() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        TimelineDraftTask task = TimelineDraftTask.success(LocalDate.of(2026, 5, 8), 10L);

        store.save("abc", task, Duration.ofHours(24));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(keyCaptor.capture(), jsonCaptor.capture(), ttlCaptor.capture());

        assertThat(keyCaptor.getValue()).isEqualTo("timeline:draft-task:abc");
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofHours(24));
        TimelineDraftTask roundTripped = objectMapper.readValue(jsonCaptor.getValue(), TimelineDraftTask.class);
        assertThat(roundTripped).isEqualTo(task);
    }

    @Test
    void find_returnsDeserializedTask() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        TimelineDraftTask task = TimelineDraftTask.processing(LocalDate.of(2026, 5, 8));
        when(valueOps.get("timeline:draft-task:abc")).thenReturn(objectMapper.writeValueAsString(task));

        Optional<TimelineDraftTask> found = store.find("abc");

        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(found.get().recordDate()).isEqualTo(LocalDate.of(2026, 5, 8));
        assertThat(found.get()).isEqualTo(task);
    }

    @Test
    void find_returnsEmptyWhenMissing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("timeline:draft-task:missing")).thenReturn(null);

        assertThat(store.find("missing")).isEmpty();
    }
}
