package com.laimory.server.timeline.service;

import com.laimory.server.timeline.CallbackTokens;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TimelineDefaults;
import com.laimory.server.timeline.dto.DraftTaskCallbackRequest;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI 작성 콜백 오케스트레이터. task 로드 + 토큰 검증 + 멱등 + status 분기 + finalize/Redis 전이를 합성한다.
 *
 * <p>순서가 load-bearing이다(spec §Callback 고정):
 * <ol>
 *   <li>task 로드(없음/만료 → 404)</li>
 *   <li><b>토큰 검증(401) 먼저</b> — terminal 멱등 단축보다 앞 (토큰이 유일 보호장치이고, terminal task도 해시를 보존하므로 가능)</li>
 *   <li>terminal이면 idempotent return(200)</li>
 *   <li>FAILED → markFailed</li>
 *   <li>SUCCESS → draft 행을 DB에서 로드. 없으면(이미 finalize돼 삭제됨) Redis SUCCESS만 set(멱등 복구).
 *       있으면 단일 트랜잭션 {@code appendDailyTimeline}(검증+영속+draft 삭제)을 호출하고 <b>커밋 이후에만</b> Redis SUCCESS.
 *       실패 시 롤백 → markFailed.</li>
 *   <li>그 외 status → 400</li>
 * </ol>
 *
 * <p>finalize 트랜잭션은 별도 빈 {@link DailyTimelineService#appendDailyTimeline}이 경계다 —
 * 이 클래스가 그 빈을 통해(Spring 프록시 경유) 호출하므로 트랜잭션이 실제로 활성화된다(self-invocation 아님).
 * Redis SUCCESS는 appendDailyTimeline이 반환(=DB 커밋)한 뒤에만 set돼, polling이 'SUCCESS인데 record 없음'을 보지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TimelineCallbackService {

    private final TimelineTaskService timelineTaskService;
    private final TimelineDraftSourceItemService timelineDraftSourceItemService;
    private final DailyTimelineService dailyTimelineService;
    private final DailyRecordService dailyRecordService;

    public void handleCallback(String applicationVersion, String taskId,
                               String callbackToken, DraftTaskCallbackRequest request) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineDraftTask task = timelineTaskService.find(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found: " + taskId));

        // 1. 토큰 검증을 먼저 한다(멱등 단축보다 앞). terminal task도 해시를 보존하므로 재콜백도 토큰으로 막힌다.
        if (!CallbackTokens.matches(callbackToken, task.callbackTokenHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid callback token");
        }

        // 2. 멱등: 이미 종결(SUCCESS/FAILED)된 task면 재처리하지 않는다(콜백 재전송 방어).
        if (task.status() != TaskStatus.PROCESSING) {
            return;
        }

        LocalDate recordDate = task.recordDate();
        String callbackTokenHash = task.callbackTokenHash();

        // AI가 자신의 실패를 보고한 경우: 그대로 FAILED 기록(draft는 cleanup이 보관기간 후 정리).
        if (request.status() == TaskStatus.FAILED) {
            timelineTaskService.markFailed(taskId, recordDate, request.error(), callbackTokenHash);
            return;
        }
        if (request.status() != TaskStatus.SUCCESS) {
            throw new IllegalArgumentException("invalid callback status: " + request.status());
        }

        // 3. SUCCESS: draft 행을 DB에서 로드.
        List<TimelineDraftSourceItem> draftRows = timelineDraftSourceItemService.findByTaskId(taskId);
        if (draftRows.isEmpty()) {
            // PROCESSING인데 draft 부재 = 보통 이전 finalize가 record 생성+draft 삭제를 커밋한 상태(멱등 복구).
            // 단, record가 실제로 존재할 때만 SUCCESS를 확정한다 — record 없이 SUCCESS로 두면 polling이
            // 'daily record missing for SUCCESS task' 500을 낸다(draft도 record도 없는 이상 상태). 없으면 FAILED로 종결.
            boolean recordExists = dailyRecordService
                    .findByUserIdAndRecordDate(TimelineDefaults.DEFAULT_USER_ID, recordDate).isPresent();
            if (recordExists) {
                timelineTaskService.markSuccess(taskId, recordDate, callbackTokenHash);
            } else {
                timelineTaskService.markFailed(taskId, recordDate,
                        "draft rows missing but no finalized daily record", callbackTokenHash);
            }
            return;
        }

        // 4. finalize: 단일 트랜잭션(검증→record/events/items 저장→draft 삭제). 커밋 후에만 Redis SUCCESS.
        //    검증/SAVED 재확인 실패는 롤백되고 여기서 잡아 FAILED로 기록한다(콜백은 200).
        try {
            dailyTimelineService.appendDailyTimeline(
                    TimelineDefaults.DEFAULT_USER_ID, recordDate, task.recordAt(), task.recordTimezone(),
                    draftRows, request.events());
            timelineTaskService.markSuccess(taskId, recordDate, callbackTokenHash);
        } catch (IllegalArgumentException | IllegalStateException e) {
            timelineTaskService.markFailed(taskId, recordDate, e.getMessage(), callbackTokenHash);
        }
    }
}
