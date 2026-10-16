package com.laimory.server.testsupport;

import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.util.UUID;

/** draft task 상태 픽스처. */
public final class TimelineDraftTaskFixtures {

    private TimelineDraftTaskFixtures() {
    }

    /**
     * #593 전 유실 처리가 task 값이 사라진 뒤 다시 기록하던 FAILED(#565) — owner와 실패 코드만 있고
     * {@code dailyRecordId}·{@code tokenHash}가 없다. 서버는 더 이상 쓰지 않지만 배포 전·롤링 배포 중 옛 서버가 남긴
     * 값을 읽는 쪽 계약을 고정하는 데 쓴다.
     */
    public static TimelineDraftTask legacyLostFailed(UUID subjectId, int error) {
        return new TimelineDraftTask(TaskStatus.FAILED, null, null, error, null, null, null, subjectId, null);
    }
}
