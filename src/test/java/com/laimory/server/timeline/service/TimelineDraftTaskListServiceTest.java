package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 진행 중 draft 작업 목록 조회 서비스 단위테스트(인프라 0). API 경계 계약을 고정한다:
 * principal 소유 PROCESSING taskId만 최신순 그대로 감싸고, 없으면 null이 아닌 빈 배열이다(T1·T2).
 */
@ExtendWith(MockitoExtension.class)
class TimelineDraftTaskListServiceTest {

    @Mock
    private TimelineTaskService timelineTaskService;

    @InjectMocks
    private TimelineDraftTaskListService service;

    private static final com.laimory.server.common.id.SubjectId SUBJECT_ID =
            com.laimory.server.testsupport.TestSubjects.id(7L);

    @Test
    void list_wrapsOwnerScopedProcessingTaskIds_newestFirst() {
        when(timelineTaskService.findProcessingTaskIds(SUBJECT_ID)).thenReturn(List.of("newer", "older"));

        assertThat(service.list("v1", SUBJECT_ID).taskIds()).containsExactly("newer", "older");
    }

    @Test
    void list_noProcessingTasks_returnsEmptyArrayNotNull() {
        when(timelineTaskService.findProcessingTaskIds(SUBJECT_ID)).thenReturn(List.of());

        assertThat(service.list("v1", SUBJECT_ID).taskIds()).isNotNull().isEmpty();
    }
}
