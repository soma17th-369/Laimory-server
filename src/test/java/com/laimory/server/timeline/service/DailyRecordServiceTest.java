package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.laimory.server.testsupport.TestSubjects.id;

import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** leaf 서비스가 자신의 레포로 위임하는지(서비스=레포 1개) 단위 검증. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class DailyRecordServiceTest {

    @Mock
    private DailyRecordRepository dailyRecordRepository;

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
