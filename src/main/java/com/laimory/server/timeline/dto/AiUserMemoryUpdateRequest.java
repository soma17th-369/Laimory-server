package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.TimelineEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * API→AI {@code POST /v1/user-memory} 접수 body — 양 저장소가 contract fixture로 고정하는 공개 계약이다.
 * 필드명·시각 포맷을 임의로 바꾸지 않는다.
 *
 * <p>AI 규격 초안은 {@code diaries[{date, content}]}로 "일기 본문"을 기대했지만 <b>우리 도메인에 일기
 * 본문도, "diary"라는 개념도 없다</b> — {@code DailyRecord}에 텍스트 필드가 없고 하루의 내용은
 * {@code TimelineEvent} 목록이다. 그래서 확정된 타임라인을 그대로 옮긴 {@code dailyTimelines}로 합의했다.
 * 이름·필드 구성은 형제 계약({@link AiTimelineTaskInputResponse}·{@link AiTimelineResultRequest})을 따른다:
 * 하루 식별은 {@code recordDate}/{@code recordTimeZone}, Event는 공통 6개 필드를 같은 순서로 두고
 * 흐름별 추가 필드 하나를 끝에 붙인다(결과 저장은 {@code sourceRawIds}, 여기는 {@code memo}).
 *
 * <p>{@code items[]}(사진 등)는 싣지 않는다 — vision 없이 기여가 없고 payload만 키운다. 장소·시간은 이미
 * AI가 그 item들로 쓴 {@code title}·{@code subtitle}에 녹아 있다. 행 PK({@code timelineEventId}·
 * {@code dailyRecordId}·{@code userId})도 싣지 않는다(입력 조회 응답과 같은 규칙 — 상관관계는 {@code taskId}).
 *
 * <p>⚠️ {@code taskToken}은 비밀 — 어떤 로그에도 포함하지 않는다.
 */
public record AiUserMemoryUpdateRequest(
        String taskId,
        String taskToken,
        @Schema(description = "갱신 기준이 되는 현재 User Memory 문서. 없으면 null", nullable = true)
        JsonNode userMemory,
        @Schema(description = "갱신 재료가 되는 확정 타임라인. 최소 1건")
        List<DailyTimeline> dailyTimelines
) {

    /**
     * 확정된 하루의 타임라인. 저장 직후 즉시 접수는 항상 1건이고, 하루 1회 재시도 배치는 한 사용자에게
     * 밀린 날들을 여기에 함께 싣는다(AI 계약 상한 7건).
     */
    public record DailyTimeline(
            LocalDate recordDate,
            @Schema(example = "Asia/Seoul") String recordTimeZone,
            @Schema(description = "사용자가 고른 하루 감정. 입력 경로가 없어 현재는 항상 null", nullable = true)
            EmotionType emotionType,
            List<Event> events
    ) {
    }

    /**
     * 확정된 타임라인 이벤트 하나. {@code question}은 우리 타임라인 AI가 쓴 문장이고 {@code memo}는 그에
     * 대한 사용자의 답이라, 둘을 함께 보내야 {@code "응 좋았어"} 같은 memo가 맥락을 잃지 않는다.
     */
    public record Event(
            @Schema(description = "Event 분류. 판별 불가면 UNKNOWN", example = "MEAL")
            TimelineEventType eventType,
            String title,
            @Schema(nullable = true) String subtitle,
            @Schema(description = "이 Event에 대해 타임라인 AI가 생성한 질문", nullable = true)
            String question,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
            OffsetDateTime startAt,
            @Schema(description = "단일 시점 이벤트면 null", nullable = true)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
            OffsetDateTime endAt,
            @Schema(description = "사용자가 쓴 메모(최대 500자). 없으면 null", nullable = true)
            String memo
    ) {
    }
}
