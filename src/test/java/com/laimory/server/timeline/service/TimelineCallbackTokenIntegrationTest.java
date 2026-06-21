package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.dto.CardSuggestionDto;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.dto.DraftTaskStatusResponse;
import com.laimory.server.timeline.dto.SourceItemDto;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * Callback-Token end-to-end 검증(실 MySQL+Redis). 수동 curl을 대체한다.
 * 토큰은 불투명하므로 디스패처를 spy해 발급된 raw 토큰을 캡처한 뒤 콜백을 호출한다.
 *
 * 실행: docker compose up -d 후 ./gradlew integrationTest
 */
@SpringBootTest
@ActiveProfiles("docker")
@Tag("integration")
class TimelineCallbackTokenIntegrationTest {

    @Autowired
    private TimelineDraftTaskService draftTaskService;
    @Autowired
    private TimelineCallbackService callbackService;
    @Autowired
    private TimelineTaskService taskService;
    @Autowired
    private TimelineDraftTaskPollingService pollingService;
    @Autowired
    private DailyRecordService dailyRecordService;
    @Autowired
    private DailyRecordRepository dailyRecordRepository;
    @Autowired
    private StringRedisTemplate redis;

    @MockitoSpyBean
    private CardSuggestionDispatcher dispatcher;

    private static final String VERSION = "v1";
    // 다른 데이터와 충돌하지 않을 고정 날짜.
    private static final LocalDate DATE = LocalDate.of(2000, 1, 1);

    private final List<String> createdTaskIds = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanUp() {
        dailyRecordService.findByUserIdAndRecordDate(0L, DATE)
                .ifPresent(record -> dailyRecordRepository.deleteById(record.getDailyRecordId())); // DB FK cascade로 이벤트/아이템도 삭제
        createdTaskIds.forEach(id -> redis.delete("timeline:draft-task:" + id));
        createdTaskIds.clear();
    }

    @Test
    void validToken_persistsAndStoresOnlyHash() {
        String taskId = draftTaskService.createDraftTask(VERSION, DATE, sources());
        createdTaskIds.add(taskId);

        // 디스패처 spy로 발급된 raw 토큰 캡처.
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(dispatcher).dispatch(eq(taskId), tokenCaptor.capture(), any(), anyString());
        String token = tokenCaptor.getValue();

        // Redis에는 원문 토큰이 없고 해시만 보관된다.
        String rawJson = redis.opsForValue().get("timeline:draft-task:" + taskId);
        assertThat(rawJson).doesNotContain(token);
        TimelineDraftTask stored = taskService.find(taskId).orElseThrow();
        assertThat(stored.callbackTokenHash()).isNotNull().isNotEqualTo(token);
        assertThat(CallbackTokens.matches(token, stored.callbackTokenHash())).isTrue();

        // 유효 토큰으로 콜백 → SUCCESS + MySQL 영속.
        callbackService.handleCallback(VERSION, taskId, token, successCallback());

        DraftTaskStatusResponse status = pollingService.poll(VERSION, taskId);
        assertThat(status.status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(status.result().events()).isNotEmpty();
        assertThat(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).isPresent();
    }

    @Test
    void wrongToken_rejected401_andNothingPersisted() {
        String taskId = draftTaskService.createDraftTask(VERSION, DATE, sources());
        createdTaskIds.add(taskId);

        assertThatThrownBy(() -> callbackService.handleCallback(VERSION, taskId, "wrong-token", successCallback()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));

        // task는 여전히 PROCESSING, MySQL엔 아무것도 안 써짐.
        assertThat(taskService.find(taskId).orElseThrow().status()).isEqualTo(TaskStatus.PROCESSING);
        assertThat(dailyRecordService.findByUserIdAndRecordDate(0L, DATE)).isEmpty();
    }

    private List<SourceItemDto> sources() {
        return List.of(new SourceItemDto(0, ItemType.PHOTO, DATE.atTime(9, 0), null, "s",
                new PhotoPayload("content://p", 37.5, 127.0)));
    }

    private DraftTaskCallbackRequest successCallback() {
        List<CardSuggestionDto> cards = List.of(
                new CardSuggestionDto("아침", null, DATE.atTime(9, 0), null, List.of(0)));
        return new DraftTaskCallbackRequest(TaskStatus.SUCCESS, null, sources(), cards);
    }
}
