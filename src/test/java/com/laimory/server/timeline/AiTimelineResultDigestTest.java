package com.laimory.server.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 결과 재시도 동일성 지문의 안정성을 고정한다.
 *
 * <p>지문 값이 바뀌면 배포 시점에 살아 있던 receipt가 전부 무효가 되므로, Jackson 업그레이드나 DTO
 * component 재배치가 조용히 지나가지 않도록 golden 상수로 못박는다.
 */
class AiTimelineResultDigestTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    /** 아래 {@link #request()}의 지문. 값이 바뀌었다면 의도한 계약 변경인지 먼저 확인한다. */
    private static final String GOLDEN = "kfIzt6QISmw4yyD/rFaIZtycehH80EkzTEYSI5iWWUM=";

    private static AiTimelineResultRequest request() {
        return new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, "점심", "회사 근처", "누구와 함께였나요?",
                OffsetDateTime.of(DATE.atTime(12, 0), KST),
                OffsetDateTime.of(DATE.atTime(13, 0), KST),
                List.of("raw-1", "raw-2"))));
    }

    private static AiTimelineResultRequest withTitle(String title) {
        AiTimelineResultRequest.Event e = request().events().getFirst();
        return new AiTimelineResultRequest(List.of(new AiTimelineResultRequest.Event(
                e.eventType(), title, e.subtitle(), e.question(), e.startAt(), e.endAt(), e.sourceRawIds())));
    }

    @Test
    void of_isPinnedToGoldenValue() {
        assertThat(AiTimelineResultDigest.of(request())).isEqualTo(GOLDEN);
    }

    @Test
    void of_structurallyEqualRequests_produceSameDigest() {
        assertThat(AiTimelineResultDigest.of(request()))
                .isEqualTo(AiTimelineResultDigest.of(request()));
    }

    @Test
    void of_differentTitle_producesDifferentDigest() {
        assertThat(AiTimelineResultDigest.of(withTitle("저녁")))
                .isNotEqualTo(AiTimelineResultDigest.of(request()));
    }

    @Test
    void of_differentSourceOrder_producesDifferentDigest() {
        // 정규화하지 않는다 — 실제 HTTP 재시도는 같은 버퍼를 재전송하므로 순서까지 같아야 한다는 계약이다.
        AiTimelineResultRequest.Event e = request().events().getFirst();
        AiTimelineResultRequest reordered = new AiTimelineResultRequest(
                List.of(new AiTimelineResultRequest.Event(e.eventType(), e.title(), e.subtitle(),
                        e.question(), e.startAt(), e.endAt(), List.of("raw-2", "raw-1"))));

        assertThat(AiTimelineResultDigest.of(reordered))
                .isNotEqualTo(AiTimelineResultDigest.of(request()));
    }

    @Test
    void of_nullableFieldsPresentOrAbsent_produceDifferentDigests() {
        AiTimelineResultRequest.Event e = request().events().getFirst();
        AiTimelineResultRequest withoutQuestion = new AiTimelineResultRequest(
                List.of(new AiTimelineResultRequest.Event(e.eventType(), e.title(), e.subtitle(),
                        null, e.startAt(), e.endAt(), e.sourceRawIds())));

        assertThat(AiTimelineResultDigest.of(withoutQuestion))
                .isNotEqualTo(AiTimelineResultDigest.of(request()));
    }

    @Test
    void of_sameInstantWithDifferentOffset_producesDifferentDigest() {
        // 객체 수준에서는 offset에 민감하다. 실무에서 문제되지 않는 이유는 Spring 바인딩이
        // ADJUST_DATES_TO_CONTEXT_TIME_ZONE으로 UTC 정규화한 뒤에야 서비스가 값을 보기 때문이다.
        AiTimelineResultRequest.Event e = request().events().getFirst();
        AiTimelineResultRequest utc = new AiTimelineResultRequest(
                List.of(new AiTimelineResultRequest.Event(e.eventType(), e.title(), e.subtitle(),
                        e.question(), e.startAt().withOffsetSameInstant(ZoneOffset.UTC),
                        e.endAt(), e.sourceRawIds())));

        assertThat(AiTimelineResultDigest.of(utc)).isNotEqualTo(AiTimelineResultDigest.of(request()));
    }

    @Test
    void of_returnsBase64EncodedSha256() {
        assertThat(AiTimelineResultDigest.of(request())).matches("[A-Za-z0-9+/]{43}=");
    }
}
