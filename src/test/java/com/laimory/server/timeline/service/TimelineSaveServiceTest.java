package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.UserMemoryUpdatePending;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 저장 오케스트레이션 단위 검증.
 *
 * <p>고정하는 계약: 부수효과 전에 404·409를 거절하고, 전이 커밋 <b>뒤에만</b> 갱신 접수를 깨우며,
 * 요청 스레드는 AI를 호출하지 않고, 접수 트리거가 실패해도 저장 응답을 깨지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class TimelineSaveServiceTest {

    private static final String VERSION = "v1";
    private static final long USER_ID = 7L;
    private static final Long RECORD_ID = 42L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 8, 5);

    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private TimelineSaveTransactionService timelineSaveTransactionService;
    @Mock
    private UserMemoryUpdateWorker worker;
    @Mock
    private UserMemoryUpdateDispatcher dispatcher;

    private TimelineSaveService service;

    @BeforeEach
    void setUp() {
        service = new TimelineSaveService(dailyRecordService, timelineSaveTransactionService, worker);
    }

    @Test
    void 전이를_커밋한_뒤에_갱신_접수를_깨운다() {
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, RECORD_DATE))
                .thenReturn(Optional.of(draftRecord()));

        service.save(VERSION, USER_ID, RECORD_DATE);

        // 커밋 전에 깨우면 롤백된 저장이 갱신을 유발한다.
        InOrder inOrder = inOrder(timelineSaveTransactionService, worker);
        inOrder.verify(timelineSaveTransactionService).save(USER_ID, RECORD_ID);
        inOrder.verify(worker).dispatchNow(any());
    }

    @Test
    void 접수_트리거가_거절돼도_저장은_성공으로_끝난다() {
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, RECORD_DATE))
                .thenReturn(Optional.of(draftRecord()));
        doThrow(new RuntimeException("executor saturated")).when(worker).dispatchNow(any());

        assertThatCode(() -> service.save(VERSION, USER_ID, RECORD_DATE)).doesNotThrowAnyException();

        verify(timelineSaveTransactionService).save(USER_ID, RECORD_ID);
    }

    @Test
    void 접수_요청은_식별자만_담는다() {
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, RECORD_DATE))
                .thenReturn(Optional.of(draftRecord()));

        service.save(VERSION, USER_ID, RECORD_DATE);

        // 접수 body와 base 지문은 guard를 잡은 뒤 worker가 만든다 — 여기서 미리 조립하지 않는다.
        ArgumentCaptor<UserMemoryUpdatePending> pending = ArgumentCaptor.forClass(UserMemoryUpdatePending.class);
        verify(worker).dispatchNow(pending.capture());
        assertThat(pending.getValue()).isEqualTo(new UserMemoryUpdatePending(USER_ID, RECORD_ID));
    }

    @Test
    void 요청_경로에서는_AI를_호출하지_않는다() {
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, RECORD_DATE))
                .thenReturn(Optional.of(draftRecord()));

        service.save(VERSION, USER_ID, RECORD_DATE);

        verifyNoInteractions(dispatcher);
    }

    @Test
    void 해당_날짜의_기록이_없으면_404로_은닉하고_아무_부수효과도_없다() {
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, RECORD_DATE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(VERSION, USER_ID, RECORD_DATE))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });
        verifyNoInteractions(timelineSaveTransactionService, worker);
    }

    @Test
    void 이미_SAVED면_409로_거절하고_아무_부수효과도_없다() {
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, RECORD_DATE))
                .thenReturn(Optional.of(savedRecord()));

        assertThatThrownBy(() -> service.save(VERSION, USER_ID, RECORD_DATE))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1003);
                });
        verifyNoInteractions(timelineSaveTransactionService, worker);
    }

    @Test
    void 전이가_실패하면_갱신_접수를_깨우지_않는다() {
        when(dailyRecordService.findByUserIdAndRecordDate(USER_ID, RECORD_DATE))
                .thenReturn(Optional.of(draftRecord()));
        doThrow(new BusinessException(ExceptionType.DAILY_RECORD_ALREADY_SAVED))
                .when(timelineSaveTransactionService).save(USER_ID, RECORD_ID);

        assertThatThrownBy(() -> service.save(VERSION, USER_ID, RECORD_DATE))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(worker);
    }

    private DailyRecord draftRecord() {
        DailyRecord record = DailyRecord.createDraft(
                USER_ID, RECORD_DATE, LocalDateTime.of(2026, 8, 5, 21, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        return record;
    }

    private DailyRecord savedRecord() {
        DailyRecord record = draftRecord();
        ReflectionTestUtils.setField(record, "status", DailyRecordStatus.SAVED);
        return record;
    }
}
