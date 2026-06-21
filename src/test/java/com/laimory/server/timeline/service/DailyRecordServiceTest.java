package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.repository.DailyRecordRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/** leaf 서비스가 자신의 레포로 위임하는지(서비스=레포 1개) 단위 검증. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class DailyRecordServiceTest {

    @Mock
    private DailyRecordRepository dailyRecordRepository;

    @InjectMocks
    private DailyRecordService dailyRecordService;

    @Test
    void findByUserIdAndRecordDate_delegatesToRepository() {
        LocalDate date = LocalDate.of(2026, 5, 8);
        DailyRecord record = DailyRecord.createDraft(0L, date);
        when(dailyRecordRepository.findByUserIdAndRecordDate(0L, date)).thenReturn(Optional.of(record));

        Optional<DailyRecord> result = dailyRecordService.findByUserIdAndRecordDate(0L, date);

        assertThat(result).containsSame(record);
        verify(dailyRecordRepository).findByUserIdAndRecordDate(0L, date);
    }

    @Test
    void save_delegatesToRepository() {
        DailyRecord record = DailyRecord.createDraft(0L, LocalDate.of(2026, 5, 8));
        when(dailyRecordRepository.save(record)).thenReturn(record);

        assertThat(dailyRecordService.save(record)).isSameAs(record);
        verify(dailyRecordRepository).save(record);
    }

    // --- findOrCreateDraft (lock-free 멱등 upsert) ---

    @Test
    void findOrCreateDraft_returnsExistingWhenFound_withoutSaving() {
        LocalDate date = LocalDate.of(2026, 5, 8);
        DailyRecord existing = DailyRecord.createDraft(0L, date);
        ReflectionTestUtils.setField(existing, "dailyRecordId", 100L);
        when(dailyRecordRepository.findByUserIdAndRecordDate(0L, date)).thenReturn(Optional.of(existing));

        DailyRecord result = dailyRecordService.findOrCreateDraft(0L, date);

        assertThat(result).isSameAs(existing);
        verify(dailyRecordRepository, never()).saveAndFlush(any());
    }

    @Test
    void findOrCreateDraft_createsWhenAbsent() {
        LocalDate date = LocalDate.of(2026, 5, 8);
        when(dailyRecordRepository.findByUserIdAndRecordDate(0L, date)).thenReturn(Optional.empty());
        DailyRecord created = DailyRecord.createDraft(0L, date);
        ReflectionTestUtils.setField(created, "dailyRecordId", 200L);
        when(dailyRecordRepository.saveAndFlush(any())).thenReturn(created);

        DailyRecord result = dailyRecordService.findOrCreateDraft(0L, date);

        assertThat(result).isSameAs(created);
        verify(dailyRecordRepository).saveAndFlush(any());
    }

    @Test
    void findOrCreateDraft_onDuplicateInsert_requeriesAndReturnsExisting() {
        LocalDate date = LocalDate.of(2026, 5, 8);
        DailyRecord existing = DailyRecord.createDraft(0L, date);
        ReflectionTestUtils.setField(existing, "dailyRecordId", 300L);
        // 첫 조회는 empty(경합 상대가 아직 commit 전), 위반 후 재조회는 상대가 만든 record를 본다.
        when(dailyRecordRepository.findByUserIdAndRecordDate(0L, date))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(dailyRecordRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("dup"));

        DailyRecord result = dailyRecordService.findOrCreateDraft(0L, date);

        assertThat(result).isSameAs(existing);
        verify(dailyRecordRepository, times(2)).findByUserIdAndRecordDate(0L, date);
    }
}
