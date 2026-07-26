package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.laimory.server.timeline.repository.TimelinePhotoDeleteJobRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class TimelinePhotoDeleteJobServiceTest {

    @Mock
    private TimelinePhotoDeleteJobRepository repository;

    private TimelinePhotoDeleteJobService service;

    @BeforeEach
    void setUp() {
        service = new TimelinePhotoDeleteJobService(repository);
    }

    @Test
    void insertIfAbsent_reportsWhetherRepositoryInserted() {
        when(repository.insertIfAbsent(1L, "hash/photos/photo.jpg")).thenReturn(1, 0);

        assertThat(service.insertIfAbsent(1L, "hash/photos/photo.jpg")).isTrue();
        assertThat(service.insertIfAbsent(1L, "hash/photos/photo.jpg")).isFalse();
    }

    @Test
    void insertIfAbsent_rejectsValuesThatInsertIgnoreCouldOtherwiseCoerce() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.insertIfAbsent(0L, "hash/photos/photo.jpg"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.insertIfAbsent(1L, "한글/photos/photo.jpg"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.insertIfAbsent(1L, " "));

        verify(repository, never()).insertIfAbsent(0L, "hash/photos/photo.jpg");
        verify(repository, never()).insertIfAbsent(1L, "한글/photos/photo.jpg");
        verify(repository, never()).insertIfAbsent(1L, " ");
    }

    @Test
    void findOldest_usesBoundedFirstPage() {
        service.findOldest(1_000);

        verify(repository).findOldest(PageRequest.of(0, 1_000));
        assertThatIllegalArgumentException().isThrownBy(() -> service.findOldest(0));
        assertThatIllegalArgumentException().isThrownBy(() -> service.findOldest(1_001));
    }

    @Test
    void deleteSucceeded_skipsEmptyInput() {
        assertThat(service.deleteSucceeded(List.of())).isZero();

        verify(repository, never()).deleteAllByJobIdIn(List.of());
    }
}
