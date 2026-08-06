package com.laimory.server.timeline.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.TimelineEventType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * API→AI {@code POST /v1/user-memory} 접수 body — 양 저장소가 contract fixture로 고정하는 공개 계약이다.
 * 필드명·시각 포맷을 임의로 바꾸지 않는다.
 *
 * <p>AI 규격 초안은 {@code diaries[{date, content}]}로 "일기 본문"을 기대했지만 <b>우리 도메인에 일기
 * 본문이 없다</b> — {@code DailyRecord}에 텍스트 필드가 없고 하루의 내용은 {@code TimelineEvent} 목록이다.
 * 그래서 확정된 타임라인을 구조화한 {@code events[]}로 보내기로 합의했다.
 *
 * <p>{@code items[]}(사진 등)는 싣지 않는다 — vision 없이 기여가 없고 payload만 키운다. 장소·시간은 이미
 * AI가 그 item들로 쓴 {@code title}·{@code subtitle}에 녹아 있다. 행 PK({@code timelineEventId}·
 * {@code dailyRecordId}·{@code userId})도 싣지 않는다(입력 조회 응답과 같은 규칙 — 상관관계는 {@code taskId}).
 *
 * <p>시각은 dispatch window와 같은 offset 포함 ISO-8601이며 {@code recordTimeZone} 기준이다.
 *
 * <p>⚠️ {@code taskToken}은 비밀 — 어떤 로그에도 포함하지 않는다.
 */
public record AiUserMemoryUpdateRequest(
        String taskId,
        String taskToken,
        JsonNode userMemory,
        List<Diary> diaries
) {

    /**
     * 갱신 재료가 되는 하루. 이번 범위에서는 항상 1건이고, 배열은 밀린 날을 함께 싣게 될 때를 위한
     * 확장 여지다.
     */
    public record Diary(
            LocalDate date,
            String recordTimeZone,
            EmotionType emotionType,
            List<Event> events
    ) {
    }

    /**
     * 확정된 타임라인 이벤트 하나. {@code question}은 우리 타임라인 AI가 쓴 문장이고 {@code memo}는 그에
     * 대한 사용자의 답이라, 둘을 함께 보내야 {@code "응 좋았어"} 같은 memo가 맥락을 잃지 않는다.
     */
    public record Event(
            TimelineEventType eventType,
            String title,
            String subtitle,
            String question,
            String memo,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
            OffsetDateTime startAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
            OffsetDateTime endAt
    ) {
    }
}
