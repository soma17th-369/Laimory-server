package com.laimory.server.timeline.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laimory.server.common.error.BusinessException;
import com.laimory.server.common.error.ExceptionType;
import com.laimory.server.common.privacy.RedactionType;
import com.laimory.server.timeline.ProcessStage;
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
 * AI 입력 조회 오케스트레이터 — 서버가 소유하는 정규 AI 입력을 조립하고 다음 RESULT token을 발급한다.
 *
 * <p>순서가 load-bearing이다: task 조회 → <b>현재 task token 검증</b> → PROCESSING/INPUT_PENDING 확인
 * <b>다음에야</b> 개인 데이터(record·source)를 읽는다. 이 endpoint는 {@code /s/api}라 request
 * principal이 없고 token만이 인증 수단이다.
 *
 * <p>응답에는 DB 식별자를 담지 않는다({@code userId}·{@code dailyRecordId}·행 PK 없음). 시각은 staging의
 * wall-clock에 record timezone을 붙인 offset 값으로 나가며, 결과 저장이 같은 규칙으로 되돌린다.
 * 응답 조립 뒤 token hash와 ProcessStage를 CAS로 함께 교체하면서 PROCESSING TTL을 다시 확보한다.
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
        if (!task.matchesToken(taskToken)) {
            log.warn("ai task input token mismatch: taskId={}", taskId);
            throw new BusinessException(ExceptionType.TASK_TOKEN_MISMATCH);
        }
        if (task.status() != TaskStatus.PROCESSING) {
            log.warn("ai task input on terminal task: taskId={} status={}", taskId, task.status());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }
        if (task.stage() != ProcessStage.INPUT_PENDING) {
            log.warn("ai task input on invalid stage: taskId={} stage={}", taskId, task.stage());
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }

        DailyRecord record = dailyRecordService.findById(task.dailyRecordId())
                .orElseThrow(() -> new BusinessException(ExceptionType.DAILY_RECORD_NOT_FOUND));
        ZoneId recordZone = ZoneId.of(record.getRecordTimezone());
        List<TimelineDraftSourceItem> sources = timelineDraftSourceItemService.findByTaskId(taskId);
        String resultToken = TaskTokens.generate();

        AiTimelineTaskInputResponse response = new AiTimelineTaskInputResponse(
                taskId,
                record.getRecordDate(),
                record.getRecordTimezone(),
                toOffsetWindow(task.timelineWindow(), recordZone),
                sources.stream().map(source -> toSourceItem(source, recordZone)).toList(),
                resultToken);

        if (!timelineTaskService.rotateTokenAndStage(
                taskId, task, TaskTokens.hash(resultToken), ProcessStage.RESULT_PENDING)) {
            log.warn("ai task input token rotation lost race: taskId={}", taskId);
            throw new BusinessException(ExceptionType.DRAFT_TASK_STATE_CONFLICT);
        }
        return response;
    }

    /** Redis에 보존된 local window에 record timezone을 붙여 offset 값으로 변환한다. */
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
                redactClientPhotoUri(source.getPayload()));
    }

    /**
     * AI 전달 직전 PHOTO {@code clientPhotoUri} 값 전체를 고정 token으로 바꾼다. storage payload는 이미
     * v1 치환 저장본이라 전체 redaction을 다시 돌리지 않고 storage 예외였던 이 필드만 치환한다(계획 §2.2).
     *
     * <p>{@code source.getPayload()}는 Hibernate 관리 엔티티 필드라 변형하지 않는다 — 해당 필드가 있을 때만
     * deep copy 뒤 치환하고, 없으면 read-only 직렬화 용도이므로 기존 인스턴스를 그대로 반환한다.
     */
    private static JsonNode redactClientPhotoUri(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return payload;
        }
        JsonNode clientPhotoUri = payload.get("clientPhotoUri");
        if (clientPhotoUri == null || !clientPhotoUri.isTextual()) {
            return payload;
        }
        ObjectNode copy = payload.deepCopy();
        copy.put("clientPhotoUri", RedactionType.DEVICE_URI.token());
        return copy;
    }

    private static OffsetDateTime toOffset(LocalDateTime value, ZoneId recordZone) {
        return value == null ? null : value.atZone(recordZone).toOffsetDateTime();
    }
}
