package com.laimory.server.timeline.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.laimory.server.common.error.StrictErrorCodeDeserializer;
import com.laimory.server.timeline.TaskStatus;
import com.laimory.server.timeline.TaskTokens;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * timeline draft 비동기 작업의 상태 모델. Redis에 JSON으로 저장된다(JPA 엔티티 아님).
 *
 * <p>AI는 이 JSON을 읽지 않는다 — dispatch HTTP body(taskId·입력 토큰)로 task를 받고 나머지 입력은 서버간
 * 입력 조회 API로 가져가므로 이 shape는 서버 내부 계약이다. record 메타데이터(recordDate/recordAt/
 * recordTimezone)는 draft 요청 시점에 DailyRecord로 먼저 확정되므로 여기 저장하지 않는다.
 *
 * <p>{@code dailyRecordId}는 선생성된 DailyRecord의 ID로 <b>세 상태 모두</b> 보존된다 — 폴링은 이 ID로만
 * 결과를 조회해, record 삭제 후 같은 날짜가 재생성돼도 과거 task가 새 기록을 반환하지 않는다.
 *
 * <p>error는 FAILED일 때만 채워진다. 단계별 토큰 hash 셋(입력·결과 저장·콜백)은 종결(SUCCESS/FAILED)
 * 후에도 보존된다 — terminal 재요청도 hash를 먼저 검증한 뒤 각 경로의 재시도 규칙으로 판정한다.
 * 세 값 모두 SHA-256 해시이며 원문 토큰은 저장하지 않는다(T1만 dispatch body로 AI에 전달하고,
 * T2·T3는 이전 단계 토큰에서 결정적으로 파생해 각 응답에 싣는다 — {@link com.laimory.server.timeline.TaskTokens}).
 *
 * <p>{@code inputTokenHash}·{@code resultTokenHash}는 이 계약 이전에 만들어진 Redis task JSON에는 없어
 * null일 수 있다(배포 시점 in-flight task — 해당 task의 단계별 인증은 실패하고 PROCESSING TTL이 회수한다).
 *
 * <p>{@code timelineWindow}는 클라이언트가 요청에 지정한 AI 이벤트 생성 범위의 local 원본이다(서버는
 * 계산·보정 없이 pass-through, AI transport에서는 record timezone 기반 offset으로 변환된 사본이 나간다).
 * {@code processingStartedAt}은 전처리(검증·dedupe·enrich·선생성·staging 저장)를 마치고 PROCESSING task를
 * 저장하기 직전에 캡처한 Server 절대 시각(UTC ISO-8601)이다 — 폴링이 "AI 작업 대기 경과 시간"
 * ({@code elapsedSeconds})을 계산하는 기준이다. 두 필드 모두 PROCESSING 전용으로 종결 시 보존하지 않는다.
 *
 * <p>{@code userId}는 task owner(작성 요청자)다 — 세 상태 모두 보존되며, 폴링의 소유권 대조와 콜백의
 * terminal 전이·완료 push가 이 값을 쓴다(콜백은 {@code /s/api}라 request principal이 없다).
 */
public record TimelineDraftTask(
        TaskStatus status,
        long dailyRecordId,
        @JsonInclude(JsonInclude.Include.NON_NULL) TimelineWindow timelineWindow,
        @JsonDeserialize(using = StrictErrorCodeDeserializer.class)
        Integer error,
        @JsonInclude(JsonInclude.Include.NON_NULL) String inputTokenHash,
        @JsonInclude(JsonInclude.Include.NON_NULL) String resultTokenHash,
        String callbackTokenHash,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant processingStartedAt,
        long userId
) {

    public TimelineDraftTask {
        Objects.requireNonNull(status, "status");
        if (dailyRecordId <= 0) {
            throw new IllegalArgumentException("dailyRecordId는 양수여야 합니다");
        }
        // 입력·결과 토큰 hash는 구 계약 task JSON에 없어 null을 허용한다(콜백 hash는 두 계약 모두 필수).
        Objects.requireNonNull(callbackTokenHash, "callbackTokenHash");
        if (userId <= 0) {
            throw new IllegalArgumentException("userId는 양수여야 합니다");
        }
        if (status == TaskStatus.PROCESSING && processingStartedAt == null) {
            throw new IllegalArgumentException("PROCESSING task에는 processingStartedAt이 필요합니다");
        }
    }

    /** 단계별 토큰 hash 묶음 — 세 상태 전이 모두 그대로 이월된다. */
    public record TokenHashes(
            String inputTokenHash,
            String resultTokenHash,
            String callbackTokenHash
    ) {

        public TokenHashes {
            Objects.requireNonNull(callbackTokenHash, "callbackTokenHash");
        }
    }

    public TokenHashes tokenHashes() {
        return new TokenHashes(inputTokenHash, resultTokenHash, callbackTokenHash);
    }

    /** 입력 조회 단계(T1) 토큰 검증. */
    public boolean matchesInputToken(String token) {
        return TaskTokens.matches(token, inputTokenHash);
    }

    /** 결과 저장 단계(T2) 토큰 검증. */
    public boolean matchesResultToken(String token) {
        return TaskTokens.matches(token, resultTokenHash);
    }

    /** 콜백 단계(T3) 토큰 검증. */
    public boolean matchesCallbackToken(String token) {
        return TaskTokens.matches(token, callbackTokenHash);
    }

    /** 클라이언트가 요청에 지정한 AI 이벤트 생성 범위의 local 원본(offset 없음 — 서버 내부 보존용). */
    public record TimelineWindow(
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
    }

    public static TimelineDraftTask processing(long userId, long dailyRecordId, TimelineWindow timelineWindow,
                                               TokenHashes tokenHashes, Instant processingStartedAt) {
        return new TimelineDraftTask(TaskStatus.PROCESSING, dailyRecordId, timelineWindow, null,
                tokenHashes.inputTokenHash(), tokenHashes.resultTokenHash(), tokenHashes.callbackTokenHash(),
                processingStartedAt, userId);
    }

    public static TimelineDraftTask success(long userId, long dailyRecordId, TokenHashes tokenHashes) {
        return new TimelineDraftTask(TaskStatus.SUCCESS, dailyRecordId, null, null,
                tokenHashes.inputTokenHash(), tokenHashes.resultTokenHash(), tokenHashes.callbackTokenHash(),
                null, userId);
    }

    public static TimelineDraftTask failed(long userId, long dailyRecordId, int error,
                                           TokenHashes tokenHashes) {
        return new TimelineDraftTask(TaskStatus.FAILED, dailyRecordId, null, error,
                tokenHashes.inputTokenHash(), tokenHashes.resultTokenHash(), tokenHashes.callbackTokenHash(),
                null, userId);
    }
}
