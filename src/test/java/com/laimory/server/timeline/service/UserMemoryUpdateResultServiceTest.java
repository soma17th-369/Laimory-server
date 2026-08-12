package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static com.laimory.server.testsupport.TestSubjects.id;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.privacy.PrivacyRedactor;
import com.laimory.server.common.privacy.RedactionType;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.UserMemoryDigest;
import com.laimory.server.timeline.dto.AiUserMemoryUpdateResultRequest;
import com.laimory.server.timeline.entity.UserMemoryUpdateTask;
import com.laimory.server.timeline.repository.UserMemoryUpdatePendingStore;
import com.laimory.server.timeline.repository.UserMemoryUpdateTaskStore;
import com.laimory.server.user.UserMemoryService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AI 결과 적용 단위 검증.
 *
 * <p>고정하는 계약: token 검증이 개인 데이터 접근보다 먼저이고, base 지문이 다르면 결과를 폐기하며
 * (다른 날짜의 기여를 지우지 않는다), 성공·실패 모두 task와 guard를 정리해 중복 결과가 404가 되게 하고,
 * {@code daily_records}는 어느 경로에서도 건드리지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class UserMemoryUpdateResultServiceTest {

    private static final String VERSION = "v1";
    private static final String TASK_ID = "0198f2a1-7c3d-7000-8b2e-1f4a9c05d6e7";
    private static final UUID SUBJECT_ID = id(7L);
    private static final long RECORD_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-08-05T12:00:30Z");
    private static final Instant STARTED_AT = Instant.parse("2026-08-05T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String token = TaskTokens.generate();

    @Mock
    private UserMemoryUpdateTaskStore taskStore;
    @Mock
    private UserMemoryUpdatePendingStore pendingStore;
    @Mock
    private UserMemoryService userMemoryService;
    @Mock
    private DailyRecordService dailyRecordService;

    // 치환 검증은 실물 redactor로 하고, 실패 주입 테스트만 mock으로 바꿔 끼운다.
    private final PrivacyRedactor privacyRedactor = new PrivacyRedactor();

    private UserMemoryUpdateResultService service;

    @BeforeEach
    void setUp() {
        service = new UserMemoryUpdateResultService(taskStore, pendingStore, userMemoryService,
                privacyRedactor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void SUCCESS이고_base_문서가_그대로면_문서를_교체하고_작업을_종결한다() throws Exception {
        JsonNode base = objectMapper.readTree("{\"schemaVersion\":\"1.0\",\"currentFocus\":\"이전\"}");
        JsonNode updated = objectMapper.readTree("{\"schemaVersion\":\"1.0\",\"currentFocus\":\"새로\"}");
        when(taskStore.find(TASK_ID)).thenReturn(Optional.of(task(UserMemoryDigest.of(Optional.of(base)))));
        when(userMemoryService.find(SUBJECT_ID)).thenReturn(Optional.of(base));

        service.applyResult(VERSION, TASK_ID, token, success(updated));

        verify(userMemoryService).replace(SUBJECT_ID, updated);
        verify(taskStore).delete(TASK_ID);
        // guard는 TTL이 반납한다 — 다음 접수가 다음 배치라 일찍 지울 이유가 없고,
        // 대조 없는 삭제는 남의 guard를 지울 위험만 남긴다.
        verify(taskStore, never()).releaseGuard(any());
        // 반영됐으니 큐에서 뺀다(즉시 접수 경로였으면 애초에 없어 no-op).
        verify(pendingStore).removeAll(SUBJECT_ID, List.of(RECORD_ID));
        verifyNoInteractions(dailyRecordService);
    }

    @Test
    void 문서가_없던_사용자도_지문이_맞으면_교체한다() throws Exception {
        JsonNode updated = objectMapper.readTree("{\"schemaVersion\":\"1.0\"}");
        when(taskStore.find(TASK_ID)).thenReturn(Optional.of(task(null)));
        when(userMemoryService.find(SUBJECT_ID)).thenReturn(Optional.empty());

        service.applyResult(VERSION, TASK_ID, token, success(updated));

        verify(userMemoryService).replace(SUBJECT_ID, updated);
    }

    @Test
    void 저장되는_문서는_textual_leaf가_치환된_사본이다() throws Exception {
        JsonNode base = objectMapper.readTree("{\"schemaVersion\":\"1.0\"}");
        JsonNode updated = objectMapper.readTree(
                "{\"schemaVersion\":\"1.0\",\"profile\":{\"contact\":\"010-1234-5678\",\"steps\":8500}}");
        when(taskStore.find(TASK_ID)).thenReturn(Optional.of(task(UserMemoryDigest.of(Optional.of(base)))));
        when(userMemoryService.find(SUBJECT_ID)).thenReturn(Optional.of(base));

        service.applyResult(VERSION, TASK_ID, token, success(updated));

        // 구조·비문자 leaf는 유지하고 textual leaf의 v1 PII만 token으로 치환해 저장한다.
        JsonNode expected = objectMapper.readTree(
                "{\"schemaVersion\":\"1.0\",\"profile\":{\"contact\":\""
                        + RedactionType.PHONE.token() + "\",\"steps\":8500}}");
        verify(userMemoryService).replace(SUBJECT_ID, expected);
        verify(taskStore).delete(TASK_ID);
        verify(pendingStore).removeAll(SUBJECT_ID, List.of(RECORD_ID));
    }

    @Test
    void redaction이_실패하면_기존_문서를_유지하고_pending을_복구한다() throws Exception {
        // 원문 fallback 저장 금지 — 계약 위반 경로처럼 task를 종결하고 큐에 되돌려 다음 배치가 재시도한다.
        JsonNode base = objectMapper.readTree("{\"schemaVersion\":\"1.0\"}");
        when(taskStore.find(TASK_ID)).thenReturn(Optional.of(task(UserMemoryDigest.of(Optional.of(base)))));
        when(userMemoryService.find(SUBJECT_ID)).thenReturn(Optional.of(base));
        PrivacyRedactor failingRedactor = mock(PrivacyRedactor.class);
        when(failingRedactor.redactTree(any(JsonNode.class)))
                .thenThrow(new RuntimeException("redactor down"));
        UserMemoryUpdateResultService failingService = new UserMemoryUpdateResultService(
                taskStore, pendingStore, userMemoryService, failingRedactor, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> failingService.applyResult(VERSION, TASK_ID, token,
                success(objectMapper.readTree("{\"schemaVersion\":\"1.0\",\"currentFocus\":\"새로\"}"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("redactor down");

        verify(userMemoryService, never()).replace(any(), any());
        verify(taskStore).delete(TASK_ID);
        verify(pendingStore).enqueueAll(SUBJECT_ID, List.of(RECORD_ID), NOW);
    }

    @Test
    void base_문서가_그_사이_바뀌었으면_409로_폐기하고_문서를_건드리지_않는다() throws Exception {
        JsonNode base = objectMapper.readTree("{\"schemaVersion\":\"1.0\",\"currentFocus\":\"이전\"}");
        JsonNode replacedByAnotherDay = objectMapper.readTree("{\"schemaVersion\":\"1.0\",\"currentFocus\":\"8/4\"}");
        when(taskStore.find(TASK_ID)).thenReturn(Optional.of(task(UserMemoryDigest.of(Optional.of(base)))));
        when(userMemoryService.find(SUBJECT_ID)).thenReturn(Optional.of(replacedByAnotherDay));

        assertThatThrownBy(() -> service.applyResult(VERSION, TASK_ID, token,
                success(objectMapper.readTree("{\"schemaVersion\":\"1.0\"}"))))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.SAVE_TASK_STATE_CONFLICT);
                    assertThat(exception.getErrorCode()).isEqualTo(-1017);
                });

        verify(userMemoryService, never()).replace(any(), any());
        verify(taskStore).delete(TASK_ID);
        verify(taskStore, never()).releaseGuard(any());
        // 반영 못 했으니 큐에 넣어 다음 배치가 다시 시도한다.
        verify(pendingStore).enqueueAll(SUBJECT_ID, List.of(RECORD_ID), NOW);
    }

    @Test
    void FAILED_통보는_DB를_바꾸지_않고_작업만_종결한다() {
        when(taskStore.find(TASK_ID)).thenReturn(Optional.of(task(null)));

        assertThatCode(() -> service.applyResult(VERSION, TASK_ID, token,
                new AiUserMemoryUpdateResultRequest("FAILED", null, 1210, "budget exceeded")))
                .doesNotThrowAnyException();

        verify(userMemoryService, never()).replace(any(), any());
        verify(taskStore).delete(TASK_ID);
        verify(taskStore, never()).releaseGuard(any());
        // 반영 못 했으니 큐에 넣어 다음 배치가 다시 시도한다.
        verify(pendingStore).enqueueAll(SUBJECT_ID, List.of(RECORD_ID), NOW);
    }

    @Test
    void token이_다르면_401이고_개인_데이터에_접근하지_않는다() {
        when(taskStore.find(TASK_ID)).thenReturn(Optional.of(task(null)));

        assertThatThrownBy(() -> service.applyResult(VERSION, TASK_ID, TaskTokens.generate(), success(null)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.TASK_TOKEN_MISMATCH);
                    assertThat(exception.getErrorCode()).isEqualTo(-1002);
                });

        verifyNoInteractions(userMemoryService, pendingStore);
        verify(taskStore, never()).delete(TASK_ID);
    }

    @Test
    void 작업이_없으면_404다_만료와_중복_도착이_같은_경로다() {
        when(taskStore.find(TASK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyResult(VERSION, TASK_ID, token, success(null)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.SAVE_TASK_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-1001);
                });

        verifyNoInteractions(userMemoryService, pendingStore);
    }

    @Test
    void SUCCESS인데_문서가_없으면_400이고_작업을_종결한다() {
        when(taskStore.find(TASK_ID)).thenReturn(Optional.of(task(null)));

        assertThatThrownBy(() -> service.applyResult(VERSION, TASK_ID, token, success(null)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.VALIDATION_FAILED);
                    assertThat(exception.getErrorCode()).isEqualTo(-400);
                });

        verify(userMemoryService, never()).replace(any(), any());
        verify(taskStore).delete(TASK_ID);
        verify(taskStore, never()).releaseGuard(any());
        // 반영 못 했으니 큐에 넣어 다음 배치가 다시 시도한다.
        verify(pendingStore).enqueueAll(SUBJECT_ID, List.of(RECORD_ID), NOW);
    }

    private UserMemoryUpdateTask task(String baseMemoryHash) {
        return new UserMemoryUpdateTask(SUBJECT_ID, List.of(RECORD_ID), TaskTokens.hash(token), STARTED_AT, baseMemoryHash);
    }

    private static AiUserMemoryUpdateResultRequest success(JsonNode memory) {
        return new AiUserMemoryUpdateResultRequest("SUCCESS", memory, null, null);
    }
}
