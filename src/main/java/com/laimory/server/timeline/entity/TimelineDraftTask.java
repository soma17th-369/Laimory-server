package com.laimory.server.timeline.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.laimory.server.common.error.StrictErrorCodeDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.laimory.server.timeline.ProcessStage;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TaskTokens;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

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
 *
 * <p>{@code retryReceipt}는 응답 유실 뒤 재요청을 인지하기 위한 흔적이다({@link RetryReceipt}).
 * PROCESSING 전용이며 terminal 전이 시 stage와 함께 버린다.
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
        UUID subjectId,
        @JsonInclude(JsonInclude.Include.NON_NULL) RetryReceipt retryReceipt
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
        // 요청 데이터로 도달할 수 없는 조합이라 프로그래밍 오류다 — 400으로 감추지 않는다.
        if (status != TaskStatus.PROCESSING && retryReceipt != null) {
            throw new IllegalStateException("terminal task에는 retry receipt를 저장하지 않습니다");
        }
    }

    /** 현재 처리 단계의 task token 검증. */
    public boolean matchesToken(String token) {
        return TaskTokens.matches(token, tokenHash);
    }

    /**
     * 직전 단계에서 소비된 token의 재제시 검증. 현재 token 검증({@link #matchesToken})과 별개 축이며,
     * receipt가 없으면 언제나 false다.
     */
    public boolean matchesPreviousToken(String token) {
        return retryReceipt != null && TaskTokens.matches(token, retryReceipt.previousTokenHash());
    }

    /** 재시도 허용 창이 지났는지. receipt가 없으면 판정 대상이 아니라 true를 반환한다. */
    public boolean retryWindowExpired(Instant now) {
        return retryReceipt == null || !now.isBefore(retryReceipt.retryableUntil());
    }

    /** 클라이언트가 요청에 지정한 AI 이벤트 생성 범위의 local 원본(offset 없음 — 서버 내부 보존용). */
    public record TimelineWindow(
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
    }

    /**
     * 응답 유실 뒤 같은 요청이 다시 왔을 때 그것을 재시도로 인지하기 위한 흔적.
     *
     * <p>{@code previousTokenHash}는 직전 단계 전이에서 소비된 token의 SHA-256이다 — 재시도의 인증 수단이며
     * "같은 결과를 다시 제출해 다음 token을 재발급받을" 권한만 갖는다(입력 조회·callback에는 쓸 수 없다).
     * {@code retryableUntil}은 <b>첫 요청이 도착한 시각</b> 기준 절대 마감이다 — 최초 PROCESSING 시작
     * 기준으로 고정된 task TTL과 마찬가지로 재발급으로 미끄러지지 않는다.
     *
     * <p>{@code claimedAt}은 결과 저장을 선점한 시각이다 — 회전이 commit 뒤로 미뤄져 stage만으로는
     * "이미 누가 transaction을 돌리고 있는" 구간이 구분되지 않으므로, 이 표식이 뒤늦은 same-token
     * 재시도의 transaction 재진입과 선점 중 FAILED callback의 terminal 확정을 막는다(없으면 요청이
     * 겹쳐 돌아 Event·Item이 중복 삽입되거나 terminal이 PROCESSING으로 되쓰인다).
     *
     * <p>"graph가 확정됐다"는 stage가 말한다 — MySQL commit 뒤에야 token 회전과 stage 전이를 하나의
     * Redis write로 수행하므로 {@code CALLBACK_PENDING}은 저장이 끝났다는 뜻이고, 선점만 된 구간은
     * {@code RESULT_PENDING} + {@code claimedAt}으로 구분된다. 별도 commit 표식을 두지 않는다.
     *
     * <p>세 값 모두 hash와 시각이라 token 원문도 요청 본문도 보존하지 않는다.
     */
    public record RetryReceipt(
            String previousTokenHash,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @JsonInclude(JsonInclude.Include.NON_NULL) Instant claimedAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant retryableUntil
    ) {

        public RetryReceipt {
            Objects.requireNonNull(previousTokenHash, "previousTokenHash");
            Objects.requireNonNull(retryableUntil, "retryableUntil");
        }

        public boolean claimed() {
            return claimedAt != null;
        }
    }

    public static TimelineDraftTask processing(UUID subjectId, long dailyRecordId, TimelineWindow timelineWindow,
                                               String tokenHash, Instant processingStartedAt) {
        return new TimelineDraftTask(TaskStatus.PROCESSING, dailyRecordId, timelineWindow, null,
                tokenHash, ProcessStage.INPUT_PENDING, processingStartedAt, subjectId, null);
    }

    /**
     * token과 stage를 바꾼 사본. {@code retryReceipt}는 <b>보존한다</b> — 재시도 재발급이 같은 stage에서
     * token만 다시 돌리기 때문이다. receipt를 든 채 허용되지 않는 stage로 나가는 조합은 compact
     * constructor가 거절하므로 보존이 위험을 만들지 않는다.
     */
    public TimelineDraftTask withTokenAndStage(String nextTokenHash, ProcessStage nextStage) {
        if (status != TaskStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING task만 token과 stage를 변경할 수 있습니다: " + status);
        }
        return new TimelineDraftTask(status, dailyRecordId, timelineWindow, error,
                Objects.requireNonNull(nextTokenHash, "nextTokenHash"),
                Objects.requireNonNull(nextStage, "nextStage"), processingStartedAt, subjectId, retryReceipt);
    }

    /** retry receipt만 교체한 사본. {@code null}을 넘기면 제거한다(선점 보상). */
    public TimelineDraftTask withRetryReceipt(RetryReceipt nextReceipt) {
        if (status != TaskStatus.PROCESSING) {
            throw new IllegalStateException("PROCESSING task만 retry receipt를 가질 수 있습니다: " + status);
        }
        return new TimelineDraftTask(status, dailyRecordId, timelineWindow, error, tokenHash, stage,
                processingStartedAt, subjectId, nextReceipt);
    }

    public static TimelineDraftTask success(UUID subjectId, long dailyRecordId, String tokenHash) {
        return new TimelineDraftTask(TaskStatus.SUCCESS, dailyRecordId, null, null,
                tokenHash, null, null, subjectId, null);
    }

    public static TimelineDraftTask failed(UUID subjectId, long dailyRecordId, int error,
                                           String tokenHash) {
        return new TimelineDraftTask(TaskStatus.FAILED, dailyRecordId, null, error,
                tokenHash, null, null, subjectId, null);
    }
}
