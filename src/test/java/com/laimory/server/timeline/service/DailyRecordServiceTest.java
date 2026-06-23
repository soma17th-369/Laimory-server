package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
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

    @Test
    void findByUserIdAndRecordDate_delegatesToRepository() {
        LocalDate date = LocalDate.of(2026, 5, 8);
        DailyRecord record = DailyRecord.createDraft(0L, date, RECORD_AT, ZONE);
        when(dailyRecordRepository.findByUserIdAndRecordDate(0L, date)).thenReturn(Optional.of(record));

        Optional<DailyRecord> result = dailyRecordService.findByUserIdAndRecordDate(0L, date);

        assertThat(result).containsSame(record);
        verify(dailyRecordRepository).findByUserIdAndRecordDate(0L, date);
    }

    @Test
    void save_delegatesToRepository() {
        DailyRecord record = DailyRecord.createDraft(0L, LocalDate.of(2026, 5, 8), RECORD_AT, ZONE);
        when(dailyRecordRepository.save(record)).thenReturn(record);

        assertThat(dailyRecordService.save(record)).isSameAs(record);
        verify(dailyRecordRepository).save(record);
    }

    // --- findOrCreateDraft (finalize 트랜잭션에 합류: REQUIRED) ---

    @Test
    void findOrCreateDraft_returnsExistingWhenFound_withoutSaving() {
        LocalDate date = LocalDate.of(2026, 5, 8);
        DailyRecord existing = DailyRecord.createDraft(0L, date, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(existing, "dailyRecordId", 100L);
        when(dailyRecordRepository.findByUserIdAndRecordDate(0L, date)).thenReturn(Optional.of(existing));

        DailyRecord result = dailyRecordService.findOrCreateDraft(0L, date, RECORD_AT, ZONE);

        assertThat(result).isSameAs(existing);
        verify(dailyRecordRepository, never()).save(any());
    }

    @Test
    void findOrCreateDraft_createsWhenAbsent() {
        LocalDate date = LocalDate.of(2026, 5, 8);
        when(dailyRecordRepository.findByUserIdAndRecordDate(0L, date)).thenReturn(Optional.empty());
        DailyRecord created = DailyRecord.createDraft(0L, date, RECORD_AT, ZONE);
        ReflectionTestUtils.setField(created, "dailyRecordId", 200L);
        when(dailyRecordRepository.save(any())).thenReturn(created);

        DailyRecord result = dailyRecordService.findOrCreateDraft(0L, date, RECORD_AT, ZONE);

        assertThat(result).isSameAs(created);
        verify(dailyRecordRepository).save(any());
    }
}
