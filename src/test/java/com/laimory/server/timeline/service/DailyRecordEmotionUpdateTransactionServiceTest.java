package com.laimory.server.timeline.service;

import static com.laimory.server.testsupport.TestSubjects.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 감정 수정 DB transaction 단위 검증.
 *
 * <p>고정하는 계약: SAVED 조건부 UPDATE가 트랜잭션의 첫 DB 작업이고, 영향 행 수 1이면 추가 조회 없이
 * 성공한다. 0행일 때만 재조회로 없음·비소유(404)·DRAFT({@code -1020})·동일 감정 SAVED(멱등 성공)를
 * 분류하고, 설명되지 않는 SAVED 불일치는 500 invariant failure로 처리한다.
 */
@ExtendWith(MockitoExtension.class)
class DailyRecordEmotionUpdateTransactionServiceTest {

    private static final UUID SUBJECT_ID = id(7L);
    private static final Long RECORD_ID = 42L;
    private static final EmotionType EMOTION = EmotionType.NEUTRAL;

    @Mock
    private DailyRecordService dailyRecordService;

    @InjectMocks
    private DailyRecordEmotionUpdateTransactionService transactionService;

    @Test
    void 조건부_UPDATE가_1행이면_추가_조회_없이_성공한다() {
        when(dailyRecordService.updateSavedEmotion(RECORD_ID, SUBJECT_ID, EMOTION)).thenReturn(1);

        transactionService.updateEmotion(SUBJECT_ID, RECORD_ID, EMOTION);

        verify(dailyRecordService).updateSavedEmotion(RECORD_ID, SUBJECT_ID, EMOTION);
        verify(dailyRecordService, never()).findByDailyRecordIdAndSubjectId(RECORD_ID, SUBJECT_ID);
    }

    @Test
    void 첫_repository_호출이_조건부_UPDATE이고_실패_때만_분류_SELECT를_수행한다() {
        when(dailyRecordService.updateSavedEmotion(RECORD_ID, SUBJECT_ID, EMOTION)).thenReturn(0);
        when(dailyRecordService.findByDailyRecordIdAndSubjectId(RECORD_ID, SUBJECT_ID))
                .thenReturn(Optional.of(savedRecordWith(EMOTION)));

        transactionService.updateEmotion(SUBJECT_ID, RECORD_ID, EMOTION);

        InOrder inOrder = inOrder(dailyRecordService);
        inOrder.verify(dailyRecordService).updateSavedEmotion(RECORD_ID, SUBJECT_ID, EMOTION);
        inOrder.verify(dailyRecordService).findByDailyRecordIdAndSubjectId(RECORD_ID, SUBJECT_ID);
    }

    @Test
    void 수정_직전에_record가_사라지면_404로_은닉한다() {
        when(dailyRecordService.updateSavedEmotion(RECORD_ID, SUBJECT_ID, EMOTION)).thenReturn(0);
        when(dailyRecordService.findByDailyRecordIdAndSubjectId(RECORD_ID, SUBJECT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.updateEmotion(SUBJECT_ID, RECORD_ID, EMOTION))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });
    }

    @Test
    void 재조회가_DRAFT면_1020으로_거절한다() {
        when(dailyRecordService.updateSavedEmotion(RECORD_ID, SUBJECT_ID, EMOTION)).thenReturn(0);
        when(dailyRecordService.findByDailyRecordIdAndSubjectId(RECORD_ID, SUBJECT_ID))
                .thenReturn(Optional.of(draftRecord()));

        assertThatThrownBy(() -> transactionService.updateEmotion(SUBJECT_ID, RECORD_ID, EMOTION))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_SAVED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1020);
                });
    }

    @Test
    void 재조회가_동일_감정_SAVED면_멱등_성공이다() {
        when(dailyRecordService.updateSavedEmotion(RECORD_ID, SUBJECT_ID, EMOTION)).thenReturn(0);
        when(dailyRecordService.findByDailyRecordIdAndSubjectId(RECORD_ID, SUBJECT_ID))
                .thenReturn(Optional.of(savedRecordWith(EMOTION)));

        assertThatCode(() -> transactionService.updateEmotion(SUBJECT_ID, RECORD_ID, EMOTION))
                .doesNotThrowAnyException();
    }

    @Test
    void 설명되지_않는_다른_감정_SAVED_불일치는_500_invariant_failure다() {
        when(dailyRecordService.updateSavedEmotion(RECORD_ID, SUBJECT_ID, EMOTION)).thenReturn(0);
        when(dailyRecordService.findByDailyRecordIdAndSubjectId(RECORD_ID, SUBJECT_ID))
                .thenReturn(Optional.of(savedRecordWith(EmotionType.VERY_UNHAPPY)));

        assertThatThrownBy(() -> transactionService.updateEmotion(SUBJECT_ID, RECORD_ID, EMOTION))
                .isInstanceOf(IllegalStateException.class);
    }

    private DailyRecord draftRecord() {
        DailyRecord record = DailyRecord.createDraft(
                SUBJECT_ID, LocalDate.of(2026, 8, 5), LocalDateTime.of(2026, 8, 5, 21, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        return record;
    }

    private DailyRecord savedRecordWith(EmotionType emotionType) {
        DailyRecord record = draftRecord();
        ReflectionTestUtils.setField(record, "status", DailyRecordStatus.SAVED);
        ReflectionTestUtils.setField(record, "emotionType", emotionType);
        return record;
    }
}
