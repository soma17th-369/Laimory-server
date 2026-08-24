package com.laimory.server.timeline.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laimory.server.timeline.EmotionType;
import com.laimory.server.timeline.TimelineEventType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * AgentCore 공통 wrapper 직렬화 계약(#338). wrapper는 <b>봉투일 뿐</b>이라는 성질을 고정한다 —
 * {@code payload}를 다시 직렬화한 결과가 기존 접수 body와 정확히 같아야 하고, 필드명·시각 포맷도
 * HTTP mode 계약 그대로여야 한다.
 *
 * <p>직렬화는 production과 같은 <b>Boot가 구성한 {@code ObjectMapper}</b>로 한다 —
 * {@code new ObjectMapper()}는 JavaTimeModule이 없어 {@code OffsetDateTime}이 계약과 다른 포맷으로
 * 나가므로, 이 테스트가 그 회귀도 함께 막는다.
 */
class AgentCoreDispatchRequestTest {

    private static final ObjectMapper MAPPER = bootObjectMapper();
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    /** production 경로와 같은 mapper — Boot 자동설정이 만든 빈을 그대로 쓴다. */
    private static ObjectMapper bootObjectMapper() {
        AtomicReference<ObjectMapper> holder = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> holder.set(context.getBean(ObjectMapper.class)));
        return holder.get();
    }

    private static AiTimelineDispatchRequest timelineRequest() {
        return new AiTimelineDispatchRequest("task-20260722-001", "task-token-001", 42L,
                new AiTimelineDispatchRequest.Window(
                        OffsetDateTime.of(2026, 7, 22, 0, 0, 0, 0, KST),
                        OffsetDateTime.of(2026, 7, 23, 0, 0, 0, 0, KST)));
    }

    private static AiUserMemoryUpdateRequest userMemoryRequest() {
        return new AiUserMemoryUpdateRequest("task-20260804-002", "task-token-002", null,
                List.of(new AiUserMemoryUpdateRequest.DailyTimeline(
                        LocalDate.of(2026, 8, 4), "Asia/Seoul", EmotionType.HAPPY,
                        List.of(new AiUserMemoryUpdateRequest.Event(
                                TimelineEventType.MEAL, "점심", null, "무엇을 드셨나요?",
                                OffsetDateTime.of(2026, 8, 4, 12, 10, 0, 0, KST),
                                OffsetDateTime.of(2026, 8, 4, 13, 0, 0, 0, KST),
                                "맛있었다")))));
    }

    @Test
    void timelineWrapper_serializesRequestTypeAndPayloadContract() throws Exception {
        String json = MAPPER.writeValueAsString(AgentCoreDispatchRequest.timeline(timelineRequest()));

        assertThat(json).isEqualTo("{\"requestType\":\"TIMELINE\",\"payload\":{"
                + "\"taskId\":\"task-20260722-001\",\"taskToken\":\"task-token-001\","
                + "\"dailyRecordId\":42,\"window\":{"
                + "\"startAt\":\"2026-07-22T00:00:00+09:00\",\"endAt\":\"2026-07-23T00:00:00+09:00\"}}}");
    }

    @Test
    void timelineWrapper_payloadIsByteIdenticalToHttpBody() throws Exception {
        // wrapper가 payload를 건드리지 않는다는 계약 — HTTP mode가 보내는 body와 정확히 같아야 한다.
        AiTimelineDispatchRequest request = timelineRequest();

        String payload = MAPPER.writeValueAsString(
                MAPPER.readTree(MAPPER.writeValueAsString(AgentCoreDispatchRequest.timeline(request)))
                        .get("payload"));

        assertThat(payload).isEqualTo(MAPPER.writeValueAsString(request));
    }

    @Test
    void userMemoryWrapper_serializesRequestTypeAndKeepsPayload() throws Exception {
        AiUserMemoryUpdateRequest request = userMemoryRequest();

        String json = MAPPER.writeValueAsString(AgentCoreDispatchRequest.userMemoryUpdate(request));

        assertThat(MAPPER.readTree(json).get("requestType").asText()).isEqualTo("USER_MEMORY_UPDATE");
        assertThat(MAPPER.writeValueAsString(MAPPER.readTree(json).get("payload")))
                .isEqualTo(MAPPER.writeValueAsString(request));
        // 기존 계약대로 base 문서 부재는 null로 실린다(키 생략이 아니다).
        assertThat(json).contains("\"userMemory\":null");
        // 시각은 offset ISO-8601 고정 포맷.
        assertThat(json).contains("\"startAt\":\"2026-08-04T12:10:00+09:00\"");
    }

    @Test
    void factories_rejectNullPayload() {
        assertThatThrownBy(() -> AgentCoreDispatchRequest.timeline(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AgentCoreDispatchRequest.userMemoryUpdate(null))
                .isInstanceOf(NullPointerException.class);
    }
}
