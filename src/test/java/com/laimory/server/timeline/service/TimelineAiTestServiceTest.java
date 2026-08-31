package com.laimory.server.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.laimory.server.common.privacy.PrivacyRedactor;
import com.laimory.server.timeline.ItemType;
import com.laimory.server.timeline.TimelineEventType;
import com.laimory.server.timeline.dto.AiTimelineResultRequest;
import com.laimory.server.timeline.dto.AiTimelineTaskInputResponse;
import com.laimory.server.timeline.dto.TimelineAiTestAiRequest;
import com.laimory.server.timeline.dto.TimelineAiTestRequest;
import com.laimory.server.timeline.dto.TimelineAiTestResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * AI 동기 테스트 오케스트레이터 단위 테스트 — 인프라 0.
 *
 * <p>고정하는 것: ① AI가 5xx로 낼 입력 오류를 앞에서 400으로 거른다, ② {@code taskId}는 서버가 매번 새로
 * 발행해 AI 요청과 응답에 <b>같은 값</b>으로 실린다, ③ AI로 나가는 텍스트는 운영과 같은 v1 치환을 거치되
 * {@code photoUrl}·{@code filename}은 원문이다, ④ 실패해도 재시도하지 않는다.
 *
 * <p>호출자 인증은 이 서비스의 책임이 아니라 security 계층이 소유하므로 여기서 다루지 않는다.
 *
 * <p>협력자가 client·설정·redactor뿐이라는 사실 자체가 "이 경로는 MySQL·Redis를 건드리지 않는다"는
 * 계약이다(배선 수준 증명은 {@code TimelineAiTestWiringTest}).
 */
class TimelineAiTestServiceTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final String RAW_ID = "6b5f2d3e-9c1a-4f88-9a2b-2f0d5c7e1a34";
    private static final String OTHER_RAW_ID = "6b5f2d3e-9c1a-4f88-9a2b-2f0d5c7e1a35";
    private static final String PHONE = "010-1234-5678";

    private final TimelineAiTestClient client = mock(TimelineAiTestClient.class);
    private final TimelineAiTestService service =
            new TimelineAiTestService(client, new PrivacyRedactor());

    // --- 입력 검증(AI 5xx 전에 400으로 거른다) ---

    @Test
    void rejectsInvalidInputWithoutCallingAi() {
        // window 누락·역전 — AI는 422 1001이지만 우리는 400으로 끝낸다.
        assertBadRequest(new TimelineAiTestRequest(LocalDate.of(2026, 6, 20), "Asia/Seoul",
                null, null, List.of(sourceItem(RAW_ID, payload()))));
        assertBadRequest(new TimelineAiTestRequest(LocalDate.of(2026, 6, 20), "Asia/Seoul",
                new AiTimelineTaskInputResponse.Window(offset(21, 0), offset(20, 0)),
                null, List.of(sourceItem(RAW_ID, payload()))));
        // recordDate 누락
        assertBadRequest(new TimelineAiTestRequest(null, "Asia/Seoul", window(), null,
                List.of(sourceItem(RAW_ID, payload()))));
        // sourceItems 0건·null — AI는 500 1102다.
        assertBadRequest(new TimelineAiTestRequest(LocalDate.of(2026, 6, 20), "Asia/Seoul",
                window(), null, List.of()));
        assertBadRequest(new TimelineAiTestRequest(LocalDate.of(2026, 6, 20), "Asia/Seoul",
                window(), null, null));
        // rawId 형식 오류·중복 — 실제 draft 경로와 같은 입력 규칙을 여기서도 지킨다.
        assertBadRequest(new TimelineAiTestRequest(LocalDate.of(2026, 6, 20), "Asia/Seoul",
                window(), null, List.of(sourceItem("NOT-A-UUID", payload()))));
        assertBadRequest(new TimelineAiTestRequest(LocalDate.of(2026, 6, 20), "Asia/Seoul",
                window(), null, List.of(sourceItem(RAW_ID, payload()), sourceItem(RAW_ID, payload()))));

        verifyNoInteractions(client);
    }

    // --- taskId 발행 ---

    @Test
    void issuesFreshTaskIdOnEveryCallAndSendsItToAi() {
        // client는 받은 taskId를 그대로 되싣는다(그 계약은 client 테스트가 소유한다).
        when(client.generate(any())).thenAnswer(invocation -> {
            TimelineAiTestAiRequest sent = invocation.getArgument(0);
            return new TimelineAiTestResponse(sent.taskId(), false, aiResult(false).events());
        });

        TimelineAiTestResponse first = service.generate("v1", request());
        TimelineAiTestResponse second = service.generate("v1", request());

        ArgumentCaptor<TimelineAiTestAiRequest> captor =
                ArgumentCaptor.forClass(TimelineAiTestAiRequest.class);
        verify(client, times(2)).generate(captor.capture());

        assertThat(first.taskId()).isEqualTo(captor.getAllValues().get(0).taskId());
        assertThat(second.taskId()).isEqualTo(captor.getAllValues().get(1).taskId());
        // 호출자가 지정할 수 없는, 호출마다 새로 발행되는 서버 UUID다.
        assertThat(first.taskId()).isNotEqualTo(second.taskId());
        assertThat(UUID.fromString(first.taskId())).isNotNull();
    }

    @Test
    void passesTimedOutSignalThrough() {
        when(client.generate(any())).thenReturn(aiResult(true));

        assertThat(service.generate("v1", request()).timedOut()).isTrue();
    }

    // --- privacy 치환 ---

    @Test
    void redactsOutboundTextButKeepsServerDerivedPhotoIdentifiers() {
        when(client.generate(any())).thenReturn(aiResult(false));
        ObjectNode photoPayload = JsonNodeFactory.instance.objectNode();
        photoPayload.put("description", "연락처 " + PHONE);
        photoPayload.put("clientPhotoUri", "content://media/external/images/1");
        photoPayload.put("filename", "0190a1b2-0001-7000-8000-000000000001.jpg");
        photoPayload.put("photoUrl", "https://cdn.example/photos/0190a1b2.jpg");
        ObjectNode userMemory = JsonNodeFactory.instance.objectNode();
        userMemory.put("summary", "비상 연락처는 " + PHONE);

        service.generate("v1", new TimelineAiTestRequest(
                LocalDate.of(2026, 6, 20), "Asia/Seoul", window(), userMemory,
                List.of(sourceItem(RAW_ID, photoPayload))));

        ArgumentCaptor<TimelineAiTestAiRequest> captor =
                ArgumentCaptor.forClass(TimelineAiTestAiRequest.class);
        verify(client).generate(captor.capture());
        JsonNode sentPayload = captor.getValue().sourceItems().getFirst().payload();

        assertThat(sentPayload.get("description").asText()).isEqualTo("연락처 [REDACTED_PHONE]");
        assertThat(sentPayload.get("clientPhotoUri").asText()).isEqualTo("[REDACTED_DEVICE_URI]");
        // AI가 photoUrl로 실제 이미지를 GET하므로 원문이어야 한다(filename도 서버 파생 식별자).
        assertThat(sentPayload.get("photoUrl").asText()).isEqualTo("https://cdn.example/photos/0190a1b2.jpg");
        assertThat(sentPayload.get("filename").asText())
                .isEqualTo("0190a1b2-0001-7000-8000-000000000001.jpg");
        assertThat(captor.getValue().userMemory().get("summary").asText())
                .isEqualTo("비상 연락처는 [REDACTED_PHONE]");
        // 필드 집합은 불변이고 원문 전화번호는 어디에도 없다.
        assertThat(sentPayload.properties()).hasSize(4);
        assertThat(captor.getValue().toString()).doesNotContain(PHONE);
    }

    @Test
    void keepsRequestUntouchedWhenPayloadIsAbsent() {
        when(client.generate(any())).thenReturn(aiResult(false));

        service.generate("v1", new TimelineAiTestRequest(
                LocalDate.of(2026, 6, 20), null, window(), null,
                List.of(sourceItem(RAW_ID, null))));

        ArgumentCaptor<TimelineAiTestAiRequest> captor =
                ArgumentCaptor.forClass(TimelineAiTestAiRequest.class);
        verify(client).generate(captor.capture());
        assertThat(captor.getValue().sourceItems().getFirst().payload()).isNull();
        // recordTimeZone 기본값은 AI가 소유한다 — 서버가 채워 넣지 않는다.
        assertThat(captor.getValue().recordTimeZone()).isNull();
    }

    // --- 실패 전파 ---

    @Test
    void propagatesAiFailureWithoutRetrying() {
        TimelineAiTestCallException failure =
                new TimelineAiTestCallException("AI가 오류를 반환했습니다.", 500, 1202);
        when(client.generate(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.generate("v1", request()))
                .isSameAs(failure);
        verify(client, times(1)).generate(any());
    }

    // --- fixtures ---

    private void assertBadRequest(TimelineAiTestRequest request) {
        assertThatThrownBy(() -> service.generate("v1", request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TimelineAiTestRequest request() {
        return new TimelineAiTestRequest(LocalDate.of(2026, 6, 20), "Asia/Seoul", window(), null,
                List.of(sourceItem(RAW_ID, payload()), sourceItem(OTHER_RAW_ID, payload())));
    }

    private static AiTimelineTaskInputResponse.Window window() {
        return new AiTimelineTaskInputResponse.Window(offset(0, 0), offset(21, 0));
    }

    private static AiTimelineTaskInputResponse.SourceItem sourceItem(String rawId, JsonNode payload) {
        return new AiTimelineTaskInputResponse.SourceItem(
                rawId, ItemType.STAY, offset(12, 0), offset(13, 0), payload);
    }

    private static ObjectNode payload() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("latitude", 37.5);
        payload.put("longitude", 127.0);
        return payload;
    }

    private static OffsetDateTime offset(int hour, int minute) {
        return OffsetDateTime.of(2026, 6, 20, hour, minute, 0, 0, KST);
    }

    private static TimelineAiTestResponse aiResult(boolean timedOut) {
        return new TimelineAiTestResponse("stub-task-id", timedOut, List.of(new AiTimelineResultRequest.Event(
                TimelineEventType.MEAL, "점심", null, null, null, null,
                offset(12, 0), offset(13, 0), List.of(RAW_ID))));
    }
}
