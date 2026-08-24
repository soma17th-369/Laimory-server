package com.laimory.server.timeline.service;

import static com.laimory.server.testsupport.TestSubjects.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.DailyRecordStatus;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.entity.DailyRecord;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 감정 수정 오케스트레이션 단위 검증.
 *
 * <p>고정하는 계약: 부수효과 전에 404·DRAFT 409를 거절하고, SAVED 사전 검증을 통과한 record의 ID
 * snapshot만 트랜잭션 서비스로 넘기며, User Memory worker에는 의존하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class DailyRecordEmotionUpdateServiceTest {

    private static final String VERSION = "v1";
    private static final UUID SUBJECT_ID = id(7L);
    private static final Long RECORD_ID = 42L;
    private static final LocalDate RECORD_DATE = LocalDate.of(2026, 8, 5);
    private static final EmotionType EMOTION = EmotionType.HAPPY;

    @Mock
    private DailyRecordService dailyRecordService;
    @Mock
    private DailyRecordEmotionUpdateTransactionService transactionService;

    @InjectMocks
    private DailyRecordEmotionUpdateService service;

    @Test
    void SAVED_record는_ID_snapshot으로_트랜잭션_서비스에_위임한다() {
        when(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, RECORD_DATE))
                .thenReturn(Optional.of(savedRecord()));

        service.updateEmotion(VERSION, SUBJECT_ID, RECORD_DATE, EMOTION);

        verify(transactionService).updateEmotion(SUBJECT_ID, RECORD_ID, EMOTION);
    }

    @Test
    void DRAFT면_트랜잭션_서비스_호출_없이_1020으로_거절한다() {
        when(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, RECORD_DATE))
                .thenReturn(Optional.of(draftRecord()));

        assertThatThrownBy(() -> service.updateEmotion(VERSION, SUBJECT_ID, RECORD_DATE, EMOTION))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_SAVED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1020);
                });
        verifyNoInteractions(transactionService);
    }

    @Test
    void 해당_날짜의_기록이_없으면_트랜잭션_서비스_호출_없이_404로_은닉한다() {
        when(dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT_ID, RECORD_DATE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateEmotion(VERSION, SUBJECT_ID, RECORD_DATE, EMOTION))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });
        verifyNoInteractions(transactionService);
    }

    private DailyRecord draftRecord() {
        DailyRecord record = DailyRecord.createDraft(
                SUBJECT_ID, RECORD_DATE, LocalDateTime.of(2026, 8, 5, 21, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        return record;
    }

    private DailyRecord savedRecord() {
        DailyRecord record = draftRecord();
        ReflectionTestUtils.setField(record, "status", DailyRecordStatus.SAVED);
        return record;
    }
}
