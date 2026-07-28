package com.laimory.server.timeline.service;

import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TaskTokens;
import com.laimory.server.timeline.dto.AiTimelineTaskInputResponse;
import com.laimory.server.timeline.entity.DailyRecord;
import com.laimory.server.timeline.entity.TimelineDraftSourceItem;
import com.laimory.server.timeline.entity.TimelineDraftTask;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 입력 조회 오케스트레이터 — 서버가 소유하는 정규 AI 입력을 조립한다.
 *
 * <p>순서가 load-bearing이다: task 조회 → <b>입력 토큰 검증</b> → PROCESSING 확인 <b>다음에야</b> 개인
 * 데이터(record·source)를 읽는다. 이 endpoint는 {@code /s/api}라 request principal이 없고 토큰만이
 * 인증 수단이므로, 검증 전에 데이터를 읽지 않는 것이 유출 방지선이다.
 *
 * <p>응답에는 DB 식별자를 담지 않는다({@code userId}·{@code dailyRecordId}·행 PK 없음). 시각은 staging의
 * wall-clock에 record timezone을 붙인 offset 값으로 나가며, 결과 저장이 같은 규칙으로 되돌린다.
 *
 * <p>검증을 통과하면 PROCESSING TTL을 다시 확보한다 — AI 추론은 이 응답 이후에 시작되므로, dispatch
 * 시점부터 3분을 소진시키면 정상 처리가 뒤 단계에서 만료로 잘릴 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineAiTaskInputService {

    private final TimelineTaskService timelineTaskService;
    private final DailyRecordService dailyRecordService;
    private final TimelineDraftSourceItemService timelineDraftSourceItemService;

    public AiTimelineTaskInputResponse getInput(String applicationVersion, String taskId, String taskToken) {
        // applicationVersion: 버전별 처리 분기 지점(현재 단일 버전이라 분기 없음).
        TimelineDraftTask task = timelineTaskService.find(taskId)
                .orElseThrow(() -> new BusinessException(ExceptionType.DRAFT_TASK_NOT_FOUND));
        if (!task.matchesInputToken(taskToken)) {
            // 어느 단계 토큰이 틀렸는지는 응답으로 구분해 주지 않는다(로그로만).
            log.warn("ai task input token mismatch: taskId={}", taskId);
            throw new BusinessException(ExceptionType.TASK_TOKEN_MISMATCH);
        }
        if (task.status() != TaskStatus.PROCESSING) {
            log.warn("ai task input on terminal task: taskId={} status={}", taskId, task.status());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }

        DailyRecord record = dailyRecordService.findById(task.dailyRecordId())
                .orElseThrow(() -> new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND));
        ZoneId recordZone = ZoneId.of(record.getRecordTimezone());
        List<TimelineDraftSourceItem> sources = timelineDraftSourceItemService.findByTaskId(taskId);

        // TTL 갱신은 응답 조립 성공 뒤에 한다 — 조회가 실패하는 task의 수명을 연장하지 않는다.
        timelineTaskService.refreshProcessing(taskId, task);

        return new AiTimelineTaskInputResponse(
                taskId,
                record.getRecordDate(),
                record.getRecordTimezone(),
                toOffsetWindow(task.timelineWindow(), recordZone),
                sources.stream().map(source -> toSourceItem(source, recordZone)).toList(),
                TaskTokens.deriveResultToken(taskToken, taskId));
    }

    /**
     * Redis에 보존된 local window(클라이언트 원본)에 record timezone을 붙여 offset 값으로 변환한다.
     * 구 계약 task에는 window가 없을 수 있어 null을 그대로 통과시킨다.
     */
    private static AiTimelineTaskInputResponse.Window toOffsetWindow(
            TimelineDraftTask.TimelineWindow window, ZoneId recordZone) {
        if (window == null) {
            return null;
        }
        return new AiTimelineTaskInputResponse.Window(
                toOffset(window.startTime(), recordZone), toOffset(window.endTime(), recordZone));
    }

    private static AiTimelineTaskInputResponse.SourceItem toSourceItem(
            TimelineDraftSourceItem source, ZoneId recordZone) {
        return new AiTimelineTaskInputResponse.SourceItem(
                source.getRawId(), source.getItemType(),
                toOffset(source.getStartAt(), recordZone), toOffset(source.getEndAt(), recordZone),
                source.getPayload());
    }

    private static OffsetDateTime toOffset(LocalDateTime value, ZoneId recordZone) {
        return value == null ? null : value.atZone(recordZone).toOffsetDateTime();
    }
}
