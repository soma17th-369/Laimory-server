package com.laimory.server.timeline.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.laimory.server.timeline.dto.CreateDraftTaskRequest;
import com.laimory.server.timeline.payload.CalendarPayload;
import com.laimory.server.timeline.payload.HealthPayload;
import com.laimory.server.timeline.payload.MovementPayload;
import com.laimory.server.timeline.payload.NotificationPayload;
import com.laimory.server.timeline.payload.PhotoPayload;
import com.laimory.server.timeline.payload.StayPayload;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Swagger request body 예시({@link TimelineApi#CREATE_DRAFT_EXAMPLE})가 실제로 유효한 요청인지 보장한다 —
 * 6개 itemType의 payload가 올바른 구체 타입으로 역직렬화돼야 한다(예시가 DTO와 어긋나 헷갈리게 되는 것 방지).
 */
class TimelineApiExampleTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void createDraft_예시는_6개_itemType의_올바른_payload로_역직렬화된다() throws Exception {
        CreateDraftTaskRequest req = mapper.readValue(TimelineApi.CREATE_DRAFT_EXAMPLE, CreateDraftTaskRequest.class);

        assertThat(req.sourceItems()).hasSize(6);
        assertThat(req.sourceItems()).anySatisfy(i -> assertThat(i.payload()).isInstanceOf(PhotoPayload.class));
        assertThat(req.sourceItems()).anySatisfy(i -> assertThat(i.payload()).isInstanceOf(CalendarPayload.class));
        assertThat(req.sourceItems()).anySatisfy(i -> assertThat(i.payload()).isInstanceOf(StayPayload.class));
        assertThat(req.sourceItems()).anySatisfy(i -> assertThat(i.payload()).isInstanceOf(MovementPayload.class));
        assertThat(req.sourceItems()).anySatisfy(i -> assertThat(i.payload()).isInstanceOf(HealthPayload.class));
        assertThat(req.sourceItems()).anySatisfy(i -> assertThat(i.payload()).isInstanceOf(NotificationPayload.class));
    }
}
