package com.laimory.server.timeline.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.laimory.server.common.error.StrictErrorCodeDeserializer;
import com.laimory.server.common.id.SubjectId;
import com.laimory.server.common.id.SubjectIdJsonDeserializer;
import com.laimory.server.common.id.SubjectIdJsonSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TaskTokens;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * timeline draft 비동기 작업의 상태 모델. Redis에 JSON으로 저장된다(JPA 엔티티 아님).
 *
 * <p>AI는 이 JSON을 읽지 않는다 — dispatch HTTP body(taskId·taskToken·dailyRecordId·window)로
 * task를 받고 source 등 정규 입력은 서버간 입력 조회 API로 가져가므로 이 shape는 서버 내부 계약이다.
 * record 메타데이터(recordDate/recordAt/
 * recordTimezone)는 draft 요청 시점에 DailyRecord로 먼저 확정되므로 여기 저장하지 않는다.
 *
 * <p>{@code dailyRecordId}는 선생성된 DailyRecord의 ID로 <b>세 상태 모두</b> 보존된다 — 폴링은 이 ID로만
 * 결과를 조회해, record 삭제 후 같은 날짜가 재생성돼도 과거 task가 새 기록을 반환하지 않는다.
 *
 * <p>error는 FAILED일 때만 채워진다. {@code tokenHash}는 현재 처리 단계가 사용하는 opaque task token의
 * SHA-256 hash이며 terminal 재요청 검증을 위해 종결 뒤에도 보존한다. {@code stage}는
 * PROCESSING task의 서버간 처리 순서를 제한하는 Redis 내부 상태이며 terminal에는 보존하지 않는다.
 *
 * <p>{@code timelineWindow}는 클라이언트가 요청에 지정한 AI 이벤트 생성 범위의 local 원본이다(서버는
 * 계산·보정 없이 pass-through, AI transport에서는 record timezone 기반 offset으로 변환된 사본이 나간다).
 * {@code processingStartedAt}은 전처리(검증·dedupe·enrich·선생성·staging 저장)를 마치고 PROCESSING task를
 * 저장하기 직전에 캡처한 Server 절대 시각(UTC ISO-8601)이다 — 폴링이 "AI 작업 대기 경과 시간"
 * ({@code elapsedSeconds})을 계산하는 기준이다. 두 필드 모두 PROCESSING 전용으로 종결 시 보존하지 않는다.
 *
 * <p>{@code subjectId}는 task owner(콘텐츠 주체)다 — 세 상태 모두 보존되며, 폴링의 소유권 대조와 콜백의
 * terminal 전이·완료 push가 이 값을 쓴다(콜백은 {@code /s/api}라 request principal이 없다).
 */
public record TimelineDraftTask(
        TaskStatus status,
        long dailyRecordId,
        @JsonInclude(JsonInclude.Include.NON_NULL) TimelineWindow timelineWindow,
        @JsonDeserialize(using = StrictErrorCodeDeserializer.class)
        Integer error,
        String tokenHash,
        @JsonInclude(JsonInclude.Include.NON_NULL) ProcessStage stage,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant processingStartedAt,
        @JsonSerialize(using = SubjectIdJsonSerializer.class)
        @JsonDeserialize(using = SubjectIdJsonDeserializer.class)
        SubjectId subjectId
) {

    public TimelineDraftTask {
        Objects.requireNonNull(status, "status");
        if (dailyRecordId <= 0) {
            throw new IllegalArgumentException("dailyRecordId는 양수여야 합니다");
        }
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(subjectId, "subjectId");
        if (status == TaskStatus.PROCESSING && processingStartedAt == null) {
            throw new IllegalArgumentException("PROCESSING task에는 processingStartedAt이 필요합니다");
        }
        if (status == TaskStatus.PROCESSING && stage == null) {
            throw new IllegalArgumentException("PROCESSING task에는 stage가 필요합니다");
        }
        if (status != TaskStatus.PROCESSING && stage != null) {
            throw new IllegalArgumentException("terminal task에는 stage를 저장하지 않습니다");
        }
    }

    /** 현재 처리 단계의 task token 검증. */
    public boolean matchesToken(String token) {
        return TaskTokens.matches(token, tokenHash);
    }

    /** 클라이언트가 요청에 지정한 AI 이벤트 생성 범위의 local 원본(offset 없음 — 서버 내부 보존용). */
    public record TimelineWindow(
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
    }

    public static TimelineDraftTask processing(SubjectId subjectId, long dailyRecordId, TimelineWindow timelineWindow,
                                               String tokenHash, Instant processingStartedAt) {
        return new TimelineDraftTask(TaskStatus.PROCESSING, dailyRecordId, timelineWindow, null,
                tokenHash, ProcessStage.INPUT_PENDING, processingStartedAt, subjectId);
    }

    public TimelineDraftTask withTokenAndStage(String nextTokenHash, ProcessStage nextStage) {
        if (status != TaskStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING task만 token과 stage를 변경할 수 있습니다: " + status);
        }
        return new TimelineDraftTask(status, dailyRecordId, timelineWindow, error,
                Objects.requireNonNull(nextTokenHash, "nextTokenHash"),
                Objects.requireNonNull(nextStage, "nextStage"), processingStartedAt, subjectId);
    }

    public static TimelineDraftTask success(SubjectId subjectId, long dailyRecordId, String tokenHash) {
        return new TimelineDraftTask(TaskStatus.SUCCESS, dailyRecordId, null, null,
                tokenHash, null, null, subjectId);
    }

    public static TimelineDraftTask failed(SubjectId subjectId, long dailyRecordId, int error,
                                           String tokenHash) {
        return new TimelineDraftTask(TaskStatus.FAILED, dailyRecordId, null, error,
                tokenHash, null, null, subjectId);
    }
}
