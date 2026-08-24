package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.laimory.server.testsupport.TestSubjects.id;

import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** leaf 서비스가 자신의 레포로 위임하는지(서비스=레포 1개) 단위 검증. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class DailyRecordServiceTest {

    @Mock
    private DailyRecordRepository dailyRecordRepository;

    @Spy
    private Clock clock = Clock.fixed(Instant.parse("2026-05-08T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @InjectMocks
    private DailyRecordService dailyRecordService;

    private static final String ZONE = "Asia/Seoul";
    private static final LocalDateTime RECORD_AT = LocalDateTime.of(2026, 5, 8, 12, 0);
    private static final UUID SUBJECT = id(42L);

    @Test
    void findByUserIdAndRecordDate_delegatesToRepository() {
        LocalDate date = LocalDate.of(2026, 5, 8);
        DailyRecord record = DailyRecord.createDraft(SUBJECT, date, RECORD_AT, ZONE);
        when(dailyRecordRepository.findBySubjectIdAndRecordDate(SUBJECT, date)).thenReturn(Optional.of(record));

        Optional<DailyRecord> result = dailyRecordService.findBySubjectIdAndRecordDate(SUBJECT, date);

        assertThat(result).containsSame(record);
        verify(dailyRecordRepository).findBySubjectIdAndRecordDate(SUBJECT, date);
    }

    @Test
    void findByUserIdOrderByRecordDateDescDailyRecordIdDesc_delegatesToRepository() {
        DailyRecord record = DailyRecord.createDraft(SUBJECT, LocalDate.of(2026, 5, 8), RECORD_AT, ZONE);
        List<DailyRecord> records = List.of(record);
        when(dailyRecordRepository.findBySubjectIdOrderByRecordDateDescDailyRecordIdDesc(SUBJECT)).thenReturn(records);

        List<DailyRecord> result = dailyRecordService.findBySubjectIdOrderByRecordDateDescDailyRecordIdDesc(SUBJECT);

        assertThat(result).isSameAs(records);
        verify(dailyRecordRepository).findBySubjectIdOrderByRecordDateDescDailyRecordIdDesc(SUBJECT);
    }

    @Test
    void findByDailyRecordIdAndUserId_delegatesToRepository() {
        DailyRecord record = DailyRecord.createDraft(SUBJECT, LocalDate.of(2026, 5, 8), RECORD_AT, ZONE);
        when(dailyRecordRepository.findByDailyRecordIdAndSubjectId(100L, SUBJECT)).thenReturn(Optional.of(record));

        Optional<DailyRecord> result = dailyRecordService.findByDailyRecordIdAndSubjectId(100L, SUBJECT);

        assertThat(result).containsSame(record);
        verify(dailyRecordRepository).findByDailyRecordIdAndSubjectId(100L, SUBJECT);
    }

    @Test
    void save_delegatesToRepository() {
        DailyRecord record = DailyRecord.createDraft(SUBJECT, LocalDate.of(2026, 5, 8), RECORD_AT, ZONE);
        when(dailyRecordRepository.save(record)).thenReturn(record);

        assertThat(dailyRecordService.save(record)).isSameAs(record);
        verify(dailyRecordRepository).save(record);
    }

    @Test
    void findBySubjectIdAndRecordDateBetween_delegatesInclusiveRangeToRepository() {
        LocalDate start = LocalDate.of(2026, 5, 1);
        LocalDate end = LocalDate.of(2026, 5, 31);
        List<DailyRecord> records = List.of(DailyRecord.createDraft(SUBJECT, start, RECORD_AT, ZONE));
        when(dailyRecordRepository
                .findBySubjectIdAndRecordDateGreaterThanEqualAndRecordDateLessThanEqualOrderByRecordDateAsc(
                        SUBJECT, start, end))
                .thenReturn(records);

        List<DailyRecord> result =
                dailyRecordService.findBySubjectIdAndRecordDateBetweenOrderByRecordDateAsc(SUBJECT, start, end);

        assertThat(result).isSameAs(records);
        verify(dailyRecordRepository)
                .findBySubjectIdAndRecordDateGreaterThanEqualAndRecordDateLessThanEqualOrderByRecordDateAsc(
                        SUBJECT, start, end);
    }

    @Test
    void markSaved_delegatesConditionalUpdateWithEmotionAndClockNow() {
        // 감정과 SAVED 전이는 레포의 조건부 UPDATE 하나로 위임된다(별도 entity write 없음).
        LocalDateTime now = LocalDateTime.now(clock);
        when(dailyRecordRepository.markSaved(100L, SUBJECT, EmotionType.HAPPY, now)).thenReturn(1);

        assertThat(dailyRecordService.markSaved(100L, SUBJECT, EmotionType.HAPPY)).isEqualTo(1);

        verify(dailyRecordRepository).markSaved(100L, SUBJECT, EmotionType.HAPPY, now);
    }

    @Test
    void markSaved_returnsZeroWhenConditionalUpdateMatchesNoRow() {
        when(dailyRecordRepository.markSaved(100L, SUBJECT, EmotionType.UNHAPPY, LocalDateTime.now(clock)))
                .thenReturn(0);

        assertThat(dailyRecordService.markSaved(100L, SUBJECT, EmotionType.UNHAPPY)).isZero();
    }

    @Test
    void updateSavedEmotion_delegatesConditionalUpdateWithEmotionAndClockNow() {
        // SAVED 전용 감정 교체도 레포의 조건부 UPDATE 하나로 위임된다(status 불변·별도 entity write 없음).
        LocalDateTime now = LocalDateTime.now(clock);
        when(dailyRecordRepository.updateSavedEmotion(100L, SUBJECT, EmotionType.HAPPY, now)).thenReturn(1);

        assertThat(dailyRecordService.updateSavedEmotion(100L, SUBJECT, EmotionType.HAPPY)).isEqualTo(1);

        verify(dailyRecordRepository).updateSavedEmotion(100L, SUBJECT, EmotionType.HAPPY, now);
    }

    @Test
    void updateSavedEmotion_returnsZeroWhenConditionalUpdateMatchesNoRow() {
        when(dailyRecordRepository.updateSavedEmotion(100L, SUBJECT, EmotionType.UNHAPPY, LocalDateTime.now(clock)))
                .thenReturn(0);

        assertThat(dailyRecordService.updateSavedEmotion(100L, SUBJECT, EmotionType.UNHAPPY)).isZero();
    }

    // --- findOrCreateDraft (finalize 트랜잭션에 합류: REQUIRED) ---

    @Test
    void findOrCreateDraft_returnsExistingWhenFound_withoutSaving() {
        LocalDate date = LocalDate.of(2026, 5, 8);
        DailyRecord existing = DailyRecord.createDraft(SUBJECT, date, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(existing, "dailyRecordId", 100L);
        when(dailyRecordRepository.findBySubjectIdAndRecordDate(SUBJECT, date)).thenReturn(Optional.of(existing));

        DailyRecord result = dailyRecordService.findOrCreateDraft(SUBJECT, date, RECORD_AT, ZONE);

        assertThat(result).isSameAs(existing);
        verify(dailyRecordRepository, never()).save(any());
    }

    @Test
    void findOrCreateDraft_createsWhenAbsent() {
        LocalDate date = LocalDate.of(2026, 5, 8);
        when(dailyRecordRepository.findBySubjectIdAndRecordDate(SUBJECT, date)).thenReturn(Optional.empty());
        DailyRecord created = DailyRecord.createDraft(SUBJECT, date, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(created, "dailyRecordId", 200L);
        when(dailyRecordRepository.save(any())).thenReturn(created);

        DailyRecord result = dailyRecordService.findOrCreateDraft(SUBJECT, date, RECORD_AT, ZONE);

        assertThat(result).isSameAs(created);
        verify(dailyRecordRepository).save(any());
    }
}
