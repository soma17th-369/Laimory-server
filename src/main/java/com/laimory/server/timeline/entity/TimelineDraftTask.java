package com.laimory.server.timeline.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.laimory.server.timeline.TaskStatus;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * timeline draft 비동기 작업의 상태 모델. Redis에 JSON으로 저장된다(JPA 엔티티 아님).
 *
 * <p>AI는 이 JSON을 더 이상 직접 읽지 않는다 — task 입력(taskId·callbackToken·dailyRecordId·window)은
 * dispatch HTTP body로 전달되므로 이 shape는 서버 내부 계약이다. record 메타데이터(recordDate/recordAt/
 * recordTimezone)는 draft 요청 시점에 DailyRecord로 먼저 확정되므로 여기 저장하지 않는다 —
 * 날짜가 필요한 소비처(guard 해제)는 {@code dailyRecordId}로 DailyRecord를 조회한다.
 *
 * <p>{@code dailyRecordId}는 선생성된 DailyRecord의 ID로 <b>세 상태 모두</b> 보존된다 — 폴링은 이 ID로만
 * 결과를 조회해, record 삭제 후 같은 날짜가 재생성돼도 과거 task가 새 기록을 반환하지 않는다.
 *
 * <p>error는 FAILED일 때만 채워진다. callbackTokenHash는 종결(SUCCESS/FAILED) 후에도 보존된다 —
 * terminal 재콜백을 token-first로 검증하려면 해시가 남아 있어야 하기 때문이다(idempotent replay 흡수).
 * callbackTokenHash는 콜백 토큰의 SHA-256 해시이며, 원문 토큰은 저장하지 않는다(dispatch body로 AI에만 전달).
 *
 * <p>{@code timelineWindow}는 클라이언트가 요청에 지정한 AI 이벤트 생성 범위의 local 원본이다(서버는
 * 계산·보정 없이 pass-through, AI transport에서는 record timezone 기반 offset으로 변환된 사본이 나간다).
 * {@code processingStartedAt}은 전처리(검증·dedupe·enrich·선생성·staging 저장)를 마치고 PROCESSING task를
 * 저장하기 직전에 캡처한 Server 절대 시각(UTC ISO-8601)이다 — 폴링이 "AI 작업 대기 경과 시간"
 * ({@code elapsedSeconds})을 계산하는 기준이다. 두 필드 모두 PROCESSING 전용으로 종결 시 보존하지 않는다.
 *
 * <p>{@code userId}는 task owner(작성 요청자)다 — 세 상태 모두 보존되며, 폴링의 소유권 대조와 콜백의
 * 전이·guard 해제가 이 값을 쓴다(콜백은 {@code /s/api}라 request principal이 없다). reference type인
 * 이유는 배포 전 legacy JSON의 field 부재를 0으로 오인하지 않기 위해서다 — {@code null} owner는
 * fail-closed(폴링 404, 콜백 전이 거부) 대상이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true) // 구 shape(record 메타데이터 포함) 잔존 JSON을 TTL 소멸까지 관용 수용
public record TimelineDraftTask(
        TaskStatus status,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long dailyRecordId,
        @JsonInclude(JsonInclude.Include.NON_NULL) TimelineWindow timelineWindow,
        String error,
        String callbackTokenHash,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant processingStartedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long userId
) {

    /** 클라이언트가 요청에 지정한 AI 이벤트 생성 범위의 local 원본(offset 없음 — 서버 내부 보존용). */
    public record TimelineWindow(
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
    }

    public static TimelineDraftTask processing(long userId, long dailyRecordId, TimelineWindow timelineWindow,
                                               String callbackTokenHash, Instant processingStartedAt) {
        return new TimelineDraftTask(TaskStatus.PROCESSING, dailyRecordId, timelineWindow,
                null, callbackTokenHash, processingStartedAt, userId);
    }

    public static TimelineDraftTask success(long userId, long dailyRecordId, String callbackTokenHash) {
        return new TimelineDraftTask(TaskStatus.SUCCESS, dailyRecordId, null,
                null, callbackTokenHash, null, userId);
    }

    public static TimelineDraftTask failed(long userId, long dailyRecordId, String error,
                                           String callbackTokenHash) {
        return new TimelineDraftTask(TaskStatus.FAILED, dailyRecordId, null,
                error, callbackTokenHash, null, userId);
    }
}
