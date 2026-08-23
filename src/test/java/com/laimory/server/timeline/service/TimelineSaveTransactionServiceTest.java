package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.laimory.server.testsupport.TestSubjects.id;

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
 * 저장 DB transaction 단위 검증.
 *
 * <p>고정하는 계약은 둘이다: 조건부 UPDATE의 영향 행 수가 전이 성공 판정의 유일한 기준이고, 0행일 때의
 * 원인을 SAVED(409)와 없음·비소유(404)로 분류한다. User Memory는 이 경계의 관심사가 아니다.
 */
@ExtendWith(MockitoExtension.class)
class TimelineSaveTransactionServiceTest {

    private static final UUID SUBJECT_ID = id(7L);
    private static final Long RECORD_ID = 42L;
    private static final EmotionType EMOTION = EmotionType.NEUTRAL;

    @Mock
    private DailyRecordService dailyRecordService;

    @InjectMocks
    private TimelineSaveTransactionService timelineSaveTransactionService;

    @Test
    void 조건부_UPDATE가_1행이면_전이가_성공한다() {
        when(dailyRecordService.markSaved(RECORD_ID, SUBJECT_ID, EMOTION)).thenReturn(1);

        timelineSaveTransactionService.save(SUBJECT_ID, RECORD_ID, EMOTION);

        verify(dailyRecordService).markSaved(RECORD_ID, SUBJECT_ID, EMOTION);
    }

    @Test
    void 전이_대상이_이미_SAVED면_409로_거절한다() {
        when(dailyRecordService.markSaved(RECORD_ID, SUBJECT_ID, EMOTION)).thenReturn(0);
        when(dailyRecordService.findByDailyRecordIdAndSubjectId(RECORD_ID, SUBJECT_ID))
                .thenReturn(Optional.of(savedRecord()));

        assertThatThrownBy(() -> timelineSaveTransactionService.save(SUBJECT_ID, RECORD_ID, EMOTION))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_ALREADY_SAVED);
                    assertThat(exception.getErrorCode()).isEqualTo(-1003);
                });
    }

    @Test
    void 전이_직전에_record가_사라지면_404로_은닉한다() {
        when(dailyRecordService.markSaved(RECORD_ID, SUBJECT_ID, EMOTION)).thenReturn(0);
        when(dailyRecordService.findByDailyRecordIdAndSubjectId(RECORD_ID, SUBJECT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> timelineSaveTransactionService.save(SUBJECT_ID, RECORD_ID, EMOTION))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getExceptionType()).isEqualTo(ExceptionType.DAILY_RECORD_NOT_FOUND);
                    assertThat(exception.getErrorCode()).isEqualTo(-404);
                });
    }

    private DailyRecord savedRecord() {
        DailyRecord record = DailyRecord.createDraft(
                SUBJECT_ID, LocalDate.of(2026, 8, 5), LocalDateTime.of(2026, 8, 5, 21, 0), "Asia/Seoul");
        ReflectionTestUtils.setField(record, "dailyRecordId", RECORD_ID);
        ReflectionTestUtils.setField(record, "status", DailyRecordStatus.SAVED);
        return record;
    }
}
