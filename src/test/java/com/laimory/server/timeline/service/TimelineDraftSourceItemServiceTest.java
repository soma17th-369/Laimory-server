package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemBatchRepository;
import com.laimory.server.timeline.repository.TimelineDraftSourceItemRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TimelineDraftSourceItemServiceTest {

    @Mock
    private TimelineDraftSourceItemRepository repository;

    @Mock
    private TimelineDraftSourceItemBatchRepository batchRepository;

    @Mock
    private TimelineDraftSourceItem row;

    private TimelineDraftSourceItemService service;

    @BeforeEach
    void setUp() {
        service = new TimelineDraftSourceItemService(repository, batchRepository);
    }

    @Test
    void selectsSameFailedRowsAgainWithoutClaimWrites() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 7, 4, 0);
        when(repository.findExpired(cutoff, 1, 2, 250)).thenReturn(List.of(row));
        assertThat(service.findExpired(cutoff, 1, 2, 250)).containsExactly(row);
        assertThat(service.findExpired(cutoff, 1, 2, 250)).containsExactly(row);
        verify(repository, org.mockito.Mockito.times(2)).findExpired(cutoff, 1, 2, 250);
        org.mockito.Mockito.verifyNoMoreInteractions(repository);
        assertThatIllegalArgumentException().isThrownBy(() -> service.findExpired(cutoff, 1, 2, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> service.findExpired(cutoff, 1, 2, 1001));
    }
}
