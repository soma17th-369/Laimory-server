package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.laimory.server.common.error.ErrorCode;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import com.laimory.server.timeline.repository.TimelineTaskStore;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** markFailed 계약 검증: task 실패 분류 코드만 수용(멤버십 가드) + 코드명이 error로 저장. 인프라 0. */
@ExtendWith(MockitoExtension.class)
class TimelineTaskServiceTest {

    @Mock
    private TimelineTaskStore timelineTaskStore;

    @InjectMocks
    private TimelineTaskService service;

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);

    @Test
    void markFailed_storesFailureCodeName() {
        service.markFailed("t", DATE, ErrorCode.ERROR_1009, "hash");

        ArgumentCaptor<TimelineDraftTask> task = ArgumentCaptor.forClass(TimelineDraftTask.class);
        verify(timelineTaskStore).save(eq("t"), task.capture(), any(Duration.class));
        assertThat(task.getValue().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getValue().error()).isEqualTo("ERROR_1009");
    }

    @Test
    void markFailed_rejectsNonTaskFailureCode() {
        // HTTP 에러 코드(ERROR_0400 등)를 task 상태로 저장하는 오용은 시그니처+가드가 차단한다.
        assertThatThrownBy(() -> service.markFailed("t", DATE, ErrorCode.ERROR_0400, "hash"))
                .isInstanceOf(IllegalStateException.class);
        verify(timelineTaskStore, never()).save(any(), any(), any());
    }
}
